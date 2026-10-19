package com.chargebee.tracelens.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import static org.assertj.core.api.Assertions.assertThat;

class DistributedConfigTest {
    @TempDir Path temp;

    @Test void independentServiceIsTheDefault() {
        TraceLensConfig config = new TraceLensConfig();
        assertThat(config.isDistributedTracingEnabled()).isFalse();
        assertThat(config.isHttpClientCaptureEnabled()).isFalse();
        assertThat(config.getHttpPropagationAllowedDestinations()).isEmpty();
        assertThat(config.validateDistributedTracing()).isEmpty();
    }

    @Test void captureDefaultsRemainCompatibleAndCanBeEnabledWithoutPropagation() {
        TraceLensConfig config = new TraceLensConfig().setDistributedTracingEnabled(true);
        assertThat(config.isHttpClientCaptureEnabled()).isTrue();
        config.setHttpClientCaptureEnabled(false);
        assertThat(config.isHttpClientCaptureEnabled()).isFalse();
        assertThat(config.isDistributedTracingEnabled()).isTrue();
        config.setDistributedTracingEnabled(false).setHttpClientCaptureEnabled(true);
        assertThat(config.isHttpClientCaptureEnabled()).isTrue();
        assertThat(config.isDistributedTracingEnabled()).isFalse();
        config.setHttpClientCaptureEnabled(null);
        assertThat(config.isHttpClientCaptureEnabled()).isFalse();
    }

    @Test void loaderAcceptsLocalOnlyCaptureWithoutLineScope() throws Exception {
        Path file = temp.resolve("http-capture.json");
        Files.writeString(file, "{\"applicationId\":\"fixture\",\"httpClientCaptureEnabled\":true}");
        TraceLensConfig config = new TraceLensConfigLoader().load(file, true);
        assertThat(config.isHttpClientCaptureEnabled()).isTrue();
        assertThat(config.isDistributedTracingEnabled()).isFalse();
        assertThat(config.getHttpPropagationAllowedDestinations()).isEmpty();
    }

    @Test void acceptsOnlyExactAuthorities() {
        TraceLensConfig config = new TraceLensConfig().setDistributedTracingEnabled(true)
                .setHttpPropagationAllowedDestinations(Arrays.asList("127.0.0.1:8082", "localhost:8082", "ems.internal", "[::1]:8082"));
        assertThat(config.validateDistributedTracing()).isEmpty();
        for (String destination : new String[] {"*", "*.internal", "http://ems:8082", "ems/path", "user:pass@ems", "ems:99999", "ems:0", "ems?secret", "ems#fragment", " ems", "ems\r\n", ""}) {
            config.setHttpPropagationAllowedDestinations(Collections.singletonList(destination));
            assertThat(config.validateDistributedTracing()).as(destination).isNotEmpty();
        }
    }
}
