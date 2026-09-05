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

/**
 * Phase 2 full-path validation: agent weaving -> bridge -> core sink -> store ->
 * export. Run WITH the agent; without it the trace stays empty (nothing woven).
 *
 *   java -javaagent:tracelens-agent-...-agent.jar \
 *        -Dtracelens.configPath=samples/line-spike/tracelens.json \
 *        -cp ... com.chargebee.ems.sample.FullPathSpikeApp
 */
public final class FullPathSpikeApp {

    private FullPathSpikeApp() {
    }

    public static void main(String[] args) {
        TraceLensConfig config = new TraceLensConfig()
                .setApplicationId("line-spike")
                .setApplicationVersion("LOCAL")
                .setPackages(List.of("com.chargebee.ems"));
        TraceStore store = new TraceStore(config);
        TraceRecording.install(store);
        Tracer tracer = new Tracer(store);
        EligibilityService service = new EligibilityService(new CustomerRepository());

        String traceId;
        try (Tracer.ActiveTrace active = tracer
                .begin("spike-flow", "spike@chargebee.com", EntryChannel.OTHER, Map.of("env", "local"))
                .orElseThrow()) {
            traceId = active.traceId();
            boolean eligible = service.checkEligibility("cust-7", true);
            System.out.println("[spike] eligibility result=" + eligible);
        }

        Trace trace = store.export(traceId).orElseThrow();
        String json = new TraceExporter(config, true).export(trace);
        System.out.println("[spike] exported trace:");
        System.out.println(json);
        System.out.println("[spike] bridge counters: emitted=" + TraceRuntime.emittedCount()
                + " droppedNoSink=" + TraceRuntime.droppedNoSinkCount()
                + " droppedNoTrace=" + TraceRuntime.droppedNoTraceCount());
    }
}
