package com.chargebee.tracelens.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataStoreBoundaryRuntimeTest {
    private static final String CLICKHOUSE = "com.clickhouse.client.api.Client";
    private static final String VALUE = "org.springframework.data.redis.core.DefaultValueOperations";
    private static final String HASH = "org.springframework.data.redis.core.DefaultHashOperations";
    private static final String SET = "org.springframework.data.redis.core.DefaultSetOperations";
    private static final String TEMPLATE = "org.springframework.data.redis.core.RedisTemplate";
    private static final String DYNAMO = "com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient";
    private static final String S3 = "com.amazonaws.services.s3.AmazonS3Client";

    private final List<Map<String, String>> events = new ArrayList<>();
    private final List<String> segments = new ArrayList<>();
    private TraceScope scope;

    @BeforeEach
    void setup() {
        DataStoreBoundaryRuntime.configure(true);
        registerRecordingSink();
        scope = TraceContext.activate(new TraceSnapshot("trace", "entry-segment", "lens", "user", null));
    }

    @AfterEach
    void cleanup() {
        scope.close();
        TraceContext.clear();
        TraceRuntime.unregisterSink();
        DataStoreBoundaryRuntime.configure(false);
    }

    @Test
    void routesToEntrySegmentWithoutChangingCurrentContext() {
        TraceSnapshot entry = TraceContext.current();
        Object token = DataStoreBoundaryRuntime.enter(CLICKHOUSE, "queryRecords", true);
        assertThat(token).isNotNull();
        assertThat(TraceContext.current()).isSameAs(entry);
        try (TraceScope ignored = TraceContext.activate(
                new TraceSnapshot("other-trace", "other-segment", "other-lens", "other-user", null))) {
            TraceSnapshot atExit = TraceContext.current();
            DataStoreBoundaryRuntime.exit(token, null);
            assertThat(TraceContext.current()).isSameAs(atExit);
        }
        assertThat(TraceContext.current()).isSameAs(entry);
        assertThat(segments).containsExactly("entry-segment");
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("system", "clickhouse")
                .containsEntry("operation", "queryRecords").containsEntry("status", "SUBMITTED");
    }

    @Test
    void noTraceNoSinkDisabledAndUnknownBoundariesAreNoOps() {
        TraceContext.clear();
        assertThat(DataStoreBoundaryRuntime.enter(CLICKHOUSE, "query", true)).isNull();
        try (TraceScope ignored = TraceContext.activate(
                new TraceSnapshot("trace", "segment", "lens", "user", null))) {
            TraceRuntime.unregisterSink();
            assertThat(DataStoreBoundaryRuntime.enter(VALUE, "get", false)).isNull();
            registerRecordingSink();
            DataStoreBoundaryRuntime.configure(false);
            assertThat(DataStoreBoundaryRuntime.enter(VALUE, "get", false)).isNull();
            DataStoreBoundaryRuntime.configure(true);
            assertThat(DataStoreBoundaryRuntime.enter("private-unknown-owner", "get", false)).isNull();
            assertThat(DataStoreBoundaryRuntime.enter(CLICKHOUSE, "private-unknown-operation", false)).isNull();
            assertThat(DataStoreBoundaryRuntime.enter(null, "get", false)).isNull();
            assertThat(DataStoreBoundaryRuntime.enter(VALUE, null, false)).isNull();
            assertThat(DataStoreBoundaryRuntime.enter(S3, "generatePresignedUrl", false)).isNull();
            assertThat(DataStoreBoundaryRuntime.enter(CLICKHOUSE, "register", false)).isNull();
        }
        assertThatCode(() -> {
            DataStoreBoundaryRuntime.exit(null, new AssertionError("private"));
            DataStoreBoundaryRuntime.exit(new Object(), new AssertionError("private"));
        }).doesNotThrowAnyException();
        assertThat(events).isEmpty();
    }

    @Test
    void synchronousReturnIsOnlyObservedAndAsyncReturnIsOnlySubmitted() {
        observe(VALUE, "get", false, null);
        observe(CLICKHOUSE, "query", true, null);
        observe(DYNAMO, "putItem", false, null);
        observe(S3, "putObject", false, null);

        assertThat(events).hasSize(4);
        assertThat(events.get(0)).containsEntry("system", "redis").containsEntry("boundaryKind", "cache")
                .containsEntry("status", "OBSERVED");
        assertThat(events.get(1)).containsEntry("system", "clickhouse").containsEntry("boundaryKind", "database")
                .containsEntry("status", "SUBMITTED");
        assertThat(events.get(2)).containsEntry("system", "dynamodb").containsEntry("boundaryKind", "database")
                .containsEntry("status", "OBSERVED");
        assertThat(events.get(3)).containsEntry("system", "s3").containsEntry("boundaryKind", "external")
                .containsEntry("status", "OBSERVED");
        assertThat(events.toString()).doesNotContain("SUCCESS", "COMPLETED", "HIT", "MISS", "duration");
    }

    @Test
    void errorsRecordClassOnlyAndNeverReadThrowableText() {
        observe(VALUE, "get", false, new PrivateFailure());
        observe(CLICKHOUSE, "query", true, new IllegalStateException("private-query-and-credentials"));

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).containsEntry("status", "ERROR")
                .containsEntry("errorType", PrivateFailure.class.getName());
        assertThat(events.get(1)).containsEntry("status", "ERROR")
                .containsEntry("errorType", "java.lang.IllegalStateException");
        assertThat(events.toString()).doesNotContain("private-query-and-credentials", "private-payload");
    }

    @Test
    void sameSystemDelegationProducesOnlyOuterBoundaryAcrossClientTypes() {
        Object outer = DataStoreBoundaryRuntime.enter(VALUE, "get", false);
        Object template = DataStoreBoundaryRuntime.enter(TEMPLATE, "execute", false);
        Object nestedHash = DataStoreBoundaryRuntime.enter(HASH, "get", false);
        try {
            assertThat(template).isSameAs(outer);
            assertThat(nestedHash).isSameAs(outer);
        } finally {
            DataStoreBoundaryRuntime.exit(nestedHash, null);
            DataStoreBoundaryRuntime.exit(template, null);
            DataStoreBoundaryRuntime.exit(outer, null);
        }

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("system", "redis").containsEntry("operation", "get");
    }

    @Test
    void independentSystemsAreNotSuppressedByNesting() {
        Object clickhouse = DataStoreBoundaryRuntime.enter(CLICKHOUSE, "execute", true);
        Object redis = DataStoreBoundaryRuntime.enter(SET, "members", false);
        Object dynamo = DataStoreBoundaryRuntime.enter(DYNAMO, "query", false);
        Object s3 = DataStoreBoundaryRuntime.enter(S3, "getObject", false);
        DataStoreBoundaryRuntime.exit(s3, null);
        DataStoreBoundaryRuntime.exit(dynamo, null);
        DataStoreBoundaryRuntime.exit(redis, null);
        assertThat(events).hasSize(3);
        DataStoreBoundaryRuntime.exit(clickhouse, null);

        assertThat(events).extracting(fields -> fields.get("system"))
                .containsExactly("s3", "dynamodb", "redis", "clickhouse");
        assertThat(events.get(3)).containsEntry("operation", "execute").containsEntry("status", "SUBMITTED");
    }

    @Test
    void exceptionUnwindingAndDuplicateFinishedExitDoNotPoisonNextOperation() {
        Object outer = DataStoreBoundaryRuntime.enter(CLICKHOUSE, "queryRecords", true);
        Object inner = DataStoreBoundaryRuntime.enter(CLICKHOUSE, "query", true);
        IllegalArgumentException failure = new IllegalArgumentException("private-query");
        DataStoreBoundaryRuntime.exit(inner, failure);
        assertThat(events).isEmpty();
        DataStoreBoundaryRuntime.exit(outer, failure);
        DataStoreBoundaryRuntime.exit(outer, failure);
        observe(CLICKHOUSE, "getTableSchema", false, null);

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).containsEntry("operation", "queryRecords").containsEntry("status", "ERROR");
        assertThat(events.get(1)).containsEntry("operation", "getTableSchema").containsEntry("status", "OBSERVED");
    }

    @Test
    void depthLimitDropsExcessNestingAndRecoversAfterBalancedExit() {
        List<Object> tokens = new ArrayList<>();
        for (int depth = 0; depth < 140; depth++) {
            tokens.add(DataStoreBoundaryRuntime.enter(CLICKHOUSE, "query", true));
        }
        try {
            assertThat(tokens.subList(0, 128)).doesNotContainNull();
            assertThat(tokens.subList(128, 140)).containsOnlyNulls();
        } finally {
            for (int depth = tokens.size() - 1; depth >= 0; depth--) {
                DataStoreBoundaryRuntime.exit(tokens.get(depth), null);
            }
        }
        observe(CLICKHOUSE, "insert", true, null);

        assertThat(events).hasSize(2);
        assertThat(events).extracting(fields -> fields.get("operation")).containsExactly("query", "insert");
    }

    @Test
    void sinkFailuresAndReentrantObservationFailOpenWithoutLeakingGuard() {
        AtomicInteger callbacks = new AtomicInteger();
        List<Object> reentrantTokens = new ArrayList<>();
        TraceRuntime.registerSink(new NoOpSink() {
            @Override
            public void onBoundary(String segment, Map<String, String> fields) {
                callbacks.incrementAndGet();
                Object reentrant = DataStoreBoundaryRuntime.enter(S3, "putObject", false);
                reentrantTokens.add(reentrant);
                DataStoreBoundaryRuntime.exit(reentrant, null);
                throw new AssertionError("private-sink-failure");
            }
        });

        assertThatCode(() -> {
            observe(VALUE, "get", false, null);
            observe(CLICKHOUSE, "query", true, null);
        }).doesNotThrowAnyException();
        assertThat(callbacks).hasValue(2);
        assertThat(reentrantTokens).containsExactly(null, null);
        registerRecordingSink();
        observe(S3, "putObject", false, null);
        observe(VALUE, "get", false, null);
        assertThat(events).hasSize(2);
    }

    @Test
    void eventsHaveAnExactPayloadFreeFieldAllowlist() {
        Map<String, String> operations = new LinkedHashMap<>();
        operations.put(CLICKHOUSE, "queryRecords");
        operations.put(VALUE, "get");
        operations.put(HASH, "entries");
        operations.put(SET, "members");
        operations.put(TEMPLATE, "execute");
        operations.put(DYNAMO, "query");
        operations.put(S3, "putObject");
        for (Map.Entry<String, String> client : operations.entrySet()) {
            observe(client.getKey(), client.getValue(), false, null);
            observe(client.getKey(), client.getValue(), false, new PrivateFailure());
        }
        assertThat(events).hasSize(14);
        for (int i = 0; i < events.size(); i++) {
            Map<String, String> fields = events.get(i);
            List<String> keys = new ArrayList<>(Arrays.asList(
                    "boundaryKind", "system", "direction", "operation", "evidenceKind", "status"));
            if (i % 2 == 1) keys.add("errorType");
            assertThat(fields.keySet()).containsExactlyInAnyOrderElementsOf(keys);
            assertThat(fields).containsEntry("direction", "outbound").containsEntry("evidenceKind", "client-boundary");
            assertThatThrownBy(() -> fields.put("private-payload", "private-value"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void registryIsImmutableAndExcludesNonBoundaryHelpers() {
        Set<String> types = DataStoreBoundaryRuntime.supportedTypes();
        assertThat(types).containsExactly(CLICKHOUSE, VALUE, HASH, SET, TEMPLATE, DYNAMO, S3);
        assertThatThrownBy(() -> types.add("private-owner")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> types.remove(CLICKHOUSE)).isInstanceOf(UnsupportedOperationException.class);
        for (String owner : types) {
            Set<String> operations = DataStoreBoundaryRuntime.supportedOperations(owner);
            assertThat(operations).isNotEmpty();
            assertThatThrownBy(() -> operations.add("private-operation"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
        assertThat(DataStoreBoundaryRuntime.supportedOperations("unknown")).isEmpty();
        assertThat(DataStoreBoundaryRuntime.supportedOperations(null)).isEmpty();
        assertThat(DataStoreBoundaryRuntime.supportedOperations(S3)).doesNotContain("generatePresignedUrl");
        assertThat(DataStoreBoundaryRuntime.supportedOperations(CLICKHOUSE)).doesNotContain("register", "close");
        assertThat(DataStoreBoundaryRuntime.supportedOperations(TEMPLATE)).doesNotContain("opsForValue", "getConnectionFactory");
    }

    private void observe(String owner, String operation, boolean asynchronous, Throwable failure) {
        DataStoreBoundaryRuntime.exit(DataStoreBoundaryRuntime.enter(owner, operation, asynchronous), failure);
    }

    private void registerRecordingSink() {
        TraceRuntime.registerSink(new NoOpSink() {
            @Override
            public void onBoundary(String segment, Map<String, String> fields) {
                segments.add(segment);
                events.add(fields);
            }
        });
    }

    private static class NoOpSink implements TraceRuntime.Sink {
        @Override public void onLine(String segment, String owner, int line) { }
        @Override public void onMethodEnter(String segment, String owner, String method) { }
        @Override public void onMethodExit(String segment, String owner, String method) { }
        @Override public void onException(String segment, String owner, String method, String type, String message) { }
    }

    private static class PrivateFailure extends RuntimeException {
        PrivateFailure() { super("private-payload"); }
        @Override public String getMessage() { throw new AssertionError("exception message must not be read"); }
        @Override public String toString() { throw new AssertionError("exception must not be stringified"); }
    }
}
