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
 * exceptional method exits, not every thrown/caught exception. Advice suppresses
 * observer failures, including exception-message extraction.
 */
public final class MethodTraceAdvice {

    private MethodTraceAdvice() {
    }

    /** enter + exit, exceptions become EXCEPTION events (trackMethods && trackExceptions). */
    public static final class Full {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void enter(@Advice.Origin("#t") String type, @Advice.Origin("#m") String method,
                                 @Advice.Origin("#d") String descriptor) {
            TraceRuntime.onMethodEnter(type, method, descriptor);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Origin("#t") String type, @Advice.Origin("#m") String method,
                                @Advice.Origin("#d") String descriptor,
                                @Advice.Thrown Throwable thrown) {
            if (thrown == null) {
                TraceRuntime.onMethodExit(type, method, descriptor);
            } else {
                TraceRuntime.onException(type, method, descriptor, thrown.getClass().getName(), thrown.getMessage());
            }
        }
    }

    /** enter + exit, exceptions propagate silently (trackMethods && !trackExceptions). */
    public static final class NoExceptions {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void enter(@Advice.Origin("#t") String type, @Advice.Origin("#m") String method,
                                 @Advice.Origin("#d") String descriptor) {
            TraceRuntime.onMethodEnter(type, method, descriptor);
        }

        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void exit(@Advice.Origin("#t") String type, @Advice.Origin("#m") String method,
                                @Advice.Origin("#d") String descriptor) {
            TraceRuntime.onMethodExit(type, method, descriptor);
        }
    }

    /** exceptions only (!trackMethods && trackExceptions). */
    public static final class ExceptionsOnly {
        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Origin("#t") String type, @Advice.Origin("#m") String method,
                                @Advice.Origin("#d") String descriptor,
                                @Advice.Thrown Throwable thrown) {
            if (thrown != null) {
                TraceRuntime.onException(type, method, descriptor, thrown.getClass().getName(), thrown.getMessage());
            }
        }
    }
}
