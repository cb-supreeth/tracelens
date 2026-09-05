package com.chargebee.ems.sample;

import com.chargebee.tracelens.bridge.TraceRuntime;
import com.chargebee.tracelens.core.config.TraceLensConfig;
import com.chargebee.tracelens.core.export.TraceExporter;
import com.chargebee.tracelens.core.record.TraceRecording;
import com.chargebee.tracelens.core.record.Tracer;
import com.chargebee.tracelens.core.store.TraceStore;
import com.chargebee.tracelens.core.trace.EntryChannel;
import com.chargebee.tracelens.core.trace.Trace;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Isolates two Phase 3 mechanics: (1) lambda bodies are woven (lambda$ methods are
 * synthetic but must not be excluded), (2) executor hops carry the trace context.
 */
public final class LambdaSpikeApp {

    private LambdaSpikeApp() {
    }

    public static void main(String[] args) throws Exception {
        TraceLensConfig config = new TraceLensConfig()
                .setApplicationId("lambda-spike")
                .setApplicationVersion("LOCAL")
                .setPackages(List.of("com.chargebee.ems"));
        TraceStore store = new TraceStore(config);
        TraceRecording.install(store);
        Tracer tracer = new Tracer(store);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        String traceId;
        try (Tracer.ActiveTrace active = tracer
                .begin("lambda-flow", "spike@chargebee.com", EntryChannel.OTHER, Map.of())
                .orElseThrow()) {
            traceId = active.traceId();

            List<String> names = List.of("alpha", "beta", "gamma");
            long count = names.stream()
                    .filter(name -> name.length() > 4)   // lambda body — must appear
                    .count();
            System.out.println("[spike] filtered count=" + count);

            Callable<String> task = () -> {              // lambda body on a pool thread
                return "computed-on-" + Thread.currentThread().getName();
            };
            Future<String> result = pool.submit(task);   // context must hop the pool
            System.out.println("[spike] " + result.get());
        } finally {
            pool.shutdown();
        }

        Trace trace = store.export(traceId).orElseThrow();
        System.out.println("[spike] exported trace:");
        System.out.println(new TraceExporter(config, true).export(trace));
        System.out.println("[spike] bridge counters: emitted=" + TraceRuntime.emittedCount()
                + " droppedNoSink=" + TraceRuntime.droppedNoSinkCount()
                + " droppedNoTrace=" + TraceRuntime.droppedNoTraceCount());
    }
}
