package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.TraceRuntime;
import net.bytebuddy.asm.Advice;

/**
 * Method-level events: entry, exit, exception. The advice bytecode is inlined into
 * woven methods; the calls go to the bootstrap-visible TraceRuntime.
 *
 * "#t"/"#m" origins resolve to constant strings at weave time — no Class literals,
 * no extra class loading on the hot path.
 *
 * The current application matcher excludes constructors. Exceptions here mean
 * exceptional method exits, not every thrown/caught exception. Advice does not
 * suppress failures from the basic tracing callbacks.
 */
public final class MethodTraceAdvice {

    private MethodTraceAdvice() {
    }

    /** enter + exit, exceptions become EXCEPTION events (trackMethods && trackExceptions). */
    public static final class Full {
        @Advice.OnMethodEnter
        public static void enter(@Advice.Origin("#t") String type, @Advice.Origin("#m") String method) {
            TraceRuntime.onMethodEnter(type, method);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Origin("#t") String type, @Advice.Origin("#m") String method,
                                @Advice.Thrown Throwable thrown) {
            if (thrown == null) {
                TraceRuntime.onMethodExit(type, method);
            } else {
                TraceRuntime.onException(type, method, thrown.getClass().getName(), thrown.getMessage());
            }
        }
    }

    /** enter + exit, exceptions propagate silently (trackMethods && !trackExceptions). */
    public static final class NoExceptions {
        @Advice.OnMethodEnter
        public static void enter(@Advice.Origin("#t") String type, @Advice.Origin("#m") String method) {
            TraceRuntime.onMethodEnter(type, method);
        }

        @Advice.OnMethodExit
        public static void exit(@Advice.Origin("#t") String type, @Advice.Origin("#m") String method) {
            TraceRuntime.onMethodExit(type, method);
        }
    }

    /** exceptions only (!trackMethods && trackExceptions). */
    public static final class ExceptionsOnly {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Origin("#t") String type, @Advice.Origin("#m") String method,
                                @Advice.Thrown Throwable thrown) {
            if (thrown != null) {
                TraceRuntime.onException(type, method, thrown.getClass().getName(), thrown.getMessage());
            }
        }
    }
}
