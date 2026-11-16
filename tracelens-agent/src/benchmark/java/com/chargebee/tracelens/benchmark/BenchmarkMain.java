package com.chargebee.tracelens.benchmark;

import com.chargebee.tracelens.bridge.AgentStatus;
import com.chargebee.tracelens.bridge.TraceRuntime;
import com.chargebee.tracelens.core.config.TraceLensConfig;
import com.chargebee.tracelens.core.config.TraceLensConfigLoader;
import com.chargebee.tracelens.core.record.TraceRecording;
import com.chargebee.tracelens.core.record.Tracer;
import com.chargebee.tracelens.core.store.TraceStore;
import com.chargebee.tracelens.core.trace.EntryChannel;
import com.example.tracelensbenchmark.SyntheticWorkload;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Repeatable smoke/performance fixture, deliberately separate from correctness gates. */
public final class BenchmarkMain {
    private static volatile long consumed;
    public static void main(String[] args) throws Exception {
        String mode = args[0]; int iterations = Integer.parseInt(args[1]);
        if (iterations < 100 || iterations > 1000000) throw new IllegalArgumentException("Use 100–1000000 iterations");
        if (AgentStatus.agentActive() == "off".equals(mode)) throw new IllegalStateException("Unexpected agent state");
        TraceLensConfig config = new TraceLensConfigLoader().load();
        try (TraceStore store = new TraceStore(config)) {
            TraceRecording.install(store); Tracer tracer = new Tracer(store);
            for (int i = 0; i < Math.min(2000, Math.max(300, iterations / 5)); i++) run(tracer, store, i);
            long[] elapsed = new long[iterations]; long retainedEvents = 0, omitted = 0;
            long allocated = allocated(), gcCount = gc(false), gcMillis = gc(true), started = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                long begin = System.nanoTime();
                long[] counts = run(tracer, store, i);
                elapsed[i] = System.nanoTime() - begin;
                retainedEvents += counts[0]; omitted += counts[1];
            }
            long nanos = System.nanoTime() - started, allocatedAfter = allocated();
            gcCount = gc(false) - gcCount; gcMillis = gc(true) - gcMillis;
            Arrays.sort(elapsed); Map<String, Object> report = new LinkedHashMap<>();
            report.put("mode", mode); report.put("javaVersion", System.getProperty("java.version"));
            report.put("iterations", iterations); report.put("requestsPerSecond", iterations * 1e9 / nanos);
            report.put("p50Micros", elapsed[(iterations - 1) / 2] / 1000d);
            report.put("p95Micros", elapsed[(int) Math.ceil(iterations * .95) - 1] / 1000d);
            report.put("p99Micros", elapsed[(int) Math.ceil(iterations * .99) - 1] / 1000d);
            report.put("allocatedBytesPerRequest", allocated >= 0 && allocatedAfter >= allocated ? (allocatedAfter - allocated) / (double) iterations : null);
            report.put("gcCollections", gcCount); report.put("gcMillis", gcMillis);
            report.put("retainedEventsPerRequest", retainedEvents / (double) iterations);
            report.put("omittedDetailCallbacksPerRequest", omitted / (double) iterations);
            report.put("observerFailures", TraceRuntime.observerFailureCount()); report.put("checksum", consumed);
            report.put("scope", "Synthetic single-thread CPU + boundary fixture; identical trace lifecycle harness, no real I/O, no saved data. Not a service overhead SLA.");
            System.out.println("TRACELENS_BENCHMARK " + new ObjectMapper().writeValueAsString(report));
        } finally { TraceRecording.uninstall(); }
    }
    private static long[] run(Tracer tracer, TraceStore store, long seed) {
        long[] counts;
        try (Tracer.ActiveTrace active = tracer.begin("benchmark", "synthetic", EntryChannel.HTTP, Collections.emptyMap()).get()) {
            consumed = SyntheticWorkload.request(seed);
            counts = new long[]{active.trace().eventCount(), active.trace().omittedDetailCallbacks()};
        }
        store.resetAll(); return counts;
    }
    private static long gc(boolean millis) {
        long value = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = millis ? bean.getCollectionTime() : bean.getCollectionCount();
            if (count >= 0) value += count;
        }
        return value;
    }
    private static long allocated() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean)) return -1;
        com.sun.management.ThreadMXBean extended = (com.sun.management.ThreadMXBean) bean;
        if (!extended.isThreadAllocatedMemorySupported()) return -1;
        if (!extended.isThreadAllocatedMemoryEnabled()) extended.setThreadAllocatedMemoryEnabled(true);
        return extended.getThreadAllocatedBytes(Thread.currentThread().getId());
    }
}
