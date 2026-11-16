package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.DataStoreBoundaryRuntime;
import net.bytebuddy.asm.Advice;

/** Only constant type/method identity is passed to the bootstrap bridge. */
public final class DataStoreBoundaryAdvice {
    private DataStoreBoundaryAdvice() { }

    public static final class Synchronous {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static Object enter(@Advice.Origin("#t") String owner, @Advice.Origin("#m") String operation,
                                   @Advice.Origin("#d") String descriptor) {
            return DataStoreBoundaryRuntime.enter(owner, operation, false, descriptor);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter Object token, @Advice.Thrown Throwable failure) {
            DataStoreBoundaryRuntime.exit(token, failure);
        }
    }

    public static final class Asynchronous {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static Object enter(@Advice.Origin("#t") String owner, @Advice.Origin("#m") String operation,
                                   @Advice.Origin("#d") String descriptor) {
            return DataStoreBoundaryRuntime.enter(owner, operation, true, descriptor);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter Object token, @Advice.Thrown Throwable failure) {
            DataStoreBoundaryRuntime.exit(token, failure);
        }
    }
}
