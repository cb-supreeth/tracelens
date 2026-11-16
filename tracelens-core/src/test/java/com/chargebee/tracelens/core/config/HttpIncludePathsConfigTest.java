package com.chargebee.tracelens.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpIncludePathsConfigTest {
    @TempDir Path directory;

    private TraceLensConfig load(String field) throws Exception {
        Path file = directory.resolve("tracelens.json");
        Files.writeString(file, "{\"applicationId\":\"scope-test\"" + field + "}");
        return new TraceLensConfigLoader().load(file, true);
    }

    @Test void omittedDefaultsToAllButExplicitEmptyRemainsDenyAll() throws Exception {
        assertThat(load("").getHttpIncludePaths()).containsExactly("/");
        assertThat(load(",\"httpIncludePaths\":[]").getHttpIncludePaths()).isEmpty();
        assertThat(load(",\"httpIncludePaths\":[\"/api\",\"/cbapi/subscriptions/\"]").getHttpIncludePaths())
                .containsExactly("/api", "/cbapi/subscriptions/");
    }

    @Test void rejectsInvalidShapesAndLiteralPrefixes() {
        for (String value : Arrays.asList("null", "\"/api\"", "{}", "[null]", "[false]", "[42]")) {
            assertThatThrownBy(() -> load(",\"httpIncludePaths\":" + value)).as(value).isInstanceOf(RuntimeException.class);
        }
        for (String path : Arrays.asList("", "api", " /api", "/api ", "/a\tb", "/a\nb", "/api?x=y",
                "/api#frag", "/api/*", "/api/[ab]", "/api/{a,b}", "/api, /other", "/a\\b", "/café", "/a\u007f")) {
            assertThat(new TraceLensConfig().setHttpIncludePaths(Collections.singletonList(path)).validateHttpIncludePaths())
                    .as(path).isNotEmpty();
        }
        assertThat(new TraceLensConfig().setHttpIncludePaths(Collections.nCopies(65, "/api")).validateHttpIncludePaths()).isNotEmpty();
        assertThat(new TraceLensConfig().setHttpIncludePaths(Collections.singletonList("/" + "a".repeat(512))).validateHttpIncludePaths()).isNotEmpty();
        assertThat(new TraceLensConfig().setHttpIncludePaths(Arrays.asList("/", "/api/", "/caf%C3%A9", "/" + "a".repeat(511))).validateHttpIncludePaths()).isEmpty();
    }

    @Test void defensivelyCopiesAndDoesNotExposeMutableList() {
        List<String> paths = new ArrayList<>(Collections.singletonList("/api"));
        TraceLensConfig config = new TraceLensConfig().setHttpIncludePaths(paths);
        paths.add("/");
        assertThat(config.getHttpIncludePaths()).containsExactly("/api");
        assertThatThrownBy(() -> config.getHttpIncludePaths().add("/")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void obsoletePropertyCannotSilentlyOverrideOrBroadenJsonScope() throws Exception {
        String key = TraceLensConfigLoader.LEGACY_HTTP_INCLUDE_PATHS_PROPERTY, previous = System.getProperty(key);
        try {
            for (String legacy : Arrays.asList("/old", "")) {
                System.setProperty(key, legacy);
                for (String field : Arrays.asList("", ",\"httpIncludePaths\":[]", ",\"httpIncludePaths\":[\"/api\"]")) {
                    assertThatThrownBy(() -> load(field)).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("httpIncludePaths JSON array").hasMessageContaining("remove the JVM property");
                }
            }
        } finally {
            if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
        }
    }
}
