package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.HttpClientTraceRuntime;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/** Only bootstrap bridge APIs are referenced from woven JDK bytecode. */
public final class HttpClientAdvice {
    private HttpClientAdvice() { }

    public static class Boundary {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static Object enter(@Advice.This Object connection) {
            return HttpClientTraceRuntime.enter(connection);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter Object token, @Advice.Origin("#m") String method,
                                @Advice.FieldValue("responseCode") int responseCode,
                                @Advice.Thrown Throwable failure) {
            HttpClientTraceRuntime.exit(token, method, responseCode, failure);
        }
    }

    public static class WriteGuard {
        @Advice.OnMethodEnter
        public static void beforeWrite(@Advice.This Object connection,
                                       @Advice.FieldValue(value = "requests", typing = Assigner.Typing.DYNAMIC) Object requests,
                                       @Advice.FieldValue(value = "userHeaders", typing = Assigner.Typing.DYNAMIC) Object userHeaders) throws java.io.IOException {
            if (!HttpClientTraceRuntime.beforeWrite(connection, requests, userHeaders)) {
                throw new java.io.IOException("TraceLens could not safely remove its headers before an external redirect");
            }
        }
    }
}
