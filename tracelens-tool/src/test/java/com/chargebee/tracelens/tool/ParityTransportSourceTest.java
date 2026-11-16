package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** Ports the Python client/source regression contracts using real local HTTP. */
class ParityTransportSourceTest {
    @TempDir Path temp;
    HttpServer upstream;
    DebugClient client;
    List<String> calls = new ArrayList<>();
    byte[] payload = "{\"traces\":[]}".getBytes(StandardCharsets.UTF_8);
    int status = 200;
    boolean chunked;

    @BeforeEach void start() throws Exception {
        temp = temp.toRealPath();
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            exchange.getResponseHeaders().set("Location", "/redirect-target");
            exchange.sendResponseHeaders(status, chunked ? 0 : payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        upstream.start();
        client = new DebugClient("http://127.0.0.1:" + upstream.getAddress().getPort());
    }
    @AfterEach void stop() { upstream.stop(0); }
    private Path source(String sourceSet, String text) throws Exception {
        Path path = temp.resolve(sourceSet).resolve("com/example/Handler.java");
        Files.createDirectories(path.getParent());
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    @Test void labelsPreserveSlashesButEncodeQueryFragmentAndUnicode() throws Exception {
        String label = "auto:GET /api/v2/features/user-licenses/reference_counts?q=a+b%20#café";
        client.findByLensId(label);
        URI uri = URI.create(calls.get(0).substring(4));
        assertThat(uri.getRawQuery()).isNull();
        assertThat(uri.getRawFragment()).isNull();
        assertThat(uri.getRawPath()).doesNotContain(" ", "%2F");
        assertThat(URLDecoder.decode(uri.getRawPath().substring("/tracelens/find-by-lens-id/".length()), "UTF-8"))
                .isEqualTo(label);
    }
    @Test void readsDoNotEvictAndMutationsAreExplicitPosts() {
        client.export("id");
        client.findByTraceId("trace");
        client.summary(); client.metadata(); client.instrumentedClasses();
        client.enable(); client.disable(); client.reset(null); client.reset("id");
        assertThat(calls).containsExactly("GET /tracelens/export/id?evict=false",
                "GET /tracelens/find-by-trace-id/trace", "GET /tracelens/summary",
                "GET /tracelens/metadata", "GET /tracelens/instrumented-classes",
                "POST /tracelens/enable", "POST /tracelens/disable",
                "POST /tracelens/reset", "POST /tracelens/reset/id");
    }
    @ParameterizedTest @ValueSource(ints = {302, 403, 500})
    void rejectsRedirectsAndHttpFailuresWithoutEchoingPrivateBodies(int code) {
        status = code;
        payload = "PRIVATE_ERROR_BODY".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(client::traces).isInstanceOf(ToolException.class)
                .satisfies(error -> assertThat(((ToolException) error).status).isEqualTo(code))
                .hasMessageNotContaining("PRIVATE");
        assertThat(calls).hasSize(1);
    }
    @ParameterizedTest @ValueSource(strings = {"[]", "{}", "{\"traces\":{}}", "{\"traces\":[null]}",
            "PRIVATE_NOT_JSON", "{\"traces\":[],\"x\":NaN}", "{\"traces\":[],\"traces\":[]}", "{} {}"})
    void invalidSummaryDocumentsFailSafely(String body) {
        payload = body.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(client::traces).isInstanceOf(ToolException.class).hasMessageNotContaining("PRIVATE");
    }
    @Test void invalidUtf8IsNotSilentlyReplaced() {
        payload = new byte[]{(byte) 0xff};
        assertThatThrownBy(client::traces).isInstanceOf(ToolException.class);
    }
    @Test void diagnosticFailureKindsDistinguishBadEvidenceFromHttpAndNetworkFailures() {
        payload = "PRIVATE_INVALID_JSON".getBytes(StandardCharsets.UTF_8);
        assertThat(Doctor.probe(client, "summary").path("error"))
                .isEqualTo(Json.obj("kind", "malformed", "httpStatus", null));
        status = 502;
        assertThat(Doctor.probe(client, "summary").path("error"))
                .isEqualTo(Json.obj("kind", "http", "httpStatus", 502));
        upstream.stop(0);
        assertThat(Doctor.probe(client, "summary").path("error"))
                .isEqualTo(Json.obj("kind", "unreachable", "httpStatus", null));
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void overflowIsBoundedWithOrWithoutContentLength(boolean streaming) {
        chunked = streaming;
        payload = new byte[4096];
        assertThatThrownBy(() -> client.traces(16)).isInstanceOf(ToolException.class).hasMessageContaining("byte limit");
        assertThat(client.lastResponseBytes()).isLessThanOrEqualTo(17);
    }
    @ParameterizedTest @ValueSource(ints = {0, -1, 1048577})
    void invalidSummaryBudgetFailsBeforeNetwork(int limit) {
        assertThatThrownBy(() -> client.traces(limit)).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).isEmpty();
    }
    @Test void boundedReaderAcceptsExactSizeRejectsOverflowAndExpiredDeadline() throws Exception {
        assertThat(DebugClient.readBounded(new ByteArrayInputStream(new byte[8]), 8, Long.MAX_VALUE)).hasSize(8);
        assertThatThrownBy(() -> DebugClient.readBounded(new ByteArrayInputStream(new byte[9]), 8, Long.MAX_VALUE))
                .isInstanceOf(ToolException.class);
        assertThatThrownBy(() -> DebugClient.readBounded(new ByteArrayInputStream(new byte[8]), 8, 0))
                .isInstanceOf(ToolException.class);
    }
    @ParameterizedTest @ValueSource(strings = {"src/main/java", "src", "app-module/src",
            "framework-module/src", "service-module/src/main/java", ""})
    void resolvesGradleAntModulesAndDirectSourceRoot(String layout) throws Exception {
        source(layout, "package com.example;\nclass Handler {}\n");
        Source lookup = new Source(temp);
        assertThat(lookup.sourceLine("com.example.Handler", 2)).isEqualTo("class Handler {}");
        assertThat(lookup.sourceLine("com.example.Handler", 3)).isNull();
    }
    @ParameterizedTest @ValueSource(strings = {"Handler", "Handler.Inner", "Handler$Inner", "Handler$1", "Handler$Inner$Nested"})
    void resolvesInnerClasses(String name) throws Exception {
        source("src", "  class Handler {}  \r\n");
        assertThat(new Source(temp).sourceLine("com.example." + name, 1)).isEqualTo("class Handler {}");
    }
    @ParameterizedTest @ValueSource(strings = {"../secret", "/etc/passwd", "com/foo", "a..B", "x?", "Missing"})
    void invalidCoordinatesNeverReadUnrelatedFiles(String name) throws Exception {
        source("src", "class Handler {}");
        Source lookup = new Source(temp);
        assertThat(lookup.sourceLine(name, 1)).isNull();
        assertThat(lookup.location(name, 1)).isNull();
    }
    @Test void sourceRefreshesAndEnrichmentDoesNotMutateHistoricalEvidence() throws Exception {
        Path file = source("src", "class Handler {}\n");
        Source lookup = new Source(temp);
        JsonNode trace = Json.obj("events", Json.arr(Json.obj("type", "LINE", "className", "com.example.Handler", "line", 1)));
        assertThat(lookup.enrich(trace).path("events").get(0).path("sourceLine").asText()).isEqualTo("class Handler {}");
        assertThat(trace.path("events").get(0).has("sourceLine")).isFalse();
        Files.write(file, "class Updated {}\r".getBytes(StandardCharsets.UTF_8));
        assertThat(lookup.sourceLine("com.example.Handler", 1)).isEqualTo("class Updated {}");
        JsonNode location = lookup.location("com.example.Handler$Inner", 1);
        assertThat(location.path("sourceCorrespondence").asText()).isEqualTo("unverified");
        assertThat(location.path("editors").path("cursor").asText()).startsWith("cursor://file/").endsWith(":1");
        assertThat(location.has("sourceLine")).isFalse();
        JsonNode oversizedLine = Json.obj("events", Json.arr(Json.obj("type", "LINE",
                "className", "com.example.Handler", "line", 4294967297L)));
        assertThat(lookup.enrich(oversizedLine).path("events").get(0).has("sourceLine")).isFalse();
    }
    @Test void sourceCannotReadThroughSymlinksOrOversizedFiles() throws Exception {
        Path outside = temp.resolve("outside/Secret.java");
        Files.createDirectories(outside.getParent());
        Files.write(outside, "PRIVATE".getBytes(StandardCharsets.UTF_8));
        Path checkout = Files.createDirectory(temp.resolve("checkout"));
        Files.createSymbolicLink(checkout.resolve("Secret.java"), outside);
        assertThat(new Source(checkout).sourceLine("Secret", 1)).isNull();
        assertThat(new Source(checkout).location("Secret", 1)).isNull();
        Path large = source("src", "");
        Files.write(large, new byte[4 * 1024 * 1024 + 1]);
        assertThat(new Source(temp).sourceLine("com.example.Handler", 1)).isNull();
    }
}
