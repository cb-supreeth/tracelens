package com.chargebee.ems.sample;

import com.chargebee.tracelens.bridge.TraceContext;
import com.chargebee.tracelens.bridge.TraceRuntime;
import com.chargebee.tracelens.bridge.TraceSnapshot;
import com.chargebee.tracelens.core.config.TraceLensConfig;
import com.chargebee.tracelens.core.export.TraceExporter;
import com.chargebee.tracelens.core.record.TraceLifecycle;
import com.chargebee.tracelens.core.record.TraceRecording;
import com.chargebee.tracelens.core.record.Tracer;
import com.chargebee.tracelens.core.store.TraceStore;
import com.chargebee.tracelens.core.trace.EntryChannel;
import com.chargebee.tracelens.core.trace.Trace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 5 validation without a broker: root trace -> "message" carries the wire
 * attributes -> consumer continues via the same TraceRuntime.beginLifecycle call the
 * queueman advice makes -> both segments joined by traceId at export.
 */
public final class SegmentSpikeApp {

    private SegmentSpikeApp() {
    }

    public static void main(String[] args) {
        TraceLensConfig config = new TraceLensConfig()
                .setApplicationId("segment-spike")
                .setApplicationVersion("LOCAL")
                .setPackages(List.of("com.chargebee.ems"));
        TraceStore store = new TraceStore(config);
        TraceRecording.install(store);
        Tracer tracer = new Tracer(store);
        TraceRuntime.registerLifecycleListener(new TraceLifecycle(tracer));
        EligibilityService service = new EligibilityService(new CustomerRepository());

        // ---- producer side: HTTP-ish root segment ----
        Map<String, String> wireMessage;
        String rootSegmentId;
        try (Tracer.ActiveTrace root = tracer
                .begin("order-flow", "spike@chargebee.com", EntryChannel.HTTP, Map.of("siteId", "site-7"))
                .orElseThrow()) {
            rootSegmentId = root.segmentId();
            service.checkEligibility("cust-3", true);
            // what the SQS producer advice stamps onto the message:
            wireMessage = new LinkedHashMap<>(TraceContext.current().toMap());
        }

        // ---- consumer side: what QueuemanConsumerAdvice does with the headers ----
        Map<String, String> wire = new LinkedHashMap<>(wireMessage);
        String traceId = wire.remove(TraceSnapshot.TRACE_ID_KEY);
        String lensId = wire.remove(TraceSnapshot.LENS_ID_KEY);
        String user = wire.remove(TraceSnapshot.USER_KEY);
        String consumerSegment = TraceRuntime.beginLifecycle(traceId, lensId, user, "SQS", wire);
        try {
            service.checkEligibility("cust-99", false); // consumer-side work
        } finally {
            TraceRuntime.endLifecycle(consumerSegment);
        }

        // ---- joined view by traceId ----
        List<Trace> segments = store.findByTraceId(traceId);
        System.out.println("[spike] segments for traceId " + traceId + ": " + segments.size());
        TraceExporter exporter = new TraceExporter(config, false);
        for (Trace segment : segments) {
            System.out.println("[spike] segment " + segment.getSegmentId().substring(0, 8)
                    + " channel=" + segment.getEntryChannel()
                    + " parent=" + (segment.getParentSegmentId() == null
                            ? "-" : segment.getParentSegmentId().substring(0, 8))
                    + " events=" + segment.eventCount());
            System.out.println(exporter.export(segment));
        }
        System.out.println("[spike] root segment was " + rootSegmentId.substring(0, 8));
    }
}
