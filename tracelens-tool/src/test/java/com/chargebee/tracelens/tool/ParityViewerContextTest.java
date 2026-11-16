package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ParityViewerContextTest {
    @TempDir Path temp;
    JsonNode context(String url, String source) {
        ToolConfig config = ToolConfig.fromArgs(new String[]{"--url", url, "--source-root", source});
        return ViewerServer.viewerContext(config);
    }
    @Test void equivalentEndpointsAndPathsKeepSavedDataNamespaceStable() {
        String source = temp.toString();
        assertThat(context("http://LOCALHOST:80/", source)).isEqualTo(context("http://localhost", source));
        assertThat(context("https://EXAMPLE.test:443/agent/", source)).isEqualTo(context("https://example.test/agent", source));
        assertThat(context("http://[::1]:7778/", source)).isEqualTo(context("http://[::1]:7778", source));
        assertThat(context("http://localhost:7778", source + "/missing/../checkout"))
                .isEqualTo(context("http://localhost:7778", source + "/checkout"));
        assertThat(context("http://localhost:7778/agent%2Fprivate", source))
                .isNotEqualTo(context("http://localhost:7778/agent/private", source));
    }
    @Test void contextIsOpaqueAndChangesOnlyForActualMappings() {
        JsonNode base = context("http://localhost:7778/private-agent", temp.toString());
        assertThat(base.path("namespace").asText()).matches("[0-9a-f]{64}");
        assertThat(base.toString()).doesNotContain("private-agent", temp.toString());
        assertThat(base.path("services").get(0).asText()).isEqualTo("local");
        assertThat(base).isNotEqualTo(context("http://localhost:7779/private-agent", temp.toString()));
        assertThat(base).isNotEqualTo(context("http://localhost:7778/private-agent", temp.resolve("other").toString()));
    }
    @Test void federatedOrderIsStableAndGlobalDefaultsAreIgnored() {
        Service app = new Service("app", "http://localhost:7778", temp.resolve("app").toString());
        Service ems = new Service("ems", "http://localhost:7779", null);
        ToolConfig config = new ToolConfig();
        config.federated = true;
        config.services = Arrays.asList(ems, app);
        JsonNode first = ViewerServer.viewerContext(config);
        config.services = Arrays.asList(app, ems);
        config.upstream = "http://unused.invalid:1";
        config.sourceRoot = temp.resolve("unused").toString();
        assertThat(ViewerServer.viewerContext(config)).isEqualTo(first);
        config.services = Arrays.asList(app);
        assertThat(ViewerServer.viewerContext(config)).isNotEqualTo(first);
    }

    @Test void managedNamespaceKeepsSavedCapturesVisibleWhenAddingServices() throws Exception {
        Path path = temp.resolve("tracelens.json");
        String firstConfig = "{\"applicationId\":\"cb-app\",\"viewer\":{\"services\":[{\"name\":\"cb-app\",\"url\":\"http://localhost:7778\"}]}}";
        Files.write(path, firstConfig.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JsonNode first = ViewerServer.viewerContext(ToolConfig.fromArgs(new String[]{"--config", path.toString()}));
        String namespace = first.path("namespace").asText();
        Path before = ViewerSnapshotStore.namespaceRoot(temp.resolve("saved"), namespace);
        com.fasterxml.jackson.databind.node.ObjectNode document = (com.fasterxml.jackson.databind.node.ObjectNode) Json.parse(firstConfig);
        com.fasterxml.jackson.databind.node.ObjectNode viewer = (com.fasterxml.jackson.databind.node.ObjectNode) document.get("viewer");
        viewer.put("namespace", namespace);
        ((com.fasterxml.jackson.databind.node.ArrayNode) viewer.get("services"))
                .add(Json.obj("name", "cb-ems", "url", "http://localhost:7779"));
        Files.write(path, Json.stringify(document).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JsonNode updated = ViewerServer.viewerContext(ToolConfig.fromArgs(new String[]{"--config", path.toString()}));
        assertThat(updated.path("namespace").asText()).isEqualTo(namespace);
        assertThat(updated.path("services")).isEqualTo(Json.arr("cb-app", "cb-ems"));
        assertThat(ViewerSnapshotStore.namespaceRoot(temp.resolve("saved"), updated.path("namespace").asText()))
                .isEqualTo(before);
    }

    @Test void managedNamespaceRejectsInvalidAndPathLikeValues() throws Exception {
        Path path = temp.resolve("invalid.json");
        for (JsonNode value : Json.arr(null, 1, true, "", "../other", "A".repeat(64), "a".repeat(63), "g".repeat(64))) {
            Files.write(path, Json.stringify(Json.obj("viewer", Json.obj("namespace", value))).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThatThrownBy(() -> ToolConfig.fromArgs(new String[]{"--config", path.toString()}))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        ToolConfig config = new ToolConfig();
        config.viewerNamespace = "../other";
        assertThatThrownBy(() -> ViewerServer.viewerContext(config)).isInstanceOf(IllegalArgumentException.class);
    }
}
