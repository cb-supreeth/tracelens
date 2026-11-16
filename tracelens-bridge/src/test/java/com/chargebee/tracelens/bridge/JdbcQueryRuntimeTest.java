package com.chargebee.tracelens.bridge;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcQueryRuntimeTest {
    private static final String PG = "org.postgresql.jdbc.PgPreparedStatement";
    private static final String MYSQL = "com.mysql.cj.jdbc.StatementImpl";
    private final List<Map<String, String>> events = Collections.synchronizedList(new ArrayList<>());
    private final List<String> segments = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach void setup() {
        JdbcQueryRuntime.configure(true, 4096, 100);
        registerSink();
        TraceContext.activate(snapshot("segment"));
    }
    private void registerSink() {
        TraceRuntime.registerSink(new TraceRuntime.Sink() {
            public void onLine(String s, String c, int l) { }
            public void onMethodEnter(String s, String c, String m) { }
            public void onMethodExit(String s, String c, String m) { }
            public void onException(String s, String c, String m, String t, String e) { }
            public void onBoundary(String segment, Map<String, String> fields) { segments.add(segment); events.add(fields); }
        });
    }
    @AfterEach void cleanup() { TraceContext.clear(); TraceRuntime.unregisterSink(); JdbcQueryRuntime.configure(false, 4096, 100); }
    private TraceSnapshot snapshot(String segment) { return new TraceSnapshot("trace", segment, "lens", "user", null); }
    private void execute(Object statement, String owner, String operation, String sql) {
        JdbcQueryRuntime.exit(JdbcQueryRuntime.enter(statement, owner, operation, sql), null);
    }

    @Test void rawStatementsKeepLiteralsButNeverInspectDriverObjects() {
        execute(new Poison(), MYSQL, "executeQuery", "select * from accounts where email = 'local@example.test'");
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("sql", "select * from accounts where email = 'local@example.test'")
                .containsEntry("system", "mysql").containsEntry("sqlSource", "statement")
                .containsEntry("evidenceKind", "jdbc-execution").containsEntry("status", "OBSERVED")
                .containsEntry("boundaryKind", "database").containsEntry("direction", "outbound")
                .containsEntry("sqlTruncated", "false");
        assertThat(Double.parseDouble(events.get(0).get("durationMs"))).isGreaterThanOrEqualTo(0);
    }

    @Test void preparedOutsideTraceIsReusedAcrossRequestsWithoutToStringOrParameters() {
        TraceContext.clear();
        Object statement = new Poison();
        JdbcQueryRuntime.prepared(statement, "select * from subscription where id = ?");
        execute(statement, PG, "executeQuery", null);
        assertThat(events).isEmpty();
        try (TraceScope scope = TraceContext.activate(snapshot("one"))) { execute(statement, PG, "executeQuery", null); }
        try (TraceScope scope = TraceContext.activate(snapshot("two"))) { execute(statement, PG, "executeQuery", null); }
        assertThat(segments).containsExactly("one", "two");
        for (Map<String, String> event : events) {
            assertThat(event).containsEntry("system", "postgresql").containsEntry("sqlSource", "prepared")
                    .containsEntry("sql", "select * from subscription where id = ?");
        }
        JdbcQueryRuntime.closed(statement);
        try (TraceScope scope = TraceContext.activate(snapshot("three"))) { execute(statement, PG, "executeQuery", null); }
        assertThat(events.get(2)).containsEntry("sqlSource", "unavailable").doesNotContainKey("sql");
    }

    @Test void nestedOverloadsEmitOnceAndFailuresContainOnlyExceptionType() {
        Object statement = new Poison();
        Object outer = JdbcQueryRuntime.enter(statement, MYSQL, "execute", "select 1");
        Object inner = JdbcQueryRuntime.enter(statement, MYSQL, "executeQuery", "driver-generated bound secrets");
        SQLException failure = new SQLException("password and private database URL");
        JdbcQueryRuntime.exit(inner, failure);
        assertThat(events).isEmpty();
        JdbcQueryRuntime.exit(outer, failure);
        assertThat(events.get(0)).containsEntry("operation", "execute").containsEntry("status", "ERROR")
                .containsEntry("errorType", "java.sql.SQLException").containsEntry("sql", "select 1");
        execute(statement, MYSQL, "execute", "select 2");
        assertThat(events).hasSize(2);
        assertThat(events.toString()).doesNotContain("password", "secrets", "private database URL");
    }

    @Test void noSinkDisabledSkippedAndUnrelatedOwnersProduceNothing() {
        Object statement = new Poison();
        JdbcQueryRuntime.configure(false, 4096, 100);
        JdbcQueryRuntime.prepared(statement, "disabled SQL");
        execute(statement, PG, "execute", "disabled SQL");
        JdbcQueryRuntime.configure(true, 4096, 100);
        execute(statement, "example.Driver", "execute", "not a supported driver");
        execute(statement, PG, "getMetaData", "not a query");
        try (TraceScope scope = TraceContext.activate(new TraceSnapshot("trace", "skipped", null, null,
                Collections.singletonMap("captureSkipped", "path-filter")))) { execute(statement, PG, "execute", "skipped"); }
        TraceRuntime.unregisterSink();
        execute(statement, PG, "execute", "no sink");
        assertThat(events).isEmpty();
    }

    @Test void sqlAndQueryLimitsAreExplicitAcrossCopiedSnapshots() {
        JdbcQueryRuntime.configure(true, 8, 2);
        Object statement = new Poison();
        JdbcQueryRuntime.prepared(statement, "select confidential");
        execute(statement, PG, "execute", null);
        try (TraceScope scope = TraceContext.activate(snapshot(new String("segment")))) { execute(statement, PG, "execute", "select 2"); }
        for (int i = 0; i < 5; i++) {
            try (TraceScope scope = TraceContext.activate(snapshot(new String("segment")))) { execute(statement, PG, "execute", "excluded secret"); }
        }
        assertThat(events).hasSize(3);
        assertThat(events.get(0)).containsEntry("sql", "select c").containsEntry("sqlTruncated", "true");
        assertThat(events.get(2)).containsEntry("evidenceKind", "jdbc-query-limit")
                .containsEntry("queryLimit", "2").containsEntry("status", "TRUNCATED")
                .doesNotContainKeys("sql", "durationMs");
    }

    @Test void queryLimitIsSharedAcrossThreads() throws Exception {
        JdbcQueryRuntime.configure(true, 4096, 5);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch completed = new CountDownLatch(20);
        for (int i = 0; i < 20; i++) pool.submit(() -> {
            try (TraceScope scope = TraceContext.activate(snapshot(new String("segment")))) {
                execute(new Object(), PG, "execute", "select 1");
            } finally { completed.countDown(); }
        });
        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        assertThat(events.stream().filter(e -> "jdbc-execution".equals(e.get("evidenceKind"))).count()).isEqualTo(5);
        assertThat(events.stream().filter(e -> "jdbc-query-limit".equals(e.get("evidenceKind"))).count()).isEqualTo(1);
    }

    @Test void preparedAndPlainBatchesRecordTemplatesCountsAndClearAfterExecution() {
        Object prepared = new Poison();
        JdbcQueryRuntime.prepared(prepared, "insert into audit values (?)");
        JdbcQueryRuntime.addBatch(prepared, null); JdbcQueryRuntime.addBatch(prepared, null);
        execute(prepared, PG, "executeBatch", null);
        assertThat(events.get(0)).containsEntry("sql", "insert into audit values (?)")
                .containsEntry("batchSize", "2").containsEntry("sqlSource", "prepared");
        execute(prepared, PG, "executeBatch", null);
        assertThat(events.get(1)).containsEntry("batchSize", "0");
        Object plain = new Poison();
        JdbcQueryRuntime.addBatch(plain, "select 1"); JdbcQueryRuntime.addBatch(plain, "select 2");
        execute(plain, MYSQL, "executeLargeBatch", null);
        assertThat(events.get(2)).containsEntry("sql", "select 1;\nselect 2")
                .containsEntry("batchSize", "2").containsEntry("sqlSource", "batch");
        JdbcQueryRuntime.addBatch(plain, "cleared"); JdbcQueryRuntime.clearBatch(plain);
        execute(plain, MYSQL, "executeBatch", null);
        assertThat(events.get(3)).containsEntry("batchSize", "0").doesNotContainKey("sql");
    }

    @Test void batchRegistrationDeduplicatesWrappersAndIgnoresFailedAdds() {
        Object statement = new Poison();
        Object outer = JdbcQueryRuntime.enterBatch(statement, "select 1");
        Object inner = JdbcQueryRuntime.enterBatch(statement, "select 1");
        JdbcQueryRuntime.exitBatch(inner, null); JdbcQueryRuntime.exitBatch(outer, null);
        JdbcQueryRuntime.exitBatch(JdbcQueryRuntime.enterBatch(statement, "failed"), new SQLException());
        execute(statement, MYSQL, "executeBatch", null);
        assertThat(events.get(0)).containsEntry("batchSize", "1").containsEntry("sql", "select 1");
    }

    @Test void mixedPreparedAndPlainBatchesKeepBoundedTemplateInventoryNotExpandedParameters() {
        Object statement = new Poison();
        JdbcQueryRuntime.prepared(statement, "insert into audit values (?)");
        JdbcQueryRuntime.addBatch(statement, "delete from audit where id = 1");
        JdbcQueryRuntime.addBatch(statement, null);
        JdbcQueryRuntime.addBatch(statement, null);
        execute(statement, MYSQL, "executeBatch", null);
        assertThat(events.get(0)).containsEntry("sqlSource", "batch").containsEntry("batchSize", "3")
                .containsEntry("sql", "insert into audit values (?);\ndelete from audit where id = 1")
                .containsEntry("sqlTruncated", "false");

        // A prepared object may contain only plain entries: its unused template is not claimed.
        JdbcQueryRuntime.addBatch(statement, "delete from audit");
        execute(statement, MYSQL, "executeBatch", null);
        assertThat(events.get(1)).containsEntry("sql", "delete from audit").containsEntry("batchSize", "1")
                .containsEntry("sqlSource", "batch");

        JdbcQueryRuntime.configure(true, 20, 100);
        JdbcQueryRuntime.prepared(statement, "insert (?)");
        JdbcQueryRuntime.addBatch(statement, null);
        JdbcQueryRuntime.addBatch(statement, "delete from audit where id = 2");
        execute(statement, MYSQL, "executeLargeBatch", null);
        assertThat(events.get(2)).containsEntry("sqlSource", "batch").containsEntry("sqlTruncated", "true")
                .containsEntry("batchSize", "2");
        assertThat(events.get(2).get("sql")).hasSize(20).startsWith("insert (?);\ndelete ");
    }

    @Test void batchSqlHasOneOverallLengthLimitAndDriverGeneratedPreparationsAreIgnored() {
        JdbcQueryRuntime.configure(true, 8, 10);
        Object statement = new Poison();
        JdbcQueryRuntime.addBatch(statement, "select 1"); JdbcQueryRuntime.addBatch(statement, "select secret");
        execute(statement, MYSQL, "executeBatch", null);
        assertThat(events.get(0)).containsEntry("sql", "select 1").containsEntry("sqlTruncated", "true");
        Object token = JdbcQueryRuntime.enter(statement, PG, "execute", "select 2");
        JdbcQueryRuntime.prepared(statement, "driver reconstructed bound parameter");
        JdbcQueryRuntime.exit(token, null);
        execute(statement, PG, "executeQuery", null);
        assertThat(events.get(2)).containsEntry("sqlSource", "unavailable").doesNotContainKey("sql");
    }

    @Test void nonRecordingExecutionsStillShieldDriverRewritesOfBoundParameters() {
        for (String mode : new String[]{"untraced", "skipped", "no-sink", "exhausted"}) {
            JdbcQueryRuntime.configure(true, 4096, 1);
            events.clear();
            registerSink();
            TraceContext.activate(snapshot("original-" + mode));
            if ("untraced".equals(mode)) TraceContext.clear();
            if ("skipped".equals(mode)) TraceContext.activate(new TraceSnapshot("trace", "skipped", null, null,
                    Collections.singletonMap("captureSkipped", "path-filter")));
            if ("no-sink".equals(mode)) TraceRuntime.unregisterSink();
            if ("exhausted".equals(mode)) {
                execute(new Object(), MYSQL, "execute", "select 1");
                execute(new Object(), MYSQL, "execute", "select 2"); // emits the one limit marker
            }
            Object generated = new Poison();
            Object batch = JdbcQueryRuntime.enter(new Object(), MYSQL, "executeBatch", null);
            assertThat(batch).as(mode).isNotNull();
            JdbcQueryRuntime.prepared(generated, "driver rewritten secret bound parameter");
            Object inner = JdbcQueryRuntime.enter(generated, MYSQL, "execute", "driver rewritten secret bound parameter");
            JdbcQueryRuntime.exit(inner, null);
            JdbcQueryRuntime.exit(batch, new SQLException("private failure"));

            registerSink();
            TraceContext.activate(snapshot("next-" + mode));
            execute(generated, MYSQL, "execute", null);
            assertThat(events.get(events.size() - 1)).as(mode)
                    .containsEntry("sqlSource", "unavailable").doesNotContainKey("sql");
            assertThat(events.toString()).as(mode).doesNotContain("secret", "private failure");
            assertThat(events).as(mode).hasSize("exhausted".equals(mode) ? 3 : 1);
        }
    }

    @Test void hardLimitsAndAssociationCapacityFailClosedWithoutCallingObjectMethods() {
        JdbcQueryRuntime.configure(true, Integer.MAX_VALUE, Integer.MAX_VALUE);
        Object statement = new Poison();
        String sql = String.join("", Collections.nCopies(20000, "x"));
        execute(statement, PG, "execute", sql);
        assertThat(events.get(0).get("sql")).hasSize(16384);
        List<Object> retained = new ArrayList<>();
        for (int i = 0; i < 1025; i++) {
            Object key = new Poison(); retained.add(key); JdbcQueryRuntime.prepared(key, "select " + i);
        }
        execute(retained.get(1024), PG, "execute", null);
        assertThat(events.get(1)).containsEntry("sqlSource", "unavailable").doesNotContainKey("sql");
        JdbcQueryRuntime.closed(retained.get(0));
        JdbcQueryRuntime.prepared(retained.get(1024), "select recovered");
        execute(retained.get(1024), PG, "execute", null);
        assertThat(events.get(2)).containsEntry("sql", "select recovered");
        for (int i = 0; i < 1100; i++) execute(statement, PG, "execute", "select 1");
        assertThat(events).hasSize(1001);
        assertThat(events.get(1000)).containsEntry("evidenceKind", "jdbc-query-limit").containsEntry("queryLimit", "1000");
    }

    static class Poison {
        @Override public String toString() { throw new AssertionError("JDBC object inspected"); }
        @Override public int hashCode() { throw new AssertionError("JDBC object hashed"); }
        @Override public boolean equals(Object other) { throw new AssertionError("JDBC object compared"); }
    }

    private void bind(Object statement, String setter, int index, Object value) {
        JdbcQueryRuntime.exitBinding(JdbcQueryRuntime.enterBinding(statement, setter, index, value), null);
    }

    @Test void parameterCaptureRequiresBothQueryAndParameterOptIns() {
        Object statement = new Poison();
        for (boolean queries : new boolean[]{false, true}) {
            JdbcQueryRuntime.configure(queries, 4096, 100, !queries);
            JdbcQueryRuntime.prepared(statement, "select ?");
            bind(statement, "setObject", 1, new Poison());
            bind(statement, "setString", 1, "private binding");
            execute(statement, PG, "executeQuery", null);
        }
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).doesNotContainKeys("sqlParameters", "sqlParametersStatus", "sqlParameterSets");
        assertThat(events.toString()).doesNotContain("private binding");
    }

    @Test void successfulSettersKeepTypesIndexOrderAndSnapshotValuesWithoutChangingTemplate() {
        JdbcQueryRuntime.configure(true, 4096, 100, true);
        Object statement = new Poison();
        JdbcQueryRuntime.prepared(statement, "select ?, ?, ?, ?");
        bind(statement, "setString", 2, "O'Reilly\n\"quoted\"\\path");
        bind(statement, "setInt", 1, 42);
        bind(statement, "setNull", 3, java.sql.Types.VARCHAR);
        bind(statement, "setObject", 4, new java.math.BigDecimal("12.50"));
        Object execution = JdbcQueryRuntime.enter(statement, PG, "executeQuery", null);
        bind(statement, "setString", 2, "internal rewritten value");
        JdbcQueryRuntime.exit(execution, null);
        assertThat(events.get(0)).containsEntry("sql", "select ?, ?, ?, ?")
                .containsEntry("sqlParametersStatus", "captured").containsEntry("sqlParametersTruncated", "false");
        assertThat(events.get(0).get("sqlParameters")).isEqualTo("[{\"index\":1,\"type\":\"Int\",\"value\":\"42\",\"status\":\"captured\"},"
                + "{\"index\":2,\"type\":\"String\",\"value\":\"O'Reilly\\u000a\\\"quoted\\\"\\\\path\",\"status\":\"captured\"},"
                + "{\"index\":3,\"type\":\"Null\",\"value\":null,\"status\":\"null\"},"
                + "{\"index\":4,\"type\":\"Object\",\"value\":\"12.50\",\"status\":\"captured\"}]");
        bind(statement, "setInt", 1, 43);
        execute(statement, PG, "executeQuery", null);
        assertThat(events.get(0).get("sqlParameters")).contains("\"42\"").doesNotContain("\"43\"");
        assertThat(events.get(1).get("sqlParameters")).contains("\"43\"").doesNotContain("internal rewritten value");
    }

    @Test void nestedSettersDeduplicateAndFailuresDoNotReplacePreviousBindings() {
        JdbcQueryRuntime.configure(true, 4096, 100, true);
        Object statement = new Poison();
        JdbcQueryRuntime.prepared(statement, "select ?");
        Object outer = JdbcQueryRuntime.enterBinding(statement, "setString", 1, "outer value");
        Object inner = JdbcQueryRuntime.enterBinding(statement, "setObject", 1, "internal value");
        JdbcQueryRuntime.exitBinding(inner, null); JdbcQueryRuntime.exitBinding(outer, null);
        JdbcQueryRuntime.exitBinding(JdbcQueryRuntime.enterBinding(statement, "setInt", 1, 9), new SQLException());
        execute(statement, PG, "execute", null);
        assertThat(events.get(0).get("sqlParameters")).contains("\"String\"", "outer value").doesNotContain("internal", "\"9\"");
    }

    @Test void clearParametersAndClosePreventStaleBindingsButReusePreservesCurrentBindings() {
        JdbcQueryRuntime.configure(true, 4096, 100, true);
        Object statement = new Poison();
        JdbcQueryRuntime.prepared(statement, "select ?");
        bind(statement, "setLong", 1, 15L);
        execute(statement, PG, "execute", null);
        execute(statement, PG, "execute", null);
        assertThat(events.get(1).get("sqlParameters")).isEqualTo(events.get(0).get("sqlParameters"));
        JdbcQueryRuntime.clearParameters(statement);
        execute(statement, PG, "execute", null);
        assertThat(events.get(2)).containsEntry("sqlParameters", "[]");
        bind(statement, "setLong", 1, 20L);
        JdbcQueryRuntime.closed(statement);
        execute(statement, PG, "execute", null);
        assertThat(events.get(3)).containsEntry("sqlParametersStatus", "unavailable").doesNotContainKey("sqlParameters");
    }

    @Test void unsupportedObjectsStreamsBinaryAndDateSubclassesAreNeverInspected() {
        JdbcQueryRuntime.configure(true, 4096, 100, true);
        Object statement = new Poison();
        JdbcQueryRuntime.prepared(statement, "select ?, ?, ?, ?, ?, ?, ?");
        bind(statement, "setObject", 1, new Poison());
        bind(statement, "setBinaryStream", 2, new java.io.InputStream() {
            public int read() { throw new AssertionError("stream consumed"); }
            public String toString() { throw new AssertionError("stream inspected"); }
        });
        bind(statement, "setBytes", 3, new byte[]{1, 2, 3});
        bind(statement, "setDate", 4, new java.sql.Date(0) {
            public String toString() { throw new AssertionError("subclass inspected"); }
        });
        bind(statement, "setDate", 5, java.sql.Date.valueOf("2026-10-22"));
        bind(statement, "setBoolean", 6, true);
        bind(statement, "setString", 7, null);
        execute(statement, PG, "execute", null);
        String json = events.get(0).get("sqlParameters");
        assertThat(json).contains("\"type\":\"Object\",\"value\":null,\"status\":\"unsupported\"",
                "\"type\":\"BinaryStream\",\"value\":null,\"status\":\"unsupported\"",
                "\"type\":\"Bytes\",\"value\":null,\"status\":\"unsupported\"", "2026-10-22", "\"true\"",
                "\"type\":\"String\",\"value\":null,\"status\":\"null\"");
    }

    @Test void bindingLimitsAreExplicitAndHugeNumericFormattingIsNotAttempted() {
        JdbcQueryRuntime.configure(true, 4096, 100, true);
        Object statement = new Poison();
        JdbcQueryRuntime.prepared(statement, "select ?");
        bind(statement, "setString", 1, String.join("", Collections.nCopies(800, "x")));
        bind(statement, "setBigDecimal", 2, new java.math.BigDecimal(java.math.BigInteger.TEN.pow(2000)));
        bind(statement, "setObject", 3, java.math.BigInteger.TEN.pow(2000));
        bind(statement, "setString", 129, "excluded index");
        execute(statement, PG, "execute", null);
        assertThat(events.get(0)).containsEntry("sqlParametersTruncated", "true");
        assertThat(events.get(0).get("sqlParameters")).contains("\"status\":\"truncated\"", "numeric value exceeds capture limit")
                .doesNotContain("excluded index").hasSizeLessThanOrEqualTo(8192);
        JdbcQueryRuntime.clearParameters(statement);
        for (int i = 1; i <= 128; i++) bind(statement, "setString", i, String.join("", Collections.nCopies(512, "\u0001")));
        execute(statement, PG, "execute", null);
        assertThat(events.get(1)).containsEntry("sqlParametersTruncated", "true");
        assertThat(events.get(1).get("sqlParameters")).hasSizeLessThanOrEqualTo(8192).endsWith("]");
    }

    @Test void preparedBatchCapturesSuccessfulAddsRatherThanCurrentBindingsAndClearsAfterExecution() {
        JdbcQueryRuntime.configure(true, 4096, 100, true);
        Object statement = new Poison();
        JdbcQueryRuntime.prepared(statement, "insert into test values (?)");
        bind(statement, "setString", 1, "first");
        JdbcQueryRuntime.exitBatch(JdbcQueryRuntime.enterBatch(statement, null), null);
        bind(statement, "setString", 1, "failed add");
        JdbcQueryRuntime.exitBatch(JdbcQueryRuntime.enterBatch(statement, null), new SQLException());
        bind(statement, "setString", 1, "second");
        Object outer = JdbcQueryRuntime.enterBatch(statement, null);
        Object inner = JdbcQueryRuntime.enterBatch(statement, null);
        bind(statement, "setString", 1, "internal addBatch rewrite");
        JdbcQueryRuntime.exitBatch(inner, null); JdbcQueryRuntime.exitBatch(outer, null);
        bind(statement, "setString", 1, "not queued");
        Object execution = JdbcQueryRuntime.enter(statement, PG, "executeBatch", null);
        JdbcQueryRuntime.clearBatch(statement);
        JdbcQueryRuntime.exit(execution, null);
        assertThat(events.get(0)).containsEntry("sqlParametersStatus", "batch").containsEntry("batchSize", "2")
                .doesNotContainKey("sqlParameters");
        assertThat(events.get(0).get("sqlParameterSets")).contains("first", "second").doesNotContain("failed add", "not queued", "rewrite");
        execute(statement, PG, "executeBatch", null);
        assertThat(events.get(1)).containsEntry("batchSize", "0").doesNotContainKeys("sqlParameters", "sqlParameterSets");
        execute(statement, PG, "execute", null);
        assertThat(events.get(2).get("sqlParameters")).contains("not queued");
    }

    @Test void batchLimitsRetainOnlyAPrefixAndMarkOmittedSetsAndMixedBatchUnavailable() {
        JdbcQueryRuntime.configure(true, 4096, 100, true);
        Object statement = new Poison();
        JdbcQueryRuntime.prepared(statement, "insert into test values (?)");
        for (int i = 1; i <= 12; i++) {
            bind(statement, "setInt", 1, i); JdbcQueryRuntime.addBatch(statement, null);
        }
        execute(statement, PG, "executeBatch", null);
        assertThat(events.get(0)).containsEntry("sqlParametersStatus", "batch").containsEntry("sqlParameterSetsOmitted", "2")
                .containsEntry("sqlParametersTruncated", "true").containsEntry("batchSize", "12");
        assertThat(events.get(0).get("sqlParameterSets")).contains("\"value\":\"10\"").doesNotContain("\"value\":\"11\"");
        JdbcQueryRuntime.addBatch(statement, null); JdbcQueryRuntime.addBatch(statement, "insert into test values (9)");
        execute(statement, PG, "executeBatch", null);
        assertThat(events.get(1)).containsEntry("sqlParametersStatus", "unavailable").doesNotContainKeys("sqlParameterSets", "sqlParameters");
    }

    @Test void batchSerializedLengthIsBoundedAndLaterSmallSetsCannotFillAnOmittedGap() {
        JdbcQueryRuntime.configure(true, 4096, 100, true);
        Object statement = new Poison();
        JdbcQueryRuntime.prepared(statement, "insert into test values (?,?)");
        String escaped = String.join("", Collections.nCopies(512, "\u0001"));
        bind(statement, "setString", 1, escaped); bind(statement, "setString", 2, escaped);
        JdbcQueryRuntime.addBatch(statement, null); JdbcQueryRuntime.addBatch(statement, null);
        JdbcQueryRuntime.clearParameters(statement); bind(statement, "setInt", 1, 123);
        JdbcQueryRuntime.addBatch(statement, null);
        execute(statement, PG, "executeBatch", null);
        assertThat(events.get(0)).containsEntry("sqlParameterSetsOmitted", "2").containsEntry("sqlParametersTruncated", "true");
        assertThat(events.get(0).get("sqlParameterSets")).hasSizeLessThanOrEqualTo(8192).doesNotContain("123");
    }

    @Test void untracedSkippedAndLimitedExecutionsDoNotEmitBindingEvidenceOrCaptureDriverRewrites() {
        JdbcQueryRuntime.configure(true, 4096, 1, true);
        Object statement = new Poison();
        JdbcQueryRuntime.prepared(statement, "select ?");
        bind(statement, "setString", 1, "application binding");
        for (String mode : new String[]{"untraced", "skipped", "limited"}) {
            if ("limited".equals(mode)) {
                TraceContext.activate(snapshot("segment")); execute(statement, PG, "execute", null);
            } else if ("untraced".equals(mode)) TraceContext.clear();
            else TraceContext.activate(new TraceSnapshot("trace", "skip", null, null, Collections.singletonMap("captureSkipped", "true")));
            Object execution = JdbcQueryRuntime.enter(statement, PG, "execute", null);
            bind(statement, "setString", 1, "internal rewrite");
            JdbcQueryRuntime.clearParameters(statement);
            JdbcQueryRuntime.exit(execution, null);
        }
        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("sqlParameters")).contains("application binding").doesNotContain("rewrite");
        assertThat(events.get(1)).containsEntry("evidenceKind", "jdbc-query-limit").doesNotContainKey("sqlParameters");
    }

    @Test void lateStatementCaptureIsExplicitlyPartialAndDirectStatementsHaveNoBindings() {
        JdbcQueryRuntime.configure(true, 4096, 100, true);
        Object statement = new Poison();
        bind(statement, "setString", 2, "observed binding");
        execute(statement, PG, "execute", null);
        assertThat(events.get(0)).containsEntry("sqlParametersStatus", "unavailable");
        assertThat(events.get(0).get("sqlParameters")).contains("observed binding");
        execute(statement, PG, "execute", "select 'literal'");
        assertThat(events.get(1)).containsEntry("sqlParametersStatus", "not-applicable").doesNotContainKey("sqlParameters");
    }
}
