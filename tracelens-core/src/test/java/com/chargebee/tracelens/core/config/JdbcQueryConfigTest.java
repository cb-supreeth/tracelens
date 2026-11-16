package com.chargebee.tracelens.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcQueryConfigTest {
    @TempDir Path temp;

    @Test void rawSqlRequiresIndependentExplicitOptIn() throws Exception {
        TraceLensConfig defaults = new ObjectMapper().readValue("{}", TraceLensConfig.class);
        assertThat(defaults.isCaptureJdbcQueries()).isFalse();
        assertThat(defaults.isCaptureJdbcParameters()).isFalse();
        assertThat(defaults.getMaxSqlLength()).isEqualTo(4096);
        assertThat(defaults.getMaxSqlQueriesPerRequest()).isEqualTo(100);
        defaults.setCaptureDataStoreBoundaries(true).setCapturePayloads(true);
        assertThat(defaults.isCaptureJdbcQueries()).isFalse();
        defaults.setCaptureJdbcQueries(true).setCapturePayloads(false).setCaptureDataStoreBoundaries(false);
        assertThat(defaults.isCaptureJdbcQueries()).isTrue();
        assertThat(defaults.isCaptureJdbcParameters()).isFalse();
        defaults.setCaptureJdbcParameters(true);
        assertThat(defaults.isCaptureJdbcParameters()).isTrue();
    }

    @Test void fileLoadsAndSerializesExplicitRawCaptureLimits() throws Exception {
        Path json = temp.resolve("tracelens.json");
        Files.writeString(json, "{\"applicationId\":\"cb-ems\",\"captureJdbcQueries\":true,\"captureJdbcParameters\":true,"
                + "\"maxSqlLength\":8192,\"maxSqlQueriesPerRequest\":50}");
        TraceLensConfig config = new TraceLensConfigLoader().load(json, true);
        assertThat(config.isCaptureJdbcQueries()).isTrue();
        assertThat(config.isCaptureJdbcParameters()).isTrue();
        assertThat(config.getMaxSqlLength()).isEqualTo(8192);
        assertThat(config.getMaxSqlQueriesPerRequest()).isEqualTo(50);
        assertThat(new ObjectMapper().valueToTree(config).path("captureJdbcQueries").asBoolean()).isTrue();
        assertThat(new ObjectMapper().valueToTree(config).path("captureJdbcParameters").asBoolean()).isTrue();
    }

    @Test void localServiceConfigsOptInButPortableDownloadsRemainOptIn() {
        for (String relative : new String[]{"scripts/cb-app/tracelens.json", "scripts/cb-ems/tracelens.json",
                "scripts/java-bundle/tracelens.json", "scripts/java-bundle/ems/tracelens.json"}) {
            Path path = Path.of(relative);
            if (!Files.isRegularFile(path)) path = Path.of("..").resolve(relative);
            TraceLensConfig config = new TraceLensConfigLoader().load(path, true);
            assertThat(config.isCaptureJdbcQueries()).as(relative).isEqualTo(!relative.contains("java-bundle"));
            assertThat(config.isCaptureJdbcParameters()).as(relative).isEqualTo(!relative.contains("java-bundle"));
            assertThat(config.getMaxSqlLength()).as(relative).isEqualTo(4096);
            assertThat(config.getMaxSqlQueriesPerRequest()).as(relative).isEqualTo(100);
            assertThat(config.isCapturePayloads()).as(relative).isFalse();
        }
    }

    @Test void rejectsUnboundedAndNonpositiveLimits() throws Exception {
        for (int length : new int[]{-1, 0, 16385}) {
            assertThat(valid().setMaxSqlLength(length).validate(true))
                    .contains("maxSqlLength must be between 1 and 16384");
        }
        for (int count : new int[]{-1, 0, 1001}) {
            assertThat(valid().setMaxSqlQueriesPerRequest(count).validate(true))
                    .contains("maxSqlQueriesPerRequest must be between 1 and 1000");
        }
        assertThat(valid().setMaxSqlLength(1).setMaxSqlQueriesPerRequest(1).validate(true)).isEmpty();
        assertThat(valid().setMaxSqlLength(16384).setMaxSqlQueriesPerRequest(1000).validate(true)).isEmpty();
        Path json = temp.resolve("invalid.json");
        Files.writeString(json, "{\"applicationId\":\"cb-app\",\"maxSqlLength\":999999}");
        assertThatThrownBy(() -> new TraceLensConfigLoader().load(json, true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("maxSqlLength");
    }

    private TraceLensConfig valid() { return new TraceLensConfig().setApplicationId("cb-ems"); }
}
