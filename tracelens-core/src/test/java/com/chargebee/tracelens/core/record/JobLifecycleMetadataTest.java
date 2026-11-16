package com.chargebee.tracelens.core.record;

import com.chargebee.tracelens.bridge.TraceContext;
import com.chargebee.tracelens.bridge.TraceRuntime;
import com.chargebee.tracelens.core.config.TraceLensConfig;
import com.chargebee.tracelens.core.export.TraceExporter;
import com.chargebee.tracelens.core.store.TraceStore;
import com.chargebee.tracelens.core.store.TraceSummary;
import com.chargebee.tracelens.core.trace.EntryChannel;
import com.chargebee.tracelens.core.trace.Trace;
import com.chargebee.tracelens.core.trace.TraceStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobLifecycleMetadataTest {
    private final TraceLensConfig config = new TraceLensConfig()
            .setApplicationId("job-test").setPackages(List.of("com.example"));
    private final TraceStore store = new TraceStore(config);
    private final Tracer tracer = new Tracer(store);
    private final TraceLifecycle lifecycle = new TraceLifecycle(tracer);

    @AfterEach
    void cleanup() {
        TraceRuntime.unregisterLifecycleListener();
        TraceContext.clear();
        store.close();
    }

    private String beginJob() {
        return lifecycle.begin(null, "job:compute_billing_metrics", "scheduler", "SCHEDULER",
                Map.of("jobType", "compute_billing_metrics", "jobId", "123",
                        "jobOutcome", "RUNNING", "scheduler", "chargebee"));
    }

    @Test
    void mergesTerminalMetadataBeforeCompletionAndExportsIt() {
        String id = beginJob();
        Trace trace = store.get(id).orElseThrow();
        assertThat(new TraceSummary(trace).getStatus()).isEqualTo(TraceStatus.ACTIVE);
        assertThat(new TraceSummary(trace).getJobOutcome()).isEqualTo("RUNNING");

        lifecycle.end(id, Map.of("jobOutcome", "FAILED", "jobAuditStatus", "FAILURE",
                "jobFailureType", "java.lang.IllegalStateException"));

        TraceSummary summary = new TraceSummary(trace);
        assertThat(summary.getStatus()).isEqualTo(TraceStatus.COMPLETED);
        assertThat(summary.getJobType()).isEqualTo("compute_billing_metrics");
        assertThat(summary.getJobId()).isEqualTo("123");
        assertThat(summary.getScheduler()).isEqualTo("chargebee");
        assertThat(summary.getJobOutcome()).isEqualTo("FAILED");
        assertThat(summary.getJobAuditStatus()).isEqualTo("FAILURE");
        assertThat(summary.getJobFailureType()).isEqualTo("java.lang.IllegalStateException");
        assertThat(TraceContext.current()).isNull();

        JsonNode exported = new TraceExporter(config).toJson(trace);
        JsonNode listed = new ObjectMapper().valueToTree(summary);
        for (String field : List.of("jobType", "jobId", "jobOutcome", "jobAuditStatus", "jobFailureType", "scheduler")) {
            assertThat(exported.path("attributes").get(field)).as(field).isEqualTo(listed.get(field));
        }
        assertThat(exported.path("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void allTerminalBusinessOutcomesAreIndependentOfLifecycleStatus() {
        for (String outcome : List.of("SUCCEEDED", "SKIPPED", "FAILED", "UNKNOWN")) {
            String id = beginJob();
            lifecycle.end(id, Map.of("jobOutcome", outcome));
            TraceSummary summary = new TraceSummary(store.get(id).orElseThrow());
            assertThat(summary.getStatus()).isEqualTo(TraceStatus.COMPLETED);
            assertThat(summary.getJobOutcome()).isEqualTo(outcome);
            assertThat(TraceContext.current()).isNull();
        }
    }

    @Test
    void restoresPreviousContextAndIgnoresRepeatedEnd() {
        try (Tracer.ActiveTrace outer = tracer.begin("http", "user", EntryChannel.HTTP, Map.of()).orElseThrow()) {
            String id = beginJob();
            assertThat(TraceContext.current().getSegmentId()).isEqualTo(id);
            lifecycle.end(id, Map.of("jobOutcome", "SUCCEEDED"));
            assertThat(TraceContext.current().getSegmentId()).isEqualTo(outer.segmentId());
            lifecycle.end(id, Map.of("jobOutcome", "FAILED"));
            assertThat(new TraceSummary(store.get(id).orElseThrow()).getJobOutcome()).isEqualTo("SUCCEEDED");
            assertThat(TraceContext.current().getSegmentId()).isEqualTo(outer.segmentId());
        }
        assertThat(TraceContext.current()).isNull();
    }

    @Test
    void closesAndRestoresContextEvenIfMetadataUpdateThrows() {
        try (Tracer.ActiveTrace outer = tracer.begin("http", "user", EntryChannel.HTTP, Map.of()).orElseThrow()) {
            String id = beginJob();
            Map<String, String> invalid = new LinkedHashMap<>();
            invalid.put(null, "invalid");
            assertThatThrownBy(() -> lifecycle.end(id, invalid)).isInstanceOf(NullPointerException.class);
            assertThat(store.get(id).orElseThrow().getStatus()).isEqualTo(TraceStatus.COMPLETED);
            assertThat(TraceContext.current().getSegmentId()).isEqualTo(outer.segmentId());
            lifecycle.end(id, Map.of("jobOutcome", "FAILED"));
            assertThat(TraceContext.current().getSegmentId()).isEqualTo(outer.segmentId());
        }
    }

    @Test
    void oldEndAndNullOrUnknownSegmentsRemainSafe() {
        String id = lifecycle.begin(null, "MyJob.run", "scheduler", "SCHEDULER", null);
        lifecycle.end(id);
        TraceSummary summary = new TraceSummary(store.get(id).orElseThrow());
        assertThat(summary.getStatus()).isEqualTo(TraceStatus.COMPLETED);
        assertThat(summary.getJobType()).isNull();
        assertThat(summary.getJobId()).isNull();
        assertThat(summary.getJobOutcome()).isNull();
        assertThat(summary.getJobAuditStatus()).isNull();
        assertThat(summary.getJobFailureType()).isNull();
        assertThat(summary.getScheduler()).isNull();
        lifecycle.end(null, Map.of("jobOutcome", "FAILED"));
        lifecycle.end("missing", Map.of("jobOutcome", "FAILED"));
        assertThat(TraceContext.current()).isNull();
    }

    @Test
    void bridgeForwardsEndAttributesToCoreListener() {
        TraceRuntime.registerLifecycleListener(lifecycle);
        String id = TraceRuntime.beginLifecycle(null, "job:compute_billing_metrics", "scheduler", "SCHEDULER",
                Map.of("jobType", "compute_billing_metrics", "jobOutcome", "RUNNING"));
        TraceRuntime.endLifecycle(id, Map.of("jobOutcome", "SUCCEEDED"));
        assertThat(new TraceSummary(store.get(id).orElseThrow()).getJobOutcome()).isEqualTo("SUCCEEDED");
        assertThat(store.get(id).orElseThrow().getStatus()).isEqualTo(TraceStatus.COMPLETED);
        assertThat(TraceContext.current()).isNull();
    }

    @Test
    void bridgeNullEndAttributesStillCloseTheSegment() {
        TraceRuntime.registerLifecycleListener(lifecycle);
        String id = TraceRuntime.beginLifecycle(null, "job", "scheduler", "SCHEDULER", null);
        TraceRuntime.endLifecycle(id, null);
        assertThat(store.get(id).orElseThrow().getStatus()).isEqualTo(TraceStatus.COMPLETED);
        assertThat(TraceContext.current()).isNull();
    }
}
