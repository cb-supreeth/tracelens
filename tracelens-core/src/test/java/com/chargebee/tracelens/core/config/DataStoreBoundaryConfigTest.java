package com.chargebee.tracelens.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DataStoreBoundaryConfigTest {
    @Test void enabledByDefaultAndIndependentOfOtherCaptureSwitches() {
        TraceLensConfig config = new TraceLensConfig().setInstrumentMessaging(false)
                .setCaptureMessagingBoundaries(false).setHttpClientCaptureEnabled(false)
                .setCapturePayloads(false);
        assertThat(config.isCaptureDataStoreBoundaries()).isTrue();
        assertThat(config.getPackages()).isEmpty();
        assertThat(config.getExternalCallClasses()).isEmpty();
        config.setCaptureDataStoreBoundaries(false);
        assertThat(config.isCaptureDataStoreBoundaries()).isFalse();
    }

    @Test void jsonCanExplicitlyDisableAutomaticClientBoundaries() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TraceLensConfig config = mapper.readValue("{\"captureDataStoreBoundaries\":false}", TraceLensConfig.class);
        assertThat(config.isCaptureDataStoreBoundaries()).isFalse();
        assertThat(mapper.readValue("{}", TraceLensConfig.class).isCaptureDataStoreBoundaries()).isTrue();
        assertThat(mapper.readTree(mapper.writeValueAsString(config)).get("captureDataStoreBoundaries").asBoolean())
                .isFalse();
    }
}
