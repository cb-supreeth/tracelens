package com.chargebee.tracelens.core.record;

import com.chargebee.tracelens.bridge.TraceContext;
import com.chargebee.tracelens.bridge.TraceRuntime;
import com.chargebee.tracelens.bridge.TraceSnapshot;
import com.chargebee.tracelens.core.config.TraceLensConfig;
import com.chargebee.tracelens.core.store.TraceStore;
import com.chargebee.tracelens.core.trace.EntryChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The segment model: root segments, wire continuation, store joining by traceId. */
class SegmentModelTest {

    private TraceStore store;

    @AfterEach
    void cleanup() {
        TraceRuntime.unregisterLifecycleListener();
        TraceContext.clear();
    }

    private Tracer newTracer() {
        store = new TraceStore(new TraceLensConfig()
                .setApplicationId("test").setPackages(List.of("a.b")));
        return new Tracer(store);
    }

    @Test
    void rootSegmentHasSegmentIdEqualToTraceId() {
        Tracer tracer = newTracer();
        try (Tracer.ActiveTrace active = tracer.begin("lens", "user", EntryChannel.HTTP, Map.of()).orElseThrow()) {
            assertThat(active.segmentId()).isEqualTo(active.traceId());
        }
    }

    @Test
    void continuedSegmentSharesTraceIdWithFreshSegmentId() {
        Tracer tracer = newTracer();
        String rootTraceId;
        String rootSegmentId;
        try (Tracer.ActiveTrace root = tracer.begin("lens", "user", EntryChannel.HTTP, Map.of()).orElseThrow()) {
            rootTraceId = root.traceId();
            rootSegmentId = root.segmentId();
        }

        // simulate the wire: producer's snapshot flattens to message attributes
        Map<String, String> wire = new TraceSnapshot(rootTraceId, rootSegmentId, "lens", "user", Map.of())
                .toMap();
        TraceSnapshot received = TraceSnapshot.fromMap(wire);

        String consumerSegmentId;
        try (Tracer.ActiveTrace segment = tracer.beginSegment(received, EntryChannel.SQS).orElseThrow()) {
            assertThat(segment.traceId()).isEqualTo(rootTraceId);       // join key shared
            assertThat(segment.segmentId()).isNotEqualTo(rootSegmentId); // fresh segment
            assertThat(segment.trace().getParentSegmentId()).isEqualTo(rootSegmentId);
            consumerSegmentId = segment.segmentId();
        }

        // both segments retained, joinable by traceId
        assertThat(store.findByTraceId(rootTraceId)).hasSize(2);
        assertThat(store.get(rootSegmentId)).isPresent();
        assertThat(store.get(consumerSegmentId)).isPresent();
    }

    @Test
    void lifecycleListenerBridgesAgentHooksToTracer() {
        Tracer tracer = newTracer();
        TraceRuntime.registerLifecycleListener(new TraceLifecycle(tracer));

        // what ScheduledJobAdvice does: fresh trace for a job
        String jobSegment = TraceRuntime.beginLifecycle(null, "MyJob.run", "scheduler",
                "SCHEDULER", null);
        assertThat(jobSegment).isNotNull();
        assertThat(TraceContext.currentTraceId()).isEqualTo(jobSegment); // root: same id
        TraceRuntime.endLifecycle(jobSegment);
        assertThat(TraceContext.current()).isNull();

        // what QueuemanConsumerAdvice does: continue from wire attributes
        String wireTraceId = "trace-from-wire";
        String wireParentSegment = "parent-segment-1";
        String consumerSegment = TraceRuntime.beginLifecycle(wireTraceId, "lens", "user", "SQS",
                Map.of(TraceSnapshot.SEGMENT_ID_KEY, wireParentSegment));
        assertThat(consumerSegment).isNotNull().isNotEqualTo(wireParentSegment);
        TraceRuntime.endLifecycle(consumerSegment);

        assertThat(store.findByTraceId(wireTraceId)).hasSize(1);
        assertThat(store.findByTraceId(wireTraceId).get(0).getParentSegmentId())
                .isEqualTo(wireParentSegment);
        assertThat(store.findByTraceId(wireTraceId).get(0).getEntryChannel())
                .isEqualTo(EntryChannel.SQS);
    }

    @Test
    void lifecycleWithoutListenerIsANoOp() {
        // no listener registered (agent-only mode): hooks must be silent no-ops
        String segment = TraceRuntime.beginLifecycle(null, "MyJob.run", "scheduler", "SCHEDULER", null);
        assertThat(segment).isNull();
        TraceRuntime.endLifecycle(null); // must not throw
    }
}
