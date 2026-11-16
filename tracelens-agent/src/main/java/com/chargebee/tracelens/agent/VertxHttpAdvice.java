package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.VertxHttpTraceRuntime;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/** Woven Vert.x code references bootstrap bridge types only. */
public final class VertxHttpAdvice {
    private VertxHttpAdvice() { }

    public static final class ServerHandler {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void enter(@Advice.Argument(value = 0, readOnly = false,
                typing = Assigner.Typing.DYNAMIC) Object handler) {
            handler = VertxHttpTraceRuntime.wrapServerHandler(handler);
        }
    }

    public static final class Callbacks {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void enter(@Advice.AllArguments(readOnly = false,
                typing = Assigner.Typing.DYNAMIC) Object[] arguments) {
            arguments = VertxHttpTraceRuntime.wrapCallbacks(arguments);
        }
    }

    public static final class Response {
        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.This Object response, @Advice.Origin("#m") String operation,
                                @Advice.Thrown Throwable failure) {
            VertxHttpTraceRuntime.responseExit(response, operation, failure);
        }
    }

    public static final class ResponseException {
        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.This Object response, @Advice.Argument(0) Throwable error) {
            VertxHttpTraceRuntime.responseException(response, error);
        }
    }
}
