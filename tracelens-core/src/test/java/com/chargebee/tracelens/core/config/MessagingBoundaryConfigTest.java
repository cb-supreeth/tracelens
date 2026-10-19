package com.chargebee.tracelens.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MessagingBoundaryConfigTest {
    @Test void unsetCaptureFollowsLegacyMessagingSwitch() {
        TraceLensConfig config = new TraceLensConfig();
        assertThat(config.isCaptureMessagingBoundaries()).isTrue();
        config.setInstrumentMessaging(false);
        assertThat(config.isCaptureMessagingBoundaries()).isFalse();
        config.setCaptureMessagingBoundaries(true);
        assertThat(config.isCaptureMessagingBoundaries()).isTrue();
        assertThat(config.isInstrumentMessaging()).isFalse();
        config.setCaptureMessagingBoundaries(null);
        assertThat(config.isCaptureMessagingBoundaries()).isFalse();
    }

    @Test void jsonCanOptIntoObservationWithoutPropagation() throws Exception {
        TraceLensConfig config = new ObjectMapper().readValue(
                "{\"instrumentMessaging\":false,\"captureMessagingBoundaries\":true}", TraceLensConfig.class);
        assertThat(config.isCaptureMessagingBoundaries()).isTrue();
        assertThat(config.isInstrumentMessaging()).isFalse();
    }
}
