package com.chargebee.tracelens.core.export;

import com.chargebee.tracelens.core.config.TraceLensConfig;
import com.chargebee.tracelens.core.trace.Trace;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CaptureProvenanceTest {
    @Test void fingerprintsEffectiveSettingsWithoutReturningConfigurationValues() {
        TraceLensConfig first = new TraceLensConfig(); first.setApplicationId("sample-service");
        TraceLensConfig second = new TraceLensConfig(); second.setApplicationId("sample-service");
        assertThat(CaptureProvenance.fingerprint(first)).matches("[0-9a-f]{64}").isEqualTo(CaptureProvenance.fingerprint(second));
        second.setCaptureMode("methods");
        assertThat(CaptureProvenance.fingerprint(second)).isNotEqualTo(CaptureProvenance.fingerprint(first));
        Trace trace = new Trace("sample", "test-user", com.chargebee.tracelens.core.trace.EntryChannel.HTTP, 1, 1);
        ObjectNode exported = new TraceExporter(first).metadata(trace);
        assertThat(exported.path("recordingManifest").path("configurationFingerprint").asText()).isEqualTo(CaptureProvenance.fingerprint(first));
        assertThat(exported.path("recordingManifest").has("packages")).isFalse();
        ObjectNode summary = exported.objectNode(); TraceProjection.capabilities(summary, first);
        assertThat(summary.path("recordingManifest")).isEqualTo(exported.path("recordingManifest"));
        assertThat(summary.path("integrationStatus").path("jdbc").path("note").asText()).contains("not proof");
        assertThat(summary.path("runtimeDiagnostics").path("executorQuarantinedPools").isIntegralNumber()).isTrue();
        assertThat(summary.path("runtimeDiagnostics").path("executorAssociationDisabled").isBoolean()).isTrue();
        assertThat(summary.path("runtimeDiagnostics").path("executorCoverage").asText()).contains("direct queue mutation is unsupported");
    }
}
