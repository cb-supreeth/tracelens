package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.ApacheHttpClientTraceRuntime;
import java.io.IOException;
import net.bytebuddy.asm.Advice;

/** No Apache types are linked from the agent or its bootstrap bridge. */
public final class ApacheHttpClientAdvice {
    private ApacheHttpClientAdvice() { }

    public static final class Boundary {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static Object enter(@Advice.Argument(0) Object host, @Advice.Argument(1) Object request) {
            return ApacheHttpClientTraceRuntime.enter(host, request);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter Object token, @Advice.Return Object response,
                                @Advice.Thrown Throwable failure) {
            ApacheHttpClientTraceRuntime.exit(token, response, failure);
        }
    }

    public static final class Dispatch {
        @Advice.OnMethodEnter
        public static void enter(@Advice.Argument(0) Object route, @Advice.Argument(1) Object request)
                throws IOException {
            if (!ApacheHttpClientTraceRuntime.beforeDispatch(route, request)) {
                // Only fail closed if a header we injected cannot be safely removed.
                throw new IOException("TraceLens could not remove its correlation header before HTTP dispatch");
            }
        }
    }
}
