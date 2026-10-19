package com.chargebee.tracelens.compat;

import com.chargebee.cbappfixture.LegacyService;
import com.chargebee.tracelens.bridge.AgentStatus;
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
        TraceLensConfig config = new TraceLensConfigLoader().load();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (TraceStore store = new TraceStore(config)) {
            TraceRecording.install(store);
            Tracer tracer = new Tracer(store);
            Trace recorded;
            try (Tracer.ActiveTrace active = tracer.begin("legacy-request", "developer", EntryChannel.HTTP,
                    Collections.singletonMap("httpMethod", "GET")).get()) {
                recorded = active.trace();
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
            require(AgentStatus.failedCount() == 0, "some classes failed instrumentation");
            require(TraceRuntime.wrappedTaskCount() > 0, "executor instrumentation did not wrap tasks");
            System.out.println("JAVA8_SMOKE_OK events=" + recorded.eventCount()
                    + " instrumented=" + AgentStatus.instrumentedCount());
        } finally {
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
