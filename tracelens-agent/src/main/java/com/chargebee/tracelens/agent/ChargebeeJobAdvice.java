package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.ChargebeeJobRuntime;
import net.bytebuddy.asm.Advice;

/** Inlined, fail-open callbacks; never catch, replace, or suppress a host exception. */
public final class ChargebeeJobAdvice {
    private ChargebeeJobAdvice() { }

    public static final class Dispatch {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static Object enter(@Advice.Argument(0) Object job) {
            return ChargebeeJobRuntime.enter(job);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter Object token, @Advice.Thrown Throwable failure) {
            ChargebeeJobRuntime.exit(token, failure);
        }
    }

    public static final class Audit {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void enter(@Advice.Argument(0) Object job, @Advice.Argument(3) Object status,
                                 @Advice.Argument(4) Throwable failure) {
            ChargebeeJobRuntime.audit(job, status, failure);
        }
    }
}
