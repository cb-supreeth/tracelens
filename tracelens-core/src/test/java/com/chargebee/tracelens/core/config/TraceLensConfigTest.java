package com.chargebee.tracelens.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceLensConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsFullConfigFromJson() throws Exception {
        Path config = tempDir.resolve("tracelens.json");
        Files.writeString(config, """
                {
                  "applicationId": "cb-ems",
                  "applicationVersion": "333",
                  "packages": ["com.chargebee.ems"],
                  "excludedPackages": ["com.chargebee.ems.api.model"],
                  "blacklistedNodes": ["ToStringBuilder"],
                  "capturePayloads": true,
                  "maxTrackedEvents": 12345,
                  "maxTrackedRequests": 7,
                  "idleTtlMinutes": 5,
                  "evictOnExport": false,
                  "trackMethods": false,
                  "trackExceptions": false,
                  "requireLabels": true,
                  "contextParams": { "jvmArgs": ["aws.profile.name"] }
                }
                """);

        TraceLensConfig loaded = new TraceLensConfigLoader().load(config);

        assertThat(loaded.getApplicationId()).isEqualTo("cb-ems");
        assertThat(loaded.getApplicationVersion()).isEqualTo("333");
        assertThat(loaded.getPackages()).containsExactly("com.chargebee.ems");
        assertThat(loaded.getExcludedPackages()).containsExactly("com.chargebee.ems.api.model");
        assertThat(loaded.getBlacklistedNodes()).containsExactly("ToStringBuilder");
        assertThat(loaded.isCapturePayloads()).isTrue();
        assertThat(loaded.getMaxTrackedEvents()).isEqualTo(12345);
        assertThat(loaded.getMaxTrackedRequests()).isEqualTo(7);
        assertThat(loaded.getIdleTtlMinutes()).isEqualTo(5);
        assertThat(loaded.isEvictOnExport()).isFalse();
        assertThat(loaded.isTrackMethods()).isFalse();
        assertThat(loaded.isTrackExceptions()).isFalse();
        assertThat(loaded.isRequireLabels()).isTrue();
        assertThat(loaded.getContextParams().getJvmArgs()).containsExactly("aws.profile.name");
    }

    @Test
    void appliesFlowLensDefaults() throws Exception {
        Path config = tempDir.resolve("tracelens.json");
        Files.writeString(config, """
                { "applicationId": "cb-ems", "packages": ["com.chargebee.ems"] }
                """);

        TraceLensConfig loaded = new TraceLensConfigLoader().load(config);

        assertThat(loaded.getApplicationVersion()).isEqualTo("LOCAL");
        assertThat(loaded.isCapturePayloads()).isFalse();
        assertThat(loaded.getMaxTrackedEvents()).isEqualTo(500_000);
        assertThat(loaded.getMaxTrackedRequests()).isEqualTo(200);
        assertThat(loaded.getIdleTtlMinutes()).isEqualTo(60);
        assertThat(loaded.isEvictOnExport()).isTrue();
        assertThat(loaded.isTrackMethods()).isTrue();
        assertThat(loaded.isTrackExceptions()).isTrue();
        assertThat(loaded.isRequireLabels()).isFalse();
    }

    @Test
    void rejectsMissingApplicationId() {
        TraceLensConfig config = new TraceLensConfig().setPackages(List.of("com.chargebee.ems"));
        assertThat(config.validate()).anyMatch(e -> e.contains("applicationId"));
    }

    @Test
    void rejectsEmptyScope() {
        TraceLensConfig config = new TraceLensConfig().setApplicationId("cb-ems");
        assertThat(config.validate()).anyMatch(e -> e.contains("packages/modules"));
    }

    @Test
    void modulesAloneSatisfyScope() {
        TraceLensConfig config = new TraceLensConfig()
                .setApplicationId("cb-ems")
                .setModules(List.of("cb-ems-app"));
        assertThat(config.validate()).isEmpty();
    }

    @Test
    void failsFastWhenConfigPathMissing() {
        assertThatThrownBy(() -> new TraceLensConfigLoader().load(tempDir.resolve("nope.json")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void unknownFieldsAreIgnored() throws Exception {
        Path config = tempDir.resolve("tracelens.json");
        Files.writeString(config, """
                { "applicationId": "cb-ems", "packages": ["a.b"], "futureField": {"x": 1} }
                """);
        assertThat(new TraceLensConfigLoader().load(config).getApplicationId()).isEqualTo("cb-ems");
    }
}
