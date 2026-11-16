package com.chargebee.tracelens.compat;

import com.chargebee.cbappfixture.LegacyService;
import com.chargebee.app.models.SchJob;
import com.chargebee.app.scheduler.internal.AppJobExecutor;
import com.chargebee.tracelens.bridge.AgentStatus;
import com.chargebee.tracelens.bridge.JdbcQueryRuntime;
import com.chargebee.tracelens.bridge.ClickHouseQueryRuntime;
import com.chargebee.tracelens.bridge.TraceContext;
import com.chargebee.tracelens.bridge.TraceRuntime;
import com.chargebee.tracelens.core.config.TraceLensConfig;
import com.chargebee.tracelens.core.config.TraceLensConfigLoader;
import com.chargebee.tracelens.core.event.EventType;
import com.chargebee.tracelens.core.export.TraceExporter;
import com.chargebee.tracelens.core.record.TraceLifecycle;
import com.chargebee.tracelens.core.record.TraceRecording;
import com.chargebee.tracelens.core.record.Tracer;
import com.chargebee.tracelens.core.store.TraceStore;
import com.chargebee.tracelens.core.trace.EntryChannel;
import com.chargebee.tracelens.core.trace.Trace;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Integration assertions without JUnit: runs in the supported application VM. */
public final class Java8AgentSmoke {
    public static void main(String[] args) throws Exception {
        require("1.8".equals(System.getProperty("java.specification.version")), "must run on Java 8");
        require(AgentStatus.agentActive(), "shaded agent premain did not run");
        require(TraceRuntime.class.getClassLoader() == null, "bridge must come from bootstrap");
        require(JdbcQueryRuntime.class.getClassLoader() == null, "JDBC runtime must share the bootstrap bridge");
        require(ClickHouseQueryRuntime.class.getClassLoader() == null, "native ClickHouse runtime must share the bootstrap bridge");
        TraceLensConfig config = new TraceLensConfigLoader().load();
        require(config.isCaptureJdbcQueries(), "JDBC opt-in not loaded");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (TraceStore store = new TraceStore(config)) {
            TraceRecording.install(store);
            Tracer tracer = new Tracer(store);
            Trace recorded;
            try (Tracer.ActiveTrace active = tracer.begin("legacy-request", "developer", EntryChannel.HTTP,
                    Collections.singletonMap("httpMethod", "GET")).get()) {
                recorded = active.trace();
                // Exercise the packaged Java 8 bridge/store path without requiring a database here.
                // Real driver executions are covered separately by JdbcDatabaseIntegrationTest.
                Object jdbc = JdbcQueryRuntime.enter(new Object(), "org.postgresql.jdbc.PgStatement",
                        "executeQuery", "SELECT 'local-smoke-literal'");
                JdbcQueryRuntime.exit(jdbc, null);
                ClickHouseQueryRuntime.configure(true, 4096, 100, true);
                Object nativeQuery = ClickHouseQueryRuntime.enter("SELECT {probe:UInt8}", Collections.singletonMap("probe", 1));
                ClickHouseQueryRuntime.exit(nativeQuery, java.util.concurrent.CompletableFuture.completedFuture(null), null);
                ClickHouseQueryRuntime.configure(false, 4096, 100, false);
                require(LegacyService.calculate() == 12, "woven code changed the result");
                require(executor.submit(() -> {
                    require(TraceContext.current() != null, "executor lost trace context");
                    require(active.traceId().equals(TraceContext.current().getTraceId()), "wrong async trace");
                    return LegacyService.calculate();
                }).get(10, TimeUnit.SECONDS) == 12, "async result changed");
                try {
                    LegacyService.fail();
                    throw new AssertionError("expected application exception");
                } catch (IllegalStateException expected) {
                    // The application sees the original exception after advice records it.
                }
            }
            require(TraceContext.current() == null, "root context leaked");
            require(executor.submit(() -> TraceContext.current() == null).get(10, TimeUnit.SECONDS),
                    "worker context leaked into unrelated work");
            require(recorded.events().stream().anyMatch(event -> event.getType() == EventType.LINE),
                    "no line events");
            require(recorded.events().stream().anyMatch(event -> event.getType() == EventType.EXTERNAL_CALL
                    && "SELECT 'local-smoke-literal'".equals(event.getAttributes().get("sql"))),
                    "packaged JDBC runtime did not retain SQL in the trace store");
            require(recorded.events().stream().anyMatch(event -> "clickhouse-query".equals(event.getAttributes().get("evidenceKind"))
                    && "SELECT {probe:UInt8}".equals(event.getAttributes().get("sql"))
                    && event.getAttributes().get("sqlParameters").contains("probe")),
                    "packaged native ClickHouse runtime did not retain SQL and named bindings");
            require(recorded.events().stream().anyMatch(event -> event.getType() == EventType.EXCEPTION),
                    "no exception events");
            require(recorded.events().stream().anyMatch(event -> event.getMethodName() != null
                    && event.getMethodName().startsWith("lambda$")), "Java 8 lambda body was not woven");
            require(recorded.events().stream().anyMatch(event -> event.getThread().startsWith("pool-")),
                    "no async events in original trace");
            String export = new TraceExporter(config).export(recorded);
            require(new ObjectMapper().readTree(export).path("eventCount").asInt() == recorded.eventCount(),
                    "export did not preserve events");
            TraceLifecycle lifecycle = new TraceLifecycle(tracer);
            String continued = lifecycle.begin("upstream-trace", "legacy-message", "developer", "SQS", null);
            require(continued != null, "null-attribute continuation rejected");
            lifecycle.end(continued);
            TraceRuntime.registerLifecycleListener(lifecycle);
            AppJobExecutor jobs = new AppJobExecutor();
            jobs.exec(new SchJob("compute_billing_metrics", "success"), null);
            Trace jobTrace = store.get(jobs.observedSegment).get();
            require(jobTrace.getEntryChannel() == EntryChannel.SCHEDULER, "custom job channel missing");
            require("SUCCEEDED".equals(jobTrace.getAttributes().get("jobOutcome")), "job audit not observed");
            require(jobTrace.events().stream().anyMatch(event -> event.getType() == EventType.METHOD_ENTER
                    && "execute".equals(event.getMethodName())), "custom job method was not woven");
            jobs.exec(new SchJob("compute_billing_metrics", "ignored"), null);
            require("SKIPPED".equals(store.get(jobs.observedSegment).get().getAttributes().get("jobOutcome")),
                    "ignored custom job incorrectly classified");
            jobs.exec(new SchJob("compute_billing_metrics", "failed"), null);
            require("FAILED".equals(store.get(jobs.observedSegment).get().getAttributes().get("jobOutcome")),
                    "swallowed custom job failure not captured");
            jobs.exec(new SchJob("other_job", "success"), null);
            require(jobs.observedSegment == null, "unselected custom job was captured");
            require(TraceContext.current() == null, "custom job context leaked");
            require(AgentStatus.failedCount() == 0, "some classes failed instrumentation");
            require(com.chargebee.tracelens.bridge.ExecutorTraceRuntime.propagatedTaskCount() > 0,
                    "executor instrumentation did not propagate task context");
            System.out.println("JAVA8_SMOKE_OK events=" + recorded.eventCount()
                    + " instrumented=" + AgentStatus.instrumentedCount());
        } finally {
            TraceRuntime.unregisterLifecycleListener();
            TraceRecording.uninstall();
            executor.shutdownNow();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
