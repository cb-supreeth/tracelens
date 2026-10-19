package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.HttpTraceRuntime;
import net.bytebuddy.asm.Advice;

/** Woven HTTP entry points reference only bootstrap types, never servlet APIs. */
public final class HttpRequestAdvice {
    private HttpRequestAdvice() { }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Object enter(@Advice.Argument(0) Object request, @Advice.Argument(1) Object response) {
        return HttpTraceRuntime.enter(request, response);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter Object scope, @Advice.Thrown Throwable failure) {
        HttpTraceRuntime.exit(scope, failure);
    }

    public static final class AsyncStart {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void enter(@Advice.This Object context,
                                 @Advice.Argument(value = 0, readOnly = false) Runnable task) {
            task = HttpTraceRuntime.wrapAsync(context, task);
        }
    }
}
