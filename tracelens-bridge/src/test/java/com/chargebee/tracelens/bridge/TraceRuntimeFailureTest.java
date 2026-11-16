package com.chargebee.tracelens.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class TraceRuntimeFailureTest {
    @AfterEach void cleanup() { TraceRuntime.unregisterSink(); TraceContext.clear(); }

    @Test void everySinkCallbackIsContainedAndCounted() {
        TraceRuntime.registerSink(new TraceRuntime.Sink() {
            public void onLine(String s, String c, int line) { throw new AssertionError(); }
            public void onMethodEnter(String s, String c, String m) { throw new AssertionError(); }
            public void onMethodExit(String s, String c, String m) { throw new AssertionError(); }
            public void onException(String s, String c, String m, String t, String v) { throw new AssertionError(); }
            public void onDbRoute(String s, String route) { throw new AssertionError(); }
            public void onExternalCall(String s, String system, String detail) { throw new AssertionError(); }
            public void onBoundary(String s, Map<String, String> attributes) { throw new AssertionError(); }
        });
        long failures = TraceRuntime.observerFailureCount();
        try (TraceScope ignored = TraceContext.activate(new TraceSnapshot("trace", "lens", "user", null))) {
            assertThatCode(() -> {
                TraceRuntime.onLine("Fixture", 1);
                TraceRuntime.onMethodEnter("Fixture", "method");
                TraceRuntime.onMethodExit("Fixture", "method");
                TraceRuntime.onException("Fixture", "method", "Failure", "original");
                TraceRuntime.onDbRoute("route");
                TraceRuntime.onExternalCall("system", "call");
                TraceRuntime.onBoundary("trace", Map.of("system", "queue"));
            }).doesNotThrowAnyException();
        }
        assertThat(TraceRuntime.observerFailureCount()).isEqualTo(failures + 7);
    }
}
