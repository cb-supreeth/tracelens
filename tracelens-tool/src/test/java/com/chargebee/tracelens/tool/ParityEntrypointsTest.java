package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

/** Behavioral ports of Python test_render/test_mcp_server and CLI entrypoint cases.
 * The renderer golden was obtained from tracelens_cli.render.render, not from Java.
 * Local fake agents record every request; no installed agent or user store is used.
 */
class ParityEntrypointsTest {
    private static final String SEGMENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String SECOND = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String TRACE = "0123456789abcdef0123456789abcdef";
    @TempDir Path temporary;
    private HttpServer upstream;
    private String url;
    private Path root;
    private ObjectNode trace;
    private ArrayNode rows;
    private McpServer mcp;
    private final List<String> reads = new CopyOnWriteArrayList<>();
    private final AtomicInteger exportStatus = new AtomicInteger(200);

    @BeforeEach void setup() throws Exception {
        root = temporary.toRealPath();
        JsonNode golden = golden();
        trace = Json.copy(golden.path("trace"));
        trace.put("traceId", TRACE).put("segmentId", SEGMENT).put("spanId", "1111111111111111")
                .put("serviceInstanceId", "instance-1").put("httpMethod", "POST").put("path", "/features")
                .put("actionId", "action-1").put("actionName", "Save Feature")
                .put("startedAtMillis", System.currentTimeMillis()).put("lastActivityMillis", System.currentTimeMillis());
        trace.set("httpCalls", Json.arr());
        ObjectNode summary = trace.deepCopy(); summary.remove("events");
        rows = Json.arr(summary);
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            reads.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/second/")) path = path.substring("/second".length());
            JsonNode response = Json.obj("ok", true);
            int status = 200;
            if (path.equals("/tracelens/summary"))
                response = Json.obj("recordingEnabled", true, "agentActive", true, "traceCount", rows.size(),
                        "instrumentedClasses", 42, "maxTrackedRequests", 200, "totalEvents", 8,
                        "maxTrackedEvents", 500000, "bridge", Json.obj("emitted", 8, "droppedNoTrace", 0));
            else if (path.equals("/tracelens/metadata"))
                response = Json.obj("agentActive", true, "recordingEnabled", true, "config", Json.obj("enabled", true));
            else if (path.startsWith("/tracelens/export/")) {
                response = trace; status = exportStatus.get();
            } else if (path.startsWith("/tracelens/find-by-lens-id/")) {
                String label = path.substring("/tracelens/find-by-lens-id/".length());
                ArrayNode matches = Json.arr();
                for (JsonNode row : rows) if (label.equals(Json.text(row, "lensId"))) matches.add(row);
                response = Json.obj("traces", matches);
            } else if (path.equals("/tracelens/traces") || path.startsWith("/tracelens/find-by-trace-id/"))
                response = Json.obj("traces", rows);
            byte[] data = Json.stringify(response).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        });
        upstream.start();
        url = "http://127.0.0.1:" + upstream.getAddress().getPort();
        mcp = new McpServer(config());
    }

    @AfterEach void close() { upstream.stop(0); }

    private ToolConfig config() {
        return ToolConfig.fromArgs(new String[] {"--url", url, "--source-root", root.toString(),
                "--capture-dir", root.resolve("captures").toString(), "mcp"});
    }
    private static JsonNode golden() throws Exception {
        try (InputStream input = ParityEntrypointsTest.class.getResourceAsStream("/parity-entrypoints-render.json")) {
            return Json.MAPPER.readTree(input);
        }
    }
    private Result cli(String... command) throws Exception {
        List<String> args = new ArrayList<>(Arrays.asList("--url", url, "--source-root", root.toString(),
                "--capture-dir", root.resolve("captures").toString()));
        args.addAll(Arrays.asList(command));
        ByteArrayOutputStream output = new ByteArrayOutputStream(), error = new ByteArrayOutputStream();
        int code = ToolMain.run(args.toArray(new String[0]), new PrintStream(output, true, "UTF-8"),
                new PrintStream(error, true, "UTF-8"));
        return new Result(code, output.toString("UTF-8"), error.toString("UTF-8"));
    }
    private static final class Result {
        final int code; final String out, err;
        Result(int code, String out, String err) { this.code = code; this.out = out; this.err = err; }
    }
    private JsonNode call(String tool, ObjectNode arguments) { return mcp.dispatchTool(tool, arguments); }
    private static String text(JsonNode result) { return result.path("content").path(0).path("text").asText(); }

    @Test void rendererMatchesPythonGoldenIncludingNullableRepeatFields() throws Exception {
        JsonNode fixture = golden();
        assertThat(Renderer.render(fixture.path("trace"), null, false, true)).isEqualTo(fixture.path("rendered").asText());
        String expanded = Renderer.render(fixture.path("trace"), null, false, false);
        assertThat(expanded).doesNotContain("(x2)").contains("#3", "http-exec-3", "thread: worker");
        assertThat(expanded.split("Flow:2", -1)).hasSize(3);
    }

    @Test void rendererSourceWidthCountsUnicodeCodePointsWithoutBreakingSurrogates() throws Exception {
        Path source = root.resolve("src/main/java/sample/Flow.java");
        Files.createDirectories(source.getParent());
        String content = "// " + String.join("", java.util.Collections.nCopies(100, "😀"));
        Files.write(source, Arrays.asList("package sample;", content), StandardCharsets.UTF_8);
        String rendered = Renderer.render(trace, root.toString(), true, true);
        String line = Arrays.stream(rendered.split("\n")).filter(value -> value.contains("│")).findFirst().orElseThrow();
        String shown = line.substring(line.indexOf("│ ") + 2);
        assertThat(shown.codePointCount(0, shown.length())).isEqualTo(96);
        assertThat(shown).endsWith("😀…");
        assertThat(Renderer.render(trace, root.toString(), false, true)).doesNotContain("│");
    }

    @Test void rendererRetainsSeparateThreadRunsAndHandlesUnmatchedExit() {
        ObjectNode simple = Json.obj("events", Json.arr(
                Json.obj("seq", 1, "type", "METHOD_EXIT", "className", "sample.Flow", "methodName", "close", "thread", "one"),
                Json.obj("seq", 2, "type", "LINE", "className", "sample.Flow", "line", 5, "thread", "one"),
                Json.obj("seq", 3, "type", "LINE", "className", "sample.Flow", "line", 5, "thread", "two")));
        String rendered = Renderer.render(simple, null, false, true);
        assertThat(rendered).contains("◀ Flow.close", "thread: one", "thread: two").doesNotContain("(x2)");
    }

    static Stream<Arguments> readCommands() {
        return Stream.of(
                Arguments.of(new String[] {"summary"}, "/tracelens/summary", "recordingEnabled"),
                Arguments.of(new String[] {"list"}, "/tracelens/traces", TRACE),
                Arguments.of(new String[] {"find", "flow"}, "/tracelens/find-by-lens-id/flow", TRACE),
                Arguments.of(new String[] {"metadata"}, "/tracelens/metadata", "agentActive"),
                Arguments.of(new String[] {"export", SEGMENT}, "/tracelens/export/" + SEGMENT + "?evict=false", "events"),
                Arguments.of(new String[] {"render", SEGMENT, "--no-source"}, "/tracelens/export/" + SEGMENT + "?evict=false", "Flow.run"));
    }

    @ParameterizedTest @MethodSource("readCommands")
    void cliReadCommandsUseExpectedNonEvictingRoutes(String[] command, String route, String expected) throws Exception {
        Result result = cli(command);
        assertThat(result.code).as(result.err).isZero();
        assertThat(result.out).contains(expected);
        assertThat(result.err).isEmpty();
        assertThat(reads).containsExactly("GET " + route);
    }

    static Stream<Arguments> mutations() {
        return Stream.of(Arguments.of(new String[] {"enable"}, "/enable"),
                Arguments.of(new String[] {"disable"}, "/disable"),
                Arguments.of(new String[] {"reset"}, "/reset"),
                Arguments.of(new String[] {"reset", SEGMENT}, "/reset/" + SEGMENT));
    }

    @ParameterizedTest @MethodSource("mutations")
    void onlyExplicitSingleServiceMutationsIssuePosts(String[] command, String route) throws Exception {
        assertThat(cli(command).code).isZero();
        assertThat(reads).containsExactly("POST /tracelens" + route);
    }

    @Test void cliFullFlagChangesOnlyRepeatPresentation() throws Exception {
        Result compact = cli("render", SEGMENT, "--no-source");
        Result full = cli("render", SEGMENT, "--no-source", "--full");
        assertThat(compact.out).contains("(x2)");
        assertThat(full.out).doesNotContain("(x2)").contains("#3");
        assertThat(cli("render", SEGMENT, "--full=false").code).isEqualTo(1);
        assertThat(reads).hasSize(2);
    }

    @Test void cliLiteralHelpAndVersionLabelsAreNotCommands() throws Exception {
        for (String label : Arrays.asList("--help", "--version")) {
            ((ObjectNode) rows.get(0)).put("lensId", label);
            Result result = cli("find", "--", label);
            assertThat(result.code).as(result.err).isZero();
            assertThat(result.out).contains(TRACE, label).doesNotContain("Usage: java", "tracelens " + ToolMain.VERSION);
        }
        assertThat(reads).containsExactly("GET /tracelens/find-by-lens-id/--help", "GET /tracelens/find-by-lens-id/--version");
    }

    @Test void cliRejectsBadCommandsAndFiltersBeforeReadingAgents() throws Exception {
        for (String[] args : new String[][] {{"unknown"}, {"summary", "extra"}, {"list", "--unknown", "x"},
                {"render"}, {"find-distributed", "--since-minutes", "NaN"}, {"tail", "--interval", "0"},
                {"diagnose"}, {"diagnose", "--path", "https://example.com/path"},
                {"capture", "search", "term", "--limit", "0"}, {"capture", "search", "term", "--category", "payload"}}) {
            Result result = cli(args);
            assertThat(result.code).as(Arrays.toString(args)).isEqualTo(1);
            assertThat(result.err).startsWith("error:");
        }
        assertThat(reads).isEmpty();
        assertThat(root.resolve("captures")).doesNotExist();
    }

    @Test void cliOfflineCaptureListAndSearchDoNotCreateStoreOrContactUpstream() throws Exception {
        assertThat(cli("capture", "list").code).isZero();
        assertThat(cli("capture", "search", "Flow").code).isZero();
        assertThat(reads).isEmpty();
        assertThat(root.resolve("captures")).doesNotExist();
    }

    @Test void cliDiscoveryAndInvestigationKeepExitStatusesAndSelectionSemantics() throws Exception {
        Result found = cli("find-distributed", "--path", "/features", "--method", "POST", "--since-minutes", "30");
        assertThat(found.code).as(found.err).isZero();
        assertThat(Json.parse(found.out).path("count").asInt()).isEqualTo(1);
        Result joined = cli("get-distributed", TRACE);
        assertThat(joined.code).as(joined.err).isZero();
        assertThat(Json.parse(joined.out).path("segments").size()).isEqualTo(1);
        Result selected = cli("investigate", "--trace-id", TRACE);
        assertThat(selected.code).as(selected.err).isZero();
        assertThat(Json.parse(selected.out).path("status").asText()).isEqualTo("selected");
        rows.removeAll();
        Result missing = cli("investigate", "--path", "/missing");
        assertThat(missing.code).as(missing.err).isEqualTo(2);
        assertThat(reads).allMatch(value -> value.startsWith("GET "));
        assertThat(reads.stream().filter(value -> value.contains("/export/"))).allMatch(value -> value.endsWith("?evict=false"));
    }

    @Test void cliTailPrintsEachRetainedIdentityOnceAndStopsCleanlyWhenInterrupted() throws Exception {
        AtomicReference<Result> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread watcher = new Thread(() -> {
            try { result.set(cli("tail", "--interval", "0.01")); }
            catch (Throwable error) { failure.set(error); }
        }, "parity-tail-test");
        watcher.start();
        try {
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (reads.size() < 3 && watcher.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(reads.size()).isGreaterThanOrEqualTo(3);
        } finally {
            watcher.interrupt();
            watcher.join(3000);
        }
        assertThat(watcher.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(result.get().code).isZero();
        assertThat(result.get().out).contains("watching for traces", "lens=flow", "user=dev");
        assertThat(result.get().out.split("lens=flow", -1)).hasSize(2);
        assertThat(reads).allMatch(value -> value.equals("GET /tracelens/traces"));
    }

    @Test void mcpActionGroupingIsCaseInsensitiveAndPreservesStableOrderingForTies() {
        ObjectNode first = (ObjectNode) rows.get(0);
        first.put("segmentId", SEGMENT).put("status", "COMPLETED");
        ObjectNode second = first.deepCopy();
        second.put("segmentId", SECOND).put("status", "FAILED");
        rows.add(second);
        String found = text(call("find_actions", Json.obj("action_name", "SAVE", "user", "DEV", "since_minutes", 30)));
        assertThat(found).contains("action-1 | Save Feature", "| 2 | FAILED");
        String detail = text(call("get_action", Json.obj("action_id", "action-1")));
        assertThat(detail.indexOf(SEGMENT)).isLessThan(detail.indexOf(SECOND));
        assertThat(text(call("get_action", Json.obj("action_id", "not-retained")))).contains("no retained action");
        assertThat(reads).allMatch(value -> value.equals("GET /tracelens/traces"));
    }

    @Test void mcpListFiltersAndOrdersRetainedFlowsWithoutExporting() {
        ObjectNode old = ((ObjectNode) rows.get(0)).deepCopy().put("segmentId", SECOND).put("lastActivityMillis", 1).put("entryChannel", "SQS");
        rows.add(old);
        String all = text(call("list_flows", Json.obj()));
        assertThat(all.indexOf(SEGMENT)).isLessThan(all.indexOf(SECOND));
        assertThat(text(call("list_flows", Json.obj("channel", "HTTP", "lens_id", "FLOW")))).contains(SEGMENT).doesNotContain(SECOND);
        assertThat(text(call("list_flows", Json.obj("limit", 0)))).startsWith("no flows retained");
        assertThat(reads).allMatch(value -> value.equals("GET /tracelens/traces"));
    }

    @Test void mcpLongHyphenatedLabelResolvesNewestSegmentButUuidSkipsLookup() {
        String label = "auto:GET /api/v2/features/user-licences/reference_counts";
        ((ObjectNode) rows.get(0)).put("lensId", label).put("lastActivityMillis", 1);
        rows.add(((ObjectNode) rows.get(0)).deepCopy().put("segmentId", SECOND).put("lastActivityMillis", 2));
        JsonNode response = call("get_flow_summary", Json.obj("id", label));
        assertThat(response.path("isError").asBoolean()).as(text(response)).isFalse();
        assertThat(reads).contains("GET /tracelens/export/" + SECOND + "?evict=false");
        reads.clear();
        assertThat(call("get_flow", Json.obj("id", SEGMENT.toUpperCase())).path("isError").asBoolean()).isFalse();
        assertThat(reads).containsExactly("GET /tracelens/export/" + SEGMENT + "?evict=false");
    }

    @Test void mcpFlowClippingAndSummaryRetainRuntimeBoundarySemantics() {
        String clipped = text(call("get_flow", Json.obj("id", SEGMENT, "max_events", 1)));
        assertThat(clipped).contains("[TRUNCATED]", "#1").doesNotContain("#2");
        String full = text(call("get_flow", Json.obj("id", SEGMENT, "max_events", 0)));
        assertThat(full).contains("#8");
        String summary = text(call("get_flow_summary", Json.obj("id", SEGMENT)));
        assertThat(summary).contains("threads=http-nio-8082-exec-3 → worker", "db-routes=TEST_MASTER",
                "external-calls=Sqs.send", "IllegalStateException at sample.Flow.run");
        String diagram = text(call("get_flow_mermaid", Json.obj("id", SEGMENT)));
        assertThat(diagram).contains("sequenceDiagram", "thread1->>thread2: thread hop", "DB route:", "thread2->>system1", "threw");
        assertThat(reads).allMatch(value -> value.equals("GET /tracelens/export/" + SEGMENT + "?evict=false"));
    }

    @Test void mcpRejectsStaleOrWrongSegmentAndSurvivesExportFailures() {
        assertThat(text(call("get_segment_execution", Json.obj("segment_id", SEGMENT, "instance", "previous-instance"))))
                .contains("instance changed");
        trace.put("segmentId", SECOND);
        assertThat(text(call("get_segment_execution", Json.obj("segment_id", SEGMENT)))).contains("different segment");
        for (int status : new int[] {404, 500}) {
            exportStatus.set(status);
            JsonNode result = call("get_flow", Json.obj("id", SEGMENT));
            assertThat(result.path("isError").asBoolean()).isTrue();
            assertThat(text(result)).contains("service error:");
            assertThat(mcp.handleMessage(Json.obj("id", 1, "method", "ping")).path("result")).isEqualTo(Json.obj());
        }
    }

    private McpServer federatedServer() {
        ToolConfig federated = config();
        federated.federated = true;
        federated.services = Arrays.asList(new Service("cb-app", url, root.toString()),
                new Service("cb-ems", url + "/second", null));
        return new McpServer(federated);
    }

    @Test void mcpExactServiceExecutionReadsOnlyThatAgentAndRejectsUnknownServiceWithoutIo() {
        McpServer server = federatedServer();
        JsonNode result = server.dispatchTool("get_segment_execution", Json.obj("segment_id", SEGMENT, "service", "cb-app", "instance", "instance-1"));
        assertThat(result.path("isError").asBoolean()).as(text(result)).isFalse();
        assertThat(result.path("structuredContent").path("segment").path("service").asText()).isEqualTo("cb-app");
        assertThat(reads).containsExactly("GET /tracelens/export/" + SEGMENT + "?evict=false");
        reads.clear();
        assertThat(server.dispatchTool("get_segment_execution", Json.obj("segment_id", SEGMENT, "service", "unknown"))
                .path("isError").asBoolean()).isTrue();
        assertThat(reads).isEmpty();
    }

    @Test void mcpFederatedLabelAmbiguityNeverChoosesAnArbitraryService() {
        McpServer server = federatedServer();
        JsonNode ambiguous = server.dispatchTool("get_flow", Json.obj("id", "flow"));
        assertThat(ambiguous.path("isError").asBoolean()).isTrue();
        assertThat(text(ambiguous)).contains("label exists in multiple services");
        assertThat(reads).containsExactlyInAnyOrder("GET /tracelens/traces", "GET /second/tracelens/traces");
        reads.clear();
        JsonNode exact = server.dispatchTool("get_flow", Json.obj("id", "flow", "service", "cb-ems", "instance", "instance-1"));
        assertThat(exact.path("isError").asBoolean()).as(text(exact)).isFalse();
        assertThat(reads).contains("GET /second/tracelens/export/" + SEGMENT + "?evict=false")
                .doesNotContain("GET /tracelens/export/" + SEGMENT + "?evict=false");
    }

    @Test void mcpFederatedSourceUsesOnlyExplicitServiceCheckout() throws Exception {
        Path source = root.resolve("src/main/java/sample/Flow.java");
        Files.createDirectories(source.getParent());
        Files.write(source, Arrays.asList("package sample;", "expectedSourceLine();"), StandardCharsets.UTF_8);
        McpServer server = federatedServer();
        assertThat(text(server.dispatchTool("get_flow", Json.obj("id", SEGMENT, "service", "cb-app"))))
                .contains("expectedSourceLine();");
        assertThat(text(server.dispatchTool("get_flow", Json.obj("id", SEGMENT, "service", "cb-ems"))))
                .doesNotContain("expectedSourceLine();", "│");
    }

    @Test void mcpTypedValidationPrecedesReadsAndNotificationsCannotTriggerTools() {
        List<ObjectNode> invalid = Arrays.asList(Json.obj(), Json.obj("segment_id", SEGMENT, "max_nodes", 0),
                Json.obj("segment_id", SEGMENT, "max_nodes", 501), Json.obj("segment_id", SEGMENT, "max_nodes", true),
                Json.obj("segment_id", SEGMENT, "max_nodes", 1.5), Json.obj("segment_id", SEGMENT, "max_nodes", "20"),
                Json.obj("segment_id", SEGMENT, "source", true), Json.obj("segment_id", SEGMENT.toUpperCase()),
                Json.obj("segment_id", SEGMENT, "service", "unknown"), Json.obj("segment_id", SEGMENT, "instance", "bad\ninstance"),
                Json.obj("segment_id", SEGMENT, "instance", "http://other.invalid"));
        for (ObjectNode arguments : invalid)
            assertThat(call("get_segment_execution", arguments).path("isError").asBoolean()).as(Json.stringify(arguments)).isTrue();
        assertThat(mcp.handleMessage(Json.obj("method", "tools/call", "params", Json.obj("name", "flow_status")))).isNull();
        assertThat(reads).isEmpty();
    }

    @Test void mcpStdioSupportsLegacyFallbackAndCompleteFinalLineWithoutNewline() throws Exception {
        String input = Json.stringify(Json.obj("jsonrpc", "2.0", "id", 1, "method", "initialize",
                "params", Json.obj("protocolVersion", "2024-11-05"))) + "\n"
                + Json.stringify(Json.obj("jsonrpc", "2.0", "id", 2, "method", "tools/call", "params",
                Json.obj("name", "get_segment_execution", "arguments", Json.obj("segment_id", SEGMENT, "max_nodes", 2))));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        mcp.serve(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(output, true, "UTF-8"), new PrintStream(new ByteArrayOutputStream()));
        String[] lines = output.toString("UTF-8").split("\n");
        assertThat(lines).hasSize(2);
        JsonNode response = Json.parse(lines[1]).path("result");
        assertThat(response.path("isError").asBoolean()).as(text(response)).isFalse();
        assertThat(response.has("structuredContent")).isFalse();
        assertThat(Json.parse(text(response)).path("kind").asText()).isEqualTo("segment-execution");
        assertThat(reads).containsExactly("GET /tracelens/export/" + SEGMENT + "?evict=false");
    }
}
