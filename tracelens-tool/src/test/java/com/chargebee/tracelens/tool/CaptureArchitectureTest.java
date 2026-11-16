package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Collections;
import static com.chargebee.tracelens.tool.Json.*;
import static org.assertj.core.api.Assertions.*;

class CaptureArchitectureTest {
    @TempDir Path temp;
    private static String digest(String value) { return String.join("", Collections.nCopies(64, value)); }
    private static ObjectNode manifest(String config, String build) {
        return obj("schemaVersion", 1, "applicationVersion", "LOCAL", "configurationFingerprint", digest(config), "agentBuildId", digest(build));
    }
    @Test void segmentEventBudgetLeavesRoomForDownstreamEvidence() throws Exception {
        ObjectNode input = CaptureWorkflowTest.snapshotInput();
        ObjectNode second = ((ObjectNode) input.path("segments").get(0)).deepCopy();
        second.put("segmentId", CaptureWorkflowTest.OTHER);
        second.put("segmentKey", stringify(arr("app", "instance-1", CaptureWorkflowTest.OTHER)));
        ((ArrayNode) input.path("segments")).add(second);
        ObjectNode summary = second.deepCopy(); summary.remove("events");
        ((ArrayNode) input.path("journey").path("segments")).add(summary);
        ViewerSnapshotStore store = new ViewerSnapshotStore(temp.toRealPath(), CaptureWorkflowTest.NAMESPACE);
        ObjectNode result = CaptureTools.projectViewerSnapshot(store.save(input), null, null, 4);
        assertThat(result.path("segments")).hasSize(2);
        for (JsonNode segment : result.path("segments")) {
            assertThat(segment.path("eventsProcessed").asInt()).isEqualTo(2);
            assertThat(segment.path("methods")).isNotEmpty();
            assertThat(segment.path("partial").asBoolean()).isTrue();
        }
    }
    @Test void comparisonWarnsAboutKnownConfigurationAndAgentBuildDifferences() throws Exception {
        ViewerSnapshotStore store = new ViewerSnapshotStore(temp.toRealPath(), CaptureWorkflowTest.NAMESPACE);
        ObjectNode before = CaptureWorkflowTest.snapshotInput(), after = before.deepCopy();
        ((ObjectNode) before.path("segments").get(0)).set("recordingManifest", manifest("a", "b"));
        ((ObjectNode) after.path("segments").get(0)).set("recordingManifest", manifest("c", "d"));
        String first = store.save(before).path("id").asText(), second = store.save(after).path("id").asText();
        String warnings = CaptureTools.compareSavedSnapshots(store, first, second).path("warnings").toString();
        assertThat(warnings).contains("capture_configuration_differs", "agent_build_differs");
        String legacy = store.save(CaptureWorkflowTest.snapshotInput()).path("id").asText();
        assertThat(CaptureTools.compareSavedSnapshots(store, first, legacy).path("warnings").toString())
                .doesNotContain("capture_configuration_differs", "agent_build_differs");
    }
    @Test void optionalAnalysisCoverageIsValidatedAndCannotClaimMoreEventsThanSaved() throws Exception {
        ViewerSnapshotStore store = new ViewerSnapshotStore(temp.toRealPath(), CaptureWorkflowTest.NAMESPACE);
        ObjectNode input = CaptureWorkflowTest.snapshotInput();
        ((ObjectNode) input.path("segments").get(0)).set("analysisCoverage", obj("savedEvents", 5, "retainedEvents", 5, "rawBytesRead", 2000, "complete", true));
        ObjectNode projected = CaptureTools.projectViewerSnapshot(store.save(input), null, null);
        assertThat(projected.path("segments").get(0).path("analysisCoverage").path("complete").asBoolean()).isTrue();
        ((ObjectNode) projected.path("segments").get(0).path("analysisCoverage")).put("retainedEvents", 6);
        assertThatThrownBy(() -> Captures.validateCapture(projected)).isInstanceOf(ToolException.class);
    }
}
