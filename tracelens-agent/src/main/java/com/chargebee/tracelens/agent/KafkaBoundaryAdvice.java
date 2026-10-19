package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.MessagingBoundaryRuntime;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/** No Kafka linkage, payload reads, header mutation, callback wrapping, or Future waits. */
public final class KafkaBoundaryAdvice {
    private KafkaBoundaryAdvice() { }

    public static final class Producer {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static Object enter(@Advice.Argument(0) Object record) {
            return MessagingBoundaryRuntime.enterKafkaSend(record);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter Object token, @Advice.Thrown Throwable failure) {
            MessagingBoundaryRuntime.exitPublish(token, failure);
        }
    }

    public static final class Consumer {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static Object enter(@Advice.This Object consumer) {
            return MessagingBoundaryRuntime.enterKafkaPoll(consumer);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter Object token,
                                @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object records,
                                @Advice.Thrown Throwable failure) {
            MessagingBoundaryRuntime.exitKafkaPoll(token, records, failure);
        }
    }
}
