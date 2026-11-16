package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ViewerServerTest {
    @TempDir Path temp;
    private HttpServer upstream;
    private ViewerServer viewer;
    private String origin;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private ObjectNode trace;
    private String response;

    @BeforeEach void setup() throws Exception {
        temp = temp.toRealPath();
        Path source = temp.resolve("src/main/java/sample/Test.java");
        Files.createDirectories(source.getParent());
        Files.write(source, "package sample;\nclass Test {}\n".getBytes(StandardCharsets.UTF_8));
        trace = Json.obj("segmentId", "11111111-1111-1111-1111-111111111111",
                "traceId", "0123456789abcdef0123456789abcdef", "serviceInstanceId", "instance-1",
                "entryChannel", "HTTP", "httpMethod", "GET", "path", "/features", "status", "COMPLETED",
                "httpStatus", 200, "events", Json.arr(Json.obj("seq", 1, "type", "METHOD_ENTER",
                        "className", "sample.Test", "methodName", "read", "thread", "worker", "nanoTime", 1),
                        Json.obj("seq", 2, "type", "LINE", "className", "sample.Test", "line", 2,
                                "thread", "worker", "nanoTime", 2)));
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/tracelens", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            String path = exchange.getRequestURI().getPath();
            JsonNode doc = path.endsWith("/summary") ? Json.obj("recordingEnabled", true) :
                    path.endsWith("/traces") ? Json.obj("traces", Json.arr()) :
                    path.contains("/export/") || path.contains("/request-data/") ? trace : Json.obj("ok", true);
            byte[] bytes = Json.stringify(doc).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        upstream.start();
        ToolConfig config = ToolConfig.fromArgs(new String[]{"--url",
                "http://127.0.0.1:" + upstream.getAddress().getPort(), "--source-root", temp.toString(),
                "--snapshot-dir", temp.resolve("snapshots").toString(), "--capture-dir", temp.resolve("captures").toString(),
                "serve", "--port", "0"});
        viewer = new ViewerServer(config);
        viewer.start();
        origin = "http://127.0.0.1:" + viewer.port();
    }
    @AfterEach void close() {
        if (viewer != null) viewer.close();
        if (upstream != null) upstream.stop(0);
    }
    private int request(String method, String path, String originHeader, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(5));
        if (originHeader != null) request.header("Origin", originHeader);
        if (body != null) {
            request.header("Content-Type", "application/json");
            request.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else request.method(method, HttpRequest.BodyPublishers.noBody());
        HttpResponse<String> result = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
        response = result.body();
        assertThat(result.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(result.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        return result.statusCode();
    }
    @Test void servesTheExistingUiAndOnlyBundledAssets() throws Exception {
        assertThat(request("GET", "/", null, null)).isEqualTo(200);
        assertThat(response).contains("TraceLens", "/static/app.js");
        assertThat(request("GET", "/static/trace-graph.js", null, null)).isEqualTo(200);
        assertThat(response).contains("TraceLensGraph");
        assertThat(request("GET", "/static/sql-values.js", null, null)).isEqualTo(200);
        assertThat(response).contains("TraceLensSqlValues");
        assertThat(request("GET", "/static/performance-model.js", null, null)).isEqualTo(200);
        assertThat(response).contains("TraceLensPerformanceModel");
        assertThat(request("GET", "/static/performance-ui.js", null, null)).isEqualTo(200);
        assertThat(response).contains("TraceLensPerformanceUI");
        assertThat(request("GET", "/static/performance.css", null, null)).isEqualTo(200);
        assertThat(response).contains(".performance-insights");
        assertThat(request("GET", "/static/../ToolMain.class", null, null)).isEqualTo(404);
        assertThat(requests).isEmpty();
    }
    @Test void exportsWithoutEvictionAndEnrichesCurrentSource() throws Exception {
        assertThat(request("GET", "/api/render-data/" + trace.path("segmentId").asText(), null, null)).isEqualTo(200);
        assertThat(Json.parse(response).path("events").get(1).path("sourceLine").asText()).isEqualTo("class Test {}");
        assertThat(requests).containsExactly("GET /tracelens/export/" + trace.path("segmentId").asText() + "?evict=false");
        assertThat(request("GET", "/api/source-location?className=sample.Test&line=2", null, null)).isEqualTo(200);
        assertThat(Json.parse(response).path("sourceCorrespondence").asText()).isEqualTo("unverified");
        assertThat(request("GET", "/api/source-location?className=sample.Test&line=3", null, null)).isEqualTo(404);
        assertThat(request("GET", "/api/source-location?className=..%2Fsecret&line=1", null, null)).isEqualTo(404);
    }
    @Test void rejectsCrossOriginReadsAndUnconfirmedWrites() throws Exception {
        assertThat(request("GET", "/api/summary", "https://untrusted.example", null)).isEqualTo(403);
        assertThat(request("POST", "/api/reset", null, null)).isEqualTo(403);
        assertThat(request("POST", "/api/reset", "http://localhost:" + viewer.port(), null)).isEqualTo(403);
        assertThat(requests).isEmpty();
        assertThat(request("POST", "/api/reset", origin, null)).isEqualTo(200);
        assertThat(requests).containsExactly("POST /tracelens/reset");
    }
    @Test void executionModelIsOfflineAndStrictlyValidated() throws Exception {
        assertThat(request("POST", "/api/segment-execution", origin, Json.stringify(Json.obj("trace", trace)))).isEqualTo(200);
        assertThat(Json.parse(response).path("kind").asText()).isEqualTo("segment-execution");
        assertThat(requests).isEmpty();
        assertThat(request("POST", "/api/segment-execution", origin, "{\"trace\":{},\"trace\":{}}")).isEqualTo(400);
        assertThat(response).doesNotContain("JsonParseException");
        assertThat(request("POST", "/api/segment-execution", origin, "{\"trace\":{},\"maxNodes\":\"secret\"}")).isEqualTo(400);
        assertThat(request("POST", "/api/capture-tools/project", origin,
                "{\"snapshotId\":\"bad\",\"label\":123}")).isEqualTo(400);
        assertThat(requests).isEmpty();
        assertThat(Files.exists(temp.resolve("captures"))).isFalse();
    }
    @Test void requestSearchIsExplicitLocalPostAndReturnsOnlyMatchingIdentity() throws Exception {
        trace.put("applicationId", "cb-app");
        trace.set("attributes", Json.obj("httpQueryParametersStatus", "captured", "httpQueryParameters", "{\"name\":[\"Xero-integration\"]}",
                "httpRequestBodyStatus", "captured", "httpRequestBody", "{\"feature\":\"Xero-integration\"}", "httpRequestBodyFormat", "json"));
        ObjectNode identity = Json.obj("service", "cb-app", "serviceInstanceId", trace.get("serviceInstanceId"),
                "segmentId", trace.get("segmentId"), "traceId", trace.get("traceId"));
        String body = Json.stringify(Json.obj("keyword", "xero-INTEGRATION", "segments", Json.arr(identity)));
        assertThat(request("POST", "/api/request-search", null, body)).isEqualTo(403);
        assertThat(request("POST", "/api/request-search?keyword=PRIVATE", origin, body)).isEqualTo(400);
        assertThat(request("GET", "/api/request-search", null, null)).isEqualTo(404);
        assertThat(requests).isEmpty();
        assertThat(request("POST", "/api/request-search", origin, body)).isEqualTo(200);
        JsonNode result = Json.parse(response);
        assertThat(result.path("kind").asText()).isEqualTo("request-data-search");
        assertThat(result.path("matches")).isEqualTo(Json.arr(identity.deepCopy().set("fields", Json.arr("query", "body"))));
        assertThat(result.path("counts").path("withData").asInt()).isEqualTo(1);
        assertThat(response).doesNotContain("Xero", "xero", "feature", "httpRequestBody", "PRIVATE");
        assertThat(requests).containsExactly("GET /tracelens/request-data/" + trace.path("segmentId").asText());
    }
    @Test void requestSearchRejectsMalformedBodiesBeforeAnyRead() throws Exception {
        for (String body : new String[]{"{}", "[]", "{\"keyword\":\"PRIVATE\",\"segments\":[],\"url\":\"https://private.invalid\"}",
                "{\"keyword\":\"PRIVATE\",\"keyword\":\"second\",\"segments\":[]}",
                "{\"keyword\":\"PRIVATE\",\"segments\":[{\"service\":\"local\",\"segmentId\":\"../secret\",\"serviceInstanceId\":\"instance-1\"}]}"}) {
            assertThat(request("POST", "/api/request-search", origin, body)).isEqualTo(400);
            assertThat(response).doesNotContain("PRIVATE", "private.invalid");
        }
        assertThat(requests).isEmpty();
    }
    @Test void savedSnapshotLifecycleIsOfflineAndNameOnly() throws Exception {
        ObjectNode snapshot = CaptureWorkflowTest.snapshotInput();
        assertThat(request("POST", "/api/snapshots", origin, Json.stringify(Json.obj("snapshot", snapshot)))).isEqualTo(201);
        ObjectNode saved = (ObjectNode) Json.parse(response);
        String id = saved.path("id").asText();
        assertThat(request("GET", "/api/snapshots", null, null)).isEqualTo(200);
        assertThat(Json.parse(response).path("count").asInt()).isEqualTo(1);
        assertThat(request("POST", "/api/snapshots/" + id + "/rename", origin, "{\"name\":\"Renamed\"}")).isEqualTo(200);
        assertThat(request("GET", "/api/snapshots/" + id, null, null)).isEqualTo(200);
        ObjectNode renamed = (ObjectNode) Json.parse(response);
        assertThat(renamed.path("name").asText()).isEqualTo("Renamed");
        renamed.put("name", saved.path("name").asText());
        assertThat(renamed).isEqualTo(saved);
        assertThat(request("DELETE", "/api/snapshots/" + id, origin, null)).isEqualTo(200);
        assertThat(request("GET", "/api/snapshots", null, null)).isEqualTo(200);
        assertThat(Json.parse(response).path("count").asInt()).isZero();
        assertThat(requests).isEmpty();
    }
    @Test void sourceLookupCannotEscapeCheckoutThroughSymlink() throws Exception {
        Path outside = Files.createTempFile("tracelens-source-test", ".java");
        try {
            Files.write(outside, "secret".getBytes(StandardCharsets.UTF_8));
            Files.createSymbolicLink(temp.resolve("src/main/java/sample/Secret.java"), outside);
            assertThat(new Source(temp).location("sample.Secret", 1)).isNull();
        } finally { Files.delete(outside); }
    }
    @Test void jsonParserRejectsNonfiniteDuplicateTrailingAndInvalidUtf8() {
        for (String doc : new String[]{"{\"a\":1,\"a\":2}", "{} {}", "{\"a\":NaN}", "{\"a\":1e1000}"})
            assertThatThrownBy(() -> Json.parse(doc)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Json.parse(new byte[]{(byte) 0xc3, 0x28})).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void combinedConfigUsesOneJsonAndRespectsPositionalSeparator() throws Exception {
        Path settings = temp.resolve("tracelens.json");
        Files.write(settings, Json.stringify(Json.obj("applicationId", "cb-app", "enabled", true,
                "viewer", Json.obj("port", 7785, "sourceRoot", temp.toString(),
                        "upstream", "http://127.0.0.1:7778"))).getBytes(StandardCharsets.UTF_8));
        ToolConfig config = ToolConfig.fromArgs(new String[]{"--config", settings.toString(), "--port", "7786", "serve"});
        assertThat(config.port).isEqualTo(7786);
        assertThat(config.services.get(0).name).isEqualTo("cb-app");
        assertThat(config.services.get(0).sourceRoot).isEqualTo(temp.toString());
        assertThat(config.remaining).containsExactly("serve");
        assertThat(ToolConfig.fromArgs(new String[]{"find", "--", "--url"}).remaining).containsExactly("find", "--", "--url");
    }
    @ParameterizedTest @ValueSource(strings = {"category=unknown", "http_status=503.1", "http_status=true",
            "http_status=99", "since_minutes=NaN", "since_minutes=inf", "since_minutes=-1",
            "min_duration=100&max_duration=10", "service=app&service=ems", "upstream=http://private.invalid"})
    void invalidErrorFiltersFailBeforeAnyAgentRead(String query) throws Exception {
        assertThat(request("GET", "/api/error-groups?" + query, null, null)).isEqualTo(400);
        assertThat(requests).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings = {"", "path=%2Fapi&unexpected=x", "path=%2Fapi&path=%2Fother",
            "path=https%3A%2F%2Fevil.invalid", "path=%2Fapi%3Ftoken%3Dsecret", "path=%2Fapi&method=GET%20%2F",
            "path=%2Fapi&service=missing", "path=%2Fapi&user=%0A"})
    void invalidDiagnosisFiltersFailBeforeAnyAgentRead(String query) throws Exception {
        assertThat(request("GET", "/api/guided-investigation?" + query, null, null)).isEqualTo(400);
        assertThat(requests).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings = {"%2Fetc%2Fpasswd", "https%3A%2F%2Funtrusted", "private%20label"})
    void graphTraceValidationPrecedesIo(String id) throws Exception {
        assertThat(request("GET", "/api/trace-visualization/" + id, null, null)).isEqualTo(400);
        assertThat(requests).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings = {"null", "[]", "\"PRIVATE\"", "{}", "{\"trace\":[]}",
            "{\"trace\":null}", "{\"trace\":{},\"upstream\":\"http://private.invalid\"}",
            "{\"trace\":{},\"maxNodes\":null}", "{\"trace\":{},\"maxNodes\":true}",
            "{\"trace\":{},\"maxNodes\":0}", "{\"trace\":{},\"maxNodes\":501}",
            "{\"trace\":{},\"maxNodes\":1.5}", "{\"trace\":{},\"maxNodes\":\"2\"}",
            "{\"trace\":{},\"maxModelBytes\":1}", "{} {}"})
    void invalidExecutionBodiesFailWithoutLiveFallback(String body) throws Exception {
        assertThat(request("POST", "/api/segment-execution", origin, body)).isEqualTo(400);
        assertThat(response).doesNotContain("PRIVATE", "private.invalid");
        assertThat(requests).isEmpty();
    }
    @Test void savedFilesRemainAvailableAfterViewerRestartWithoutReadingAgents() throws Exception {
        assertThat(request("POST", "/api/snapshots", origin,
                Json.stringify(Json.obj("snapshot", CaptureWorkflowTest.snapshotInput())))).isEqualTo(201);
        JsonNode saved = Json.parse(response);
        viewer.close();
        ToolConfig config = ToolConfig.fromArgs(new String[]{"--url",
                "http://127.0.0.1:" + upstream.getAddress().getPort(), "--source-root", temp.toString(),
                "--snapshot-dir", temp.resolve("snapshots").toString(), "--capture-dir", temp.resolve("captures").toString(),
                "serve", "--port", "0"});
        viewer = new ViewerServer(config);
        viewer.start();
        origin = "http://127.0.0.1:" + viewer.port();
        assertThat(request("GET", "/api/snapshots/" + saved.path("id").asText(), null, null)).isEqualTo(200);
        assertThat(Json.parse(response)).isEqualTo(saved);
        assertThat(requests).isEmpty();
    }
    @Test void offlineCaptureToolsProjectSearchCompareAndMissingIdDoNotUseAgents() throws Exception {
        assertThat(request("POST", "/api/snapshots", origin,
                Json.stringify(Json.obj("snapshot", CaptureWorkflowTest.snapshotInput())))).isEqualTo(201);
        String id = Json.parse(response).path("id").asText();
        assertThat(request("POST", "/api/capture-tools/project", origin,
                Json.stringify(Json.obj("snapshotId", id, "label", "parity fixture", "revision", "test-revision")))).isEqualTo(200);
        assertThat(request("GET", "/api/capture-tools", null, null)).isEqualTo(200);
        assertThat(Json.parse(response).path("captures")).hasSize(1);
        assertThat(Json.parse(response).path("snapshots")).hasSize(1);
        assertThat(request("POST", "/api/capture-tools/compare", origin,
                Json.stringify(Json.obj("before", id, "after", id)))).isEqualTo(200);
        assertThat(Json.parse(response).path("kind").asText()).isNotEmpty();
        assertThat(request("POST", "/api/capture-tools/search", origin,
                Json.stringify(Json.obj("query", "parity")))).isEqualTo(200);
        assertThat(request("POST", "/api/capture-tools/project", origin,
                "{\"snapshotId\":\"00000000-1111-2222-3333-444444444444\"}")).isBetween(400, 499);
        assertThat(requests).isEmpty();
    }
}
