package com.chargebee.tracelens.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Bridge-only boundaries: no instrumentation and no sockets are opened. */
class HttpClientTraceRuntimeTest {
    private final List<Map<String, String>> records = new ArrayList<>();
    private final List<String> segments = new ArrayList<>();
    private final HttpClientTraceRuntime.Listener listener = (segment, call) -> {
        segments.add(segment);
        records.add(call);
    };

    @BeforeEach void configure() throws Exception {
        states().clear();
        HttpClientTraceRuntime.registerListener(listener);
        HttpClientTraceRuntime.configure(true, false, Collections.emptyList());
    }

    @AfterEach void reset() throws Exception {
        HttpClientTraceRuntime.unregisterListener(listener);
        HttpClientTraceRuntime.configure(false, false, Collections.emptyList());
        states().clear();
    }

    @Test void localCallIsBoundedPrivateAndCompletedOnceOutsideItsScope() throws Exception {
        HttpURLConnection connection = connection("http://private-user:private-password@127.0.0.1:8080/read;session=private-session?token=private-query#private-fragment");
        connection.setRequestProperty("Authorization", "private-authorization");
        connection.setRequestProperty("X-Fixture-Secret", "private-header");
        Object token;
        try (TraceScope scope = TraceContext.activate(localSnapshot())) {
            token = HttpClientTraceRuntime.enter(connection);
            assertThat(token).isNotNull();
            assertThat(HttpClientTraceRuntime.enter(connection)).isSameAs(token);
            HttpClientTraceRuntime.exit(token, "connect", -1, null);
            assertThat(records).hasSize(1);
            assertThat(records.get(0)).containsEntry("traceId", "local-trace-uuid")
                    .containsEntry("status", "ACTIVE").containsEntry("path", "/read")
                    .containsEntry("peer", "127.0.0.1:8080").containsEntry("propagationStatus", "DISABLED")
                    .doesNotContainKey("parentSpanId");
            assertThat(records.get(0).get("spanId")).hasSize(16);
            assertThat(records.toString()).doesNotContain("private-");
            assertThat(connection.getRequestProperty("traceparent")).isNull();
            assertThat(connection.getRequestProperty("X-Fixture-Secret")).isEqualTo("private-header");
            assertThatThrownBy(() -> records.get(0).put("status", "changed")).isInstanceOf(UnsupportedOperationException.class);
        }
        HttpClientTraceRuntime.exit(token, "getResponseCode", 503, null);
        assertThat(records).hasSize(2);
        assertThat(segments).containsExactly("local-segment", "local-segment");
        assertThat(records.get(1)).containsEntry("status", "ERROR").containsEntry("httpStatus", "503")
                .containsKeys("endedAt", "durationNanos").doesNotContainKey("errorType");
        assertThat(records.get(0)).containsEntry("status", "ACTIVE");
        assertThat(HttpClientTraceRuntime.enter(connection)).isNull();
        HttpClientTraceRuntime.exit(token, "disconnect", 503, null);
        assertThat(records).hasSize(2);
    }

    @Test void requiresActiveContextAndListenerFailuresAreFailOpen() throws Exception {
        HttpURLConnection connection = connection("http://127.0.0.1:8080/no-network");
        assertThat(HttpClientTraceRuntime.enter(connection)).isNull();
        AtomicReference<Object> recursive = new AtomicReference<>("not-called");
        HttpClientTraceRuntime.registerListener((segment, call) -> {
            recursive.set(HttpClientTraceRuntime.enter(connection));
            throw new IllegalStateException("fixture sink failure");
        });
        try (TraceScope scope = TraceContext.activate(localSnapshot())) {
            Object token = HttpClientTraceRuntime.enter(connection);
            assertThat(token).isNotNull();
            assertThat(recursive.get()).isNull();
            HttpClientTraceRuntime.exit(token, "getInputStream", -1, new java.io.IOException("private-body"));
            assertThat(HttpClientTraceRuntime.enter(connection)).isNull();
        } finally {
            HttpClientTraceRuntime.registerListener(listener);
        }
    }

    @Test void connectionLimitRetainsLiveStatesWhenConfigurationChanges() throws Exception {
        List<HttpURLConnection> connections = new ArrayList<>();
        Object first = null;
        try (TraceScope scope = TraceContext.activate(localSnapshot())) {
            for (int index = 0; index < 4096; index++) {
                HttpURLConnection connection = connection("http://127.0.0.1:8080/bounded");
                connections.add(connection); // Keep weak-map keys live for the whole assertion.
                Object token = HttpClientTraceRuntime.enter(connection);
                assertThat(token).isNotNull();
                if (index == 0) first = token;
            }
            assertThat(records).hasSize(4096);
            assertThat(HttpClientTraceRuntime.enter(connection("http://127.0.0.1:8080/overflow"))).isNull();
            assertThat(states()).hasSize(4096);
            HttpClientTraceRuntime.configure(false, false, Collections.emptyList());
            assertThat(states()).hasSize(4096);
            assertThat(HttpClientTraceRuntime.enter(connections.get(0))).isSameAs(first);
            HttpClientTraceRuntime.exit(first, "connect", -1, null);
            HttpClientTraceRuntime.exit(first, "getResponseCode", 200, null);
            assertThat(records).hasSize(4097);
            assertThat(records.get(4096)).containsEntry("status", "COMPLETED");
        }
    }

    private static TraceSnapshot localSnapshot() {
        return new TraceSnapshot("local-trace-uuid", "local-segment", "fixture", "private-user", Collections.emptyMap());
    }

    private static HttpURLConnection connection(String url) throws Exception {
        return (HttpURLConnection) new URL(url).openConnection();
    }

    private static Map<?, ?> states() throws Exception {
        java.lang.reflect.Field field = HttpClientTraceRuntime.class.getDeclaredField("CALLS");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(null);
    }
}
