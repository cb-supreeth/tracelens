package com.chargebee.tracelens.bridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ClickHouseQueryRuntimeTest {
    private final List<Map<String, String>> events = new ArrayList<>();
    private final List<String> segments = new ArrayList<>();
    private TraceScope scope;

    @BeforeEach void setup() {
        ClickHouseQueryRuntime.configure(true, 4096, 100, true);
        TraceRuntime.registerSink(new TraceRuntime.Sink() {
            public void onLine(String s, String c, int l) { }
            public void onMethodEnter(String s, String c, String m) { }
            public void onMethodExit(String s, String c, String m) { }
            public void onException(String s, String c, String m, String t, String msg) { }
            public void onBoundary(String s, Map<String, String> fields) { segments.add(s); events.add(fields); }
        });
        scope = TraceContext.activate(new TraceSnapshot("trace", "segment", null, null, null));
    }

    @AfterEach void cleanup() {
        scope.close(); TraceContext.clear(); TraceRuntime.unregisterSink();
        ClickHouseQueryRuntime.configure(false, 4096, 100, false);
    }

    @Test void noTraceNoSinkSkippedOrDisabledDoesNotCapture() {
        TraceContext.clear(); assertThat(ClickHouseQueryRuntime.enter("secret", null)).isNull();
        try (TraceScope ignored = TraceContext.activate(new TraceSnapshot("x", "y", null, null,
                Collections.singletonMap("captureSkipped", "true")))) {
            assertThat(ClickHouseQueryRuntime.enter("secret", null)).isNull();
        }
        try (TraceScope ignored = TraceContext.activate(new TraceSnapshot("x", "y", null, null, null))) {
            TraceRuntime.unregisterSink(); assertThat(ClickHouseQueryRuntime.enter("secret", null)).isNull();
        }
        ClickHouseQueryRuntime.configure(false, 4096, 100, true);
        assertThat(ClickHouseQueryRuntime.enter("secret", new PoisonMap())).isNull();
        assertThat(events).isEmpty();
    }

    @Test void asyncResultIdentityAndOriginArePreservedWithoutBlockingOrInspectingResults() {
        CompletableFuture<Object> future = new PoisonFuture();
        Object token = ClickHouseQueryRuntime.enter("SELECT 1", null);
        ClickHouseQueryRuntime.exit(token, future, null);
        assertThat(events).isEmpty();
        TraceContext.clear();
        future.complete(new Poison());
        assertThat(segments).containsExactly("segment");
        assertThat(events.get(0)).containsEntry("completionKind", "response-complete")
                .containsEntry("status", "OBSERVED").containsEntry("clientThread", Thread.currentThread().getName());
        assertThat(Double.parseDouble(events.get(0).get("durationMs"))).isGreaterThanOrEqualTo(0);
        assertThat(TraceContext.current()).isNull();
    }

    @Test void exceptionAndCancellationRecordOnlyTypeAndDoNotChangeFuture() {
        CompletableFuture<Object> future = new CompletableFuture<>();
        Object token = ClickHouseQueryRuntime.enter("SELECT 1", null);
        ClickHouseQueryRuntime.exit(token, future, null);
        future.cancel(false);
        assertThat(events.get(0)).containsEntry("status", "ERROR")
                .containsEntry("errorType", "java.util.concurrent.CancellationException");
        RuntimeException failure = new PrivateFailure();
        ClickHouseQueryRuntime.exit(ClickHouseQueryRuntime.enter("SELECT 2", null), null, failure);
        assertThat(events.get(1)).containsEntry("status", "ERROR").containsEntry("completionKind", "immediate-error");
    }

    @Test void parametersNeedSeparateOptInAndNeverInspectQuerySettingsOrUnsupportedObjects() {
        ClickHouseQueryRuntime.configure(true, 4096, 100, false);
        observe("SELECT {secret:String}", new PoisonMap());
        assertThat(events.get(0)).doesNotContainKeys("sqlParameters", "sqlParametersStatus");
        ClickHouseQueryRuntime.configure(true, 4096, 100, true);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("item", "quote\" and \n tab\t surrogate\uD800");
        parameters.put("count", 7); parameters.put("nil", null); parameters.put("object", new Poison());
        parameters.put("stream", new java.io.ByteArrayInputStream(new byte[] {1, 2}));
        observe("SELECT {item:String}", parameters);
        String json = events.get(1).get("sqlParameters");
        assertThat(json).contains("\"name\":\"item\"", "\\\"", "\\u000a", "\\ud800", "\"status\":\"null\"", "\"status\":\"unsupported\"");
        assertThat(events.get(1)).containsEntry("sqlParametersStatus", "captured");
    }

    @Test void sqlBindingsAndQueryCountAreBoundedWithOneLimitMarkerPerSegment() {
        ClickHouseQueryRuntime.configure(true, 8, 2, true);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("long", repeat("x", 10000));
        for (int i = 0; i < 200; i++) parameters.put("key" + i, repeat("y", 512));
        observe("SELECT long_sensitive_literal", parameters);
        observe("SELECT 2", null);
        observe("SELECT 3", null); observe("SELECT 4", null);
        assertThat(events).hasSize(3);
        assertThat(events.get(0)).containsEntry("sql", "SELECT l").containsEntry("sqlTruncated", "true")
                .containsEntry("sqlParametersTruncated", "true");
        assertThat(events.get(0).get("sqlParameters").length()).isLessThanOrEqualTo(8192);
        assertThat(events.get(2)).containsEntry("evidenceKind", "clickhouse-query-limit").containsEntry("queryLimit", "2");
        assertThat(events.get(2)).doesNotContainKeys("sql", "sqlParameters");
        try (TraceScope ignored = TraceContext.activate(new TraceSnapshot("trace", "second-segment", null, null, null))) {
            observe("SELECT 5", null);
        }
        assertThat(events).hasSize(4);
    }

    @Test void nestedInvocationsAndRepeatedExitEmitOnceAndCleanState() {
        Object outer = ClickHouseQueryRuntime.enter("SELECT outer", null);
        Object inner = ClickHouseQueryRuntime.enter("SELECT inner", null);
        assertThat(inner).isSameAs(outer);
        ClickHouseQueryRuntime.exit(inner, CompletableFuture.completedFuture(null), null);
        assertThat(events).isEmpty();
        ClickHouseQueryRuntime.exit(outer, CompletableFuture.completedFuture(null), null);
        ClickHouseQueryRuntime.exit(outer, CompletableFuture.completedFuture(null), null);
        observe("SELECT next", null);
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).containsEntry("sql", "SELECT outer");
        assertThat(events.get(1)).containsEntry("sql", "SELECT next");
    }

    @Test void unsupportedOrThrowingFutureObserverRecordsSubmissionNotFalseSuccess() {
        ClickHouseQueryRuntime.exit(ClickHouseQueryRuntime.enter("SELECT 1", null), new Poison(), null);
        CompletableFuture<Object> future = new CompletableFuture<Object>() {
            @Override public CompletableFuture<Object> whenComplete(java.util.function.BiConsumer<? super Object, ? super Throwable> action) {
                throw new AssertionError("observer rejected");
            }
        };
        ClickHouseQueryRuntime.exit(ClickHouseQueryRuntime.enter("SELECT 2", null), future, null);
        assertThat(events).hasSize(2).allSatisfy(e -> assertThat(e).containsEntry("status", "SUBMITTED").doesNotContainKey("durationMs"));
        assertThat(events.get(1)).containsEntry("completionKind", "observer-unavailable");
    }

    @Test void pendingCaptureCapacityIsBoundedAndReconfigurationDiscardsOldCallbacks() {
        ClickHouseQueryRuntime.configure(true, 4096, 1000, false);
        CompletableFuture<Object> first = new CompletableFuture<>();
        ClickHouseQueryRuntime.exit(ClickHouseQueryRuntime.enter("SELECT 0", null), first, null);
        for (int i = 1; i < 1000; i++) ClickHouseQueryRuntime.exit(ClickHouseQueryRuntime.enter("SELECT 1", null), new CompletableFuture<>(), null);
        try (TraceScope ignored = TraceContext.activate(new TraceSnapshot("trace", "other", null, null, null))) {
            for (int i = 0; i < 25; i++) ClickHouseQueryRuntime.exit(ClickHouseQueryRuntime.enter("SELECT 2", null), new CompletableFuture<>(), null);
        }
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("status", "SUBMITTED").containsEntry("completionKind", "capture-capacity");
        ClickHouseQueryRuntime.configure(false, 4096, 100, false);
        first.complete(null);
        assertThat(events).hasSize(1);
    }

    @Test void maliciousParameterMapFailureIsContainedAndMarkedIncomplete() {
        observe("SELECT 1", new PoisonMap());
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("sqlParametersStatus", "unavailable")
                .containsEntry("sqlParametersTruncated", "true").containsEntry("sqlParameters", "[]");
    }

    private void observe(String sql, Map<?, ?> parameters) {
        ClickHouseQueryRuntime.exit(ClickHouseQueryRuntime.enter(sql, parameters), CompletableFuture.completedFuture(new Poison()), null);
    }
    private static String repeat(String text, int n) { StringBuilder b = new StringBuilder(); for (int i = 0; i < n; i++) b.append(text); return b.toString(); }
    private static class Poison { @Override public String toString() { throw new AssertionError("value inspected"); } }
    private static class PrivateFailure extends RuntimeException { @Override public String getMessage() { throw new AssertionError("error inspected"); } }
    private static class PoisonMap extends LinkedHashMap<String, Object> {
        @Override public java.util.Set<Map.Entry<String, Object>> entrySet() { throw new AssertionError("parameters inspected"); }
    }
    private static class PoisonFuture extends CompletableFuture<Object> {
        @Override public boolean isDone() { throw new AssertionError("future inspected"); }
        @Override public Object get() { throw new AssertionError("future blocked"); }
        @Override public Object join() { throw new AssertionError("future blocked"); }
    }
}
