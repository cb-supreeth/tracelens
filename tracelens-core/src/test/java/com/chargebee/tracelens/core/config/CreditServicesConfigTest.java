package com.chargebee.tracelens.core.config;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CreditServicesConfigTest {
    @Test
    void cbAppTemplatesCaptureCreditApisAndRestrictPropagationToLocalServiceAuthorities() {
        for (String directory : List.of("cb-app", "java-bundle")) {
            Path relative = Path.of("scripts", directory, "tracelens.json");
            Path file = Files.isRegularFile(relative) ? relative : Path.of("..").resolve(relative);
            TraceLensConfig config = new TraceLensConfigLoader().load(file);
            assertThat(config.validate(true)).isEmpty();
            // Descendants include /ledger_operations/{handle}, /capture and /allocate.
            assertThat(config.getHttpIncludePaths()).contains(
                    "/api/v2/ledger_account_balances", "/api/v2/ledger_operations",
                    "/api/v2/grant_blocks", "/api/v2/promotional_grants", "/api/v2/custom_pricing_units")
                    .doesNotContain("/", "/api/v2");
            assertThat(config.getPackages()).contains("com.chargebee.app.ledger",
                    "com.chargebee.app.api.helper.configs.ubb.consumption_ledger");
            assertThat(config.isDistributedTracingEnabled()).isTrue();
            assertThat(config.isHttpClientCaptureEnabled()).isTrue();
            assertThat(config.getHttpPropagationAllowedDestinations()).containsExactly(
                    "127.0.0.1:8082", "localhost:8082",
                    "127.0.0.1:9187", "localhost:9187", "mannar-test.localcb.in:9187",
                    "127.0.0.1:7087", "localhost:7087", "mannar-test.localcb.in:7087");
            assertThat(config.isCapturePayloads()).isFalse();
            assertThat(config.isInstrumentMessaging()).isFalse();
        }
    }

    @Test
    void middlewareConfigCapturesSelectedModulesWithoutSqlOrBackgroundJobs() {
        Path relative = Path.of("scripts", "cb-ui-middleware", "tracelens.json");
        Path file = Files.isRegularFile(relative) ? relative : Path.of("..").resolve(relative);
        TraceLensConfig config = new TraceLensConfigLoader().load(file);
        assertThat(config.validate(true)).isEmpty();
        assertThat(config.getApplicationId()).isEqualTo("cb-ui-middleware");
        assertThat(config.getHttpIncludePaths()).containsExactly("/i", "/cn", "/p", "/c", "/s", "/o", "/q", "/e", "/l", "/ubb");
        assertThat(config.getPackages()).contains("com.chargebee.v2.fe");
        assertThat(config.getMaxTrackedRequests()).isEqualTo(100);
        assertThat(config.getMaxTrackedEvents()).isEqualTo(100_000);
        assertThat(config.isCapturePayloads()).isFalse();
        assertThat(config.isCaptureJdbcQueries()).isFalse();
        assertThat(config.isCaptureJdbcParameters()).isFalse();
        assertThat(config.isInstrumentScheduled()).isFalse();
        assertThat(config.isInstrumentMessaging()).isFalse();
        assertThat(config.getHttpPropagationAllowedDestinations()).isEmpty();
    }

    @Test
    void creditServiceConfigsHaveDistinctIdentityAndBoundedLocalCapture() {
        for (String service : List.of("cb-consumption-ledger", "cb-grant-orchestrator")) {
            Path relative = Path.of("scripts", service, "tracelens.json");
            Path file = Files.isRegularFile(relative) ? relative : Path.of("..").resolve(relative);
            TraceLensConfig config = new TraceLensConfigLoader().load(file);
            assertThat(config.validate(true)).isEmpty();
            assertThat(config.getApplicationId()).isEqualTo(service);
            assertThat(config.getHttpIncludePaths()).containsExactly("/api/v2", "/micro_service");
            assertThat(config.getPackages()).allMatch(p -> p.startsWith("com.chargebee.app."));
            if (service.equals("cb-grant-orchestrator")) {
                assertThat(config.getPackages()).contains("com.chargebee.app.adapter", "com.chargebee.app.adaptors");
            }
            assertThat(config.getMaxTrackedEvents()).isEqualTo(100_000);
            assertThat(config.getMaxTrackedRequests()).isEqualTo(100);
            assertThat(config.getIdleTtlMinutes()).isEqualTo(30);
            assertThat(config.isCapturePayloads()).isFalse();
            assertThat(config.isCaptureJdbcQueries()).isTrue();
            assertThat(config.isCaptureJdbcParameters()).isFalse();
            assertThat(config.isEvictOnExport()).isFalse();
            assertThat(config.isInstrumentScheduled()).isFalse();
            assertThat(config.getChargebeeJobTypes()).isEmpty();
            assertThat(config.isInstrumentMessaging()).isFalse();
            assertThat(config.isCaptureMessagingBoundaries()).isTrue();
            assertThat(config.isDistributedTracingEnabled()).isTrue();
            assertThat(config.isHttpClientCaptureEnabled()).isTrue();
            assertThat(config.getHttpPropagationAllowedDestinations()).doesNotContain("*", "127.0.0.1:8081");
            assertThat(config.getHttpIdentityHooks()).isEmpty();
        }
    }
}
