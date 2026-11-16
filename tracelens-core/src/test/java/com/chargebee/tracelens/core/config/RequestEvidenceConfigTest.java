package com.chargebee.tracelens.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestEvidenceConfigTest {
    @TempDir Path temp;

    @Test void separateExplicitOptInsDoNotInheritLegacyOrJdbcFlags() throws Exception {
        TraceLensConfig config = new ObjectMapper().readValue("{}", TraceLensConfig.class);
        config.setCapturePayloads(true).setCaptureJdbcQueries(true).setCaptureJdbcParameters(true);
        assertThat(config.isCaptureHttpQueryParameters()).isFalse();
        assertThat(config.isCaptureHttpRequestBodies()).isFalse();
        assertThat(config.isRedactHttpPayloads()).isTrue();
        assertThat(config.isCaptureClickHouseQueries()).isFalse();
        assertThat(config.isCaptureClickHouseParameters()).isFalse();
        assertThat(config.getMaxHttpPayloadLength()).isEqualTo(4096);
    }

    @Test void explicitSettingsRoundTripAndLoad() throws Exception {
        Path json = temp.resolve("tracelens.json");
        Files.writeString(json, "{\"applicationId\":\"cb-ems\",\"captureHttpQueryParameters\":true,"
                + "\"captureHttpRequestBodies\":true,\"redactHttpPayloads\":false,\"maxHttpPayloadLength\":8192,"
                + "\"captureClickHouseQueries\":true,\"captureClickHouseParameters\":true}");
        TraceLensConfig loaded = new TraceLensConfigLoader().load(json, true);
        ObjectMapper mapper = new ObjectMapper();
        TraceLensConfig config = mapper.readValue(mapper.writeValueAsBytes(loaded), TraceLensConfig.class);
        assertThat(config.isCaptureHttpQueryParameters()).isTrue();
        assertThat(config.isCaptureHttpRequestBodies()).isTrue();
        assertThat(config.isRedactHttpPayloads()).isFalse();
        assertThat(config.getMaxHttpPayloadLength()).isEqualTo(8192);
        assertThat(config.isCaptureClickHouseQueries()).isTrue();
        assertThat(config.isCaptureClickHouseParameters()).isTrue();
        assertThat(config.isCapturePayloads()).isFalse();
        assertThat(config.isCaptureJdbcQueries()).isFalse();
        assertThat(config.validate(true)).isEmpty();
    }

    @Test void redactionSettingIsFluentAndDoesNotEnablePayloadCapture() throws Exception {
        TraceLensConfig config = new TraceLensConfig();
        assertThat(config.setRedactHttpPayloads(false)).isSameAs(config);
        assertThat(config.isRedactHttpPayloads()).isFalse();
        assertThat(config.isCaptureHttpQueryParameters()).isFalse();
        assertThat(config.isCaptureHttpRequestBodies()).isFalse();
        ObjectMapper mapper = new ObjectMapper();
        config.setRedactHttpPayloads(true);
        assertThat(mapper.readValue(mapper.writeValueAsBytes(config), TraceLensConfig.class).isRedactHttpPayloads())
                .isTrue();
    }

    @Test void rejectsUnboundedPayloadLimits() throws Exception {
        for (int value : new int[]{-1, 0, 16385}) {
            assertThat(new TraceLensConfig().setApplicationId("cb-app")
                    .setMaxHttpPayloadLength(value).validate(true))
                    .contains("maxHttpPayloadLength must be between 1 and 16384");
        }
        for (int value : new int[]{1, 4096, 16384}) {
            assertThat(new TraceLensConfig().setApplicationId("cb-app")
                    .setMaxHttpPayloadLength(value).validate(true)).isEmpty();
        }
        Path json = temp.resolve("invalid.json");
        Files.writeString(json, "{\"applicationId\":\"cb-app\",\"maxHttpPayloadLength\":2147483647}");
        assertThatThrownBy(() -> new TraceLensConfigLoader().load(json, true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("maxHttpPayloadLength");
    }
}
