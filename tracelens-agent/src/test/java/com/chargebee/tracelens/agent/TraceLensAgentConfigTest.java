package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.core.config.TraceLensConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceLensAgentConfigTest {
    private final Map<String, String> previous = new HashMap<>();

    @TempDir
    Path directory;

    @BeforeEach
    void isolateProperties() {
        for (String key : new String[]{"tracelens.configPath", "tracelens.packages",
                "tracelens.applicationId", "tracelens.applicationVersion"}) {
            previous.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
    }

    @AfterEach
    void restoreProperties() {
        previous.forEach((key, value) -> {
            if (value == null) System.clearProperty(key);
            else System.setProperty(key, value);
        });
    }

    @Test
    void metadataOnlyStandaloneDefaultsToCbApp() throws Exception {
        TraceLensConfig config = resolve(true);
        assertThat(config.getApplicationId()).isEqualTo("cb-app");
        assertThat(config.getPackages()).isEmpty();
        assertThat(config.validate(true)).isEmpty();
    }

    @Test
    void packageFallbackDefaultsToCbAppInBothModes() throws Exception {
        System.setProperty("tracelens.packages", "com.example.app");
        for (boolean standalone : new boolean[]{false, true}) {
            TraceLensConfig config = resolve(standalone);
            assertThat(config.getApplicationId()).isEqualTo("cb-app");
            assertThat(config.getPackages()).containsExactly("com.example.app");
            assertThat(config.validate(standalone)).isEmpty();
        }
    }

    @Test
    void explicitSystemPropertyOverridesBothFallbackPaths() throws Exception {
        System.setProperty("tracelens.applicationId", "cb-ems");
        assertThat(resolve(true).getApplicationId()).isEqualTo("cb-ems");
        System.setProperty("tracelens.packages", "com.example.ems");
        assertThat(resolve(true).getApplicationId()).isEqualTo("cb-ems");
        assertThat(resolve(false).getApplicationId()).isEqualTo("cb-ems");
    }

    @Test
    void explicitlyConfiguredLongFormNameIsNotRewritten() throws Exception {
        System.setProperty("tracelens.applicationId", "chargebee-app");
        assertThat(resolve(true).getApplicationId()).isEqualTo("chargebee-app");
        System.setProperty("tracelens.packages", "com.example.app");
        assertThat(resolve(false).getApplicationId()).isEqualTo("chargebee-app");
    }

    @Test
    void explicitJsonNameIsPreserved() throws Exception {
        useJsonConfig();
        assertThat(resolve(true).getApplicationId()).isEqualTo("cb-ems");
        assertThat(resolve(false).getApplicationId()).isEqualTo("cb-ems");
    }

    @Test
    void systemPropertyStillTakesPrecedenceOverJson() throws Exception {
        useJsonConfig();
        System.setProperty("tracelens.applicationId", "custom-service");
        assertThat(resolve(true).getApplicationId()).isEqualTo("custom-service");
        assertThat(resolve(false).getApplicationId()).isEqualTo("custom-service");
    }

    private void useJsonConfig() throws Exception {
        Path file = directory.resolve("tracelens.json");
        Files.writeString(file, "{\"applicationId\":\"cb-ems\",\"packages\":[\"com.example.ems\"]}");
        System.setProperty("tracelens.configPath", file.toString());
    }

    private TraceLensConfig resolve(boolean standalone) throws Exception {
        Method method = TraceLensAgent.class.getDeclaredMethod("resolveConfig", boolean.class);
        method.setAccessible(true);
        return (TraceLensConfig) method.invoke(null, standalone);
    }
}
