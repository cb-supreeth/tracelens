package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class McpServerTest {
    private static final String SEGMENT = "11111111-1111-1111-1111-111111111111";
    private static final String TRACE = "0123456789abcdef0123456789abcdef";
    @TempDir Path temporary;
    private HttpServer upstream;
    private ToolConfig config;
    private McpServer server;
    private ObjectNode trace;
    private ArrayNode summaryOverride;
    private final List<String> requests = new CopyOnWriteArrayList<String>();

    @BeforeEach void setup() throws Exception {
        long now = System.currentTimeMillis();
        trace = Json.obj("segmentId", SEGMENT, "traceId", TRACE, "spanId", "1234567890abcdef", "serviceInstanceId", "fixture-1",
                "applicationId", "fixture", "lensId", "checkout", "user", "private-user", "entryChannel", "HTTP",
                "actionId", "action-1", "actionName", "Save checkout", "status", "FAILED", "eventCount", 4,
                "startedAtMillis", now, "lastActivityMillis", now, "durationMs", 2.5, "startNanos", 1000000,
                "httpMethod", "GET", "path", "/checkout", "httpStatus", 500, "boundaryExceptionType", "java.lang.IllegalStateException",
                "attributes", Json.obj("httpMethod", "GET", "path", "/checkout", "httpStatus", "500", "siteId", "private-site"),
                "httpCalls", Json.arr(), "events", Json.arr(
                        event(1, "METHOD_ENTER", 1000000, 10, null),
                        event(2, "LINE", 1500000, 11, null),
                        event(3, "EXCEPTION", 2000000, 12, "PRIVATE-RAW-MESSAGE"),
                        event(4, "METHOD_EXIT", 3500000, 13, null)));
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/tracelens", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            String path = exchange.getRequestURI().getPath();
            JsonNode response;
            if (path.equals("/tracelens/summary"))
                response = Json.obj("recordingEnabled", true, "agentActive", true, "instrumentedClasses", 7, "traceCount", 1,
                        "maxTrackedRequests", 100, "totalEvents", 4, "maxTrackedEvents", 10000, "bridge", Json.obj("emitted", 4, "droppedNoTrace", 0));
            else if (path.equals("/tracelens/metadata"))
                response = Json.obj("recordingEnabled", true, "agentActive", true, "config", Json.obj("includePackages", Json.arr("sample"), "enabled", true));
            else if (path.equals("/tracelens/instrumented-classes")) response = Json.obj("classes", Json.arr("sample.Checkout"));
            else if (path.startsWith("/tracelens/export/") || path.startsWith("/tracelens/overview/")) response = trace;
            else if (path.equals("/tracelens/traces") || path.startsWith("/tracelens/find-by-")) {
                ObjectNode summary = trace.deepCopy(); summary.remove("events");
                response = Json.obj("traces", summaryOverride == null ? Json.arr(summary) : summaryOverride);
            } else response = Json.obj("ok", true);
            byte[] bytes = Json.stringify(response).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        upstream.start();
        config = ToolConfig.fromArgs(new String[] {"--url", "http://127.0.0.1:" + upstream.getAddress().getPort(),
                "--capture-dir", temporary.toRealPath().resolve("captures").toString(), "mcp"});
        server = new McpServer(config);
    }

    @AfterEach void close() { if (upstream != null) upstream.stop(0); }

    private static ObjectNode event(int seq, String type, long nanos, int line, String message) {
        return Json.obj("seq", seq, "type", type, "nanoTime", nanos, "thread", "http-nio-8082-exec-3",
                "className", "sample.Checkout", "methodName", "save", "line", line, "message", message,
                "attributes", "EXCEPTION".equals(type) ? Json.obj("exceptionType", "java.lang.IllegalStateException") : Json.obj());
    }
    private ObjectNode call(String name, ObjectNode args) { return server.dispatchTool(name, args); }
    private static JsonNode request(int id, String method, JsonNode params) { return Json.obj("jsonrpc", "2.0", "id", id, "method", method, "params", params); }
    private static String text(JsonNode result) { return result.path("content").path(0).path("text").asText(); }

    @Test void negotiatesBothProtocolsAndOnlyOffersAppMetadataWhenNegotiated() {
        JsonNode initialized = server.handleMessage(request(1, "initialize", Json.obj("protocolVersion", "2025-06-18",
                "capabilities", Json.obj("extensions", Json.obj("io.modelcontextprotocol/ui", Json.obj("mimeTypes", Json.arr(McpServer.APP_MIME)))))));
        assertThat(initialized.path("result").path("protocolVersion").asText()).isEqualTo("2025-06-18");
        assertThat(initialized.path("result").path("capabilities").has("extensions")).isTrue();
        JsonNode tools = server.handleMessage(request(2, "tools/list", Json.obj())).path("result").path("tools");
        assertThat(tools.size()).isEqualTo(22);
        JsonNode show = null;
        for (JsonNode item : tools) if ("show_trace_visualization".equals(item.path("name").asText())) show = item;
        assertThat(show.path("_meta").path("ui").path("resourceUri").asText()).isEqualTo(McpServer.APP_URI);
        server.handleMessage(request(3, "initialize", Json.obj("protocolVersion", "2024-11-05", "capabilities", Json.obj())));
        JsonNode legacy = server.handleMessage(request(4, "tools/call", Json.obj("name", "get_segment_execution", "arguments", Json.obj("segment_id", SEGMENT))));
        assertThat(legacy.path("result").path("isError").asBoolean()).isFalse();
        assertThat(legacy.path("result").has("structuredContent")).isFalse();
        assertThat(text(legacy.path("result"))).contains("\"kind\":\"segment-execution\"");
    }

    @Test void servesOfflineAppFromFixedBundledAssetsOnly() {
        JsonNode result = server.handleMessage(request(1, "resources/read", Json.obj("uri", McpServer.APP_URI)));
        String html = result.path("result").path("contents").path(0).path("text").asText();
        assertThat(html).contains("connect-src 'none'", "Causal request flow", "request-graph", "script");
        assertThat(server.handleMessage(request(2, "resources/read", Json.obj("uri", "file:///etc/passwd")))
                .path("error").path("code").asInt()).isEqualTo(-32002);
        assertThat(requests).isEmpty();
    }

    @Test void jdbcMetadataIsAvailableWithoutSendingRawSqlThroughMcp() {
        String sql="SELECT id FROM accounts WHERE email='private-jdbc-literal'";
        trace.set("events",Json.arr(event(1,"METHOD_ENTER",1000000,10,null),JdbcEvidenceTest.jdbc(2,"postgresql",sql),event(3,"METHOD_EXIT",3500000,13,null)));
        ((ObjectNode)trace.path("events").get(1).path("attributes")).put("sqlParametersStatus","captured").put("sqlParameters",
                Json.stringify(Json.arr(Json.obj("index",1,"type","String","value","private-bound-value","status","captured"))));
        trace.put("eventCount",3);
        for(String name:new String[]{"get_distributed_trace","get_trace_visualization","show_trace_visualization"}){
            ObjectNode result=call(name,Json.obj("trace_id",TRACE));
            assertThat(result.path("isError").asBoolean()).isFalse();
            assertThat(Json.stringify(result)).contains("jdbc-execution").doesNotContain("private-jdbc-literal","private-bound-value");
        }
        ObjectNode execution=call("get_segment_execution",Json.obj("segment_id",SEGMENT));
        assertThat(execution.path("isError").asBoolean()).isFalse();
        assertThat(Json.stringify(execution)).contains("jdbc-execution").doesNotContain("private-jdbc-literal","private-bound-value");
        ObjectNode flow=call("get_flow",Json.obj("id",SEGMENT));
        assertThat(flow.path("isError").asBoolean()).isFalse();
        assertThat(text(flow)).contains("JDBC postgresql executeQuery", "12.25 ms client-side").doesNotContain("private-jdbc-literal");
        ObjectNode limit=JdbcEvidenceTest.jdbc(4,"postgresql",sql);
        ((ObjectNode)limit.get("attributes")).put("evidenceKind","jdbc-query-limit").put("operation","query-limit").put("status","TRUNCATED").put("queryLimit","100");
        ((ArrayNode)trace.get("events")).add(limit);trace.put("eventCount",4);
        String mermaid=text(call("get_flow_mermaid",Json.obj("id",SEGMENT)));
        assertThat(mermaid).contains("JDBC \"executeQuery\"", "transaction commit not observed", "JDBC query capture limit reached").doesNotContain("private-jdbc-literal", ": return");
    }

    private McpServer exposed() throws Exception {
        return new McpServer(ToolConfig.fromArgs(new String[] {"--url", "http://127.0.0.1:" + upstream.getAddress().getPort(),
                "--capture-dir", temporary.toRealPath().resolve("captures").toString(), "--mcp-expose-sql", "mcp"}));
    }
    private ObjectNode sqlEvent(int seq, String sql) {
        ObjectNode event = JdbcEvidenceTest.jdbc(seq, "mysql", sql);
        event.put("thread", "http-nio-8082-exec-3");
        return event;
    }

    @Test void sqlToolIsHiddenAndRejectedUnlessTheOperatorEnablesIt() throws Exception {
        trace.set("events", Json.arr(event(1, "METHOD_ENTER", 1000000, 10, null), sqlEvent(2, "SELECT 'private-jdbc-literal'"), event(3, "METHOD_EXIT", 3500000, 13, null)));
        assertThat(config.mcpExposeSql).isFalse();
        for (JsonNode tool : server.tools()) assertThat(tool.path("name").asText()).isNotEqualTo("get_segment_sql");
        ObjectNode refused = call("get_segment_sql", Json.obj("segment_id", SEGMENT));
        assertThat(refused.path("isError").asBoolean()).isTrue();
        assertThat(text(refused)).isEqualTo("unknown tool: get_segment_sql");
        assertThat(requests).isEmpty();
        // The opt-in is operator configuration; an MCP argument cannot carry it.
        assertThat(text(call("get_segment_execution", Json.obj("segment_id", SEGMENT, "mcpExposeSql", true)))).contains("unexpected argument");
        Path file = temporary.resolve("tracelens.json");
        Files.write(file, "{\"viewer\":{\"mcpExposeSql\":true}}".getBytes(StandardCharsets.UTF_8));
        assertThat(ToolConfig.fromArgs(new String[] {"--config", file.toString()}).mcpExposeSql).isTrue();
        Files.write(file, "{\"viewer\":{\"mcpExposeSql\":\"true\"}}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> ToolConfig.fromArgs(new String[] {"--config", file.toString()})).isInstanceOf(IllegalArgumentException.class);
        McpServer enabled = exposed();
        assertThat(enabled.tools().size()).isEqualTo(23);
        // Enabling the dedicated tool does not loosen the existing model-facing summaries.
        for (String name : new String[] {"get_distributed_trace", "get_trace_visualization"})
            assertThat(Json.stringify(enabled.dispatchTool(name, Json.obj("trace_id", TRACE)))).doesNotContain("private-jdbc-literal");
        assertThat(Json.stringify(enabled.dispatchTool("get_segment_execution", Json.obj("segment_id", SEGMENT)))).doesNotContain("private-jdbc-literal");
        assertThat(text(enabled.dispatchTool("get_flow", Json.obj("id", SEGMENT)))).doesNotContain("private-jdbc-literal");
        assertThat(text(enabled.dispatchTool("get_trace_visualization", Json.obj("trace_id", TRACE)))).contains("Use get_segment_sql");
    }

    @Test void sqlToolReturnsBoundedSqlBindingsAndCallerWhenEnabled() throws Exception {
        StringBuilder longSql = new StringBuilder("UPDATE subscription_allowance_terms SET note='");
        while (longSql.length() < 3000) longSql.append('x');
        ObjectNode insert = sqlEvent(2, "INSERT INTO subscription_allowance_terms (id, granted_total) VALUES (?, ?)");
        ((ObjectNode) insert.path("attributes")).put("sqlParametersStatus", "captured").put("sqlParameters",
                Json.stringify(Json.arr(Json.obj("index", 1, "type", "Long", "value", "13", "status", "captured"),
                        Json.obj("index", 2, "type", "String", "value", "private-bound-value", "status", "captured"))));
        trace.set("events", Json.arr(event(1, "METHOD_ENTER", 1000000, 10, null), insert, sqlEvent(3, "SELECT id FROM accounts"),
                sqlEvent(4, longSql.toString()), event(5, "METHOD_EXIT", 3500000, 13, null), sqlEvent(6, "SELECT 1 FROM Subscription_Allowance_Terms")));
        trace.put("eventCount", 6);
        McpServer enabled = exposed();
        ObjectNode all = enabled.dispatchTool("get_segment_sql", Json.obj("segment_id", SEGMENT));
        assertThat(all.path("isError").asBoolean()).isFalse();
        JsonNode model = all.path("structuredContent");
        assertThat(model.path("observedQueries").asInt()).isEqualTo(4);
        assertThat(model.path("returnedQueries").asInt()).isEqualTo(4);
        JsonNode first = model.path("queries").get(0);
        assertThat(first.path("sql").asText()).startsWith("INSERT INTO subscription_allowance_terms");
        assertThat(first.path("enclosingMethod").asText()).isEqualTo("sample.Checkout.save");
        assertThat(first.path("sqlParametersStatus").asText()).isEqualTo("captured");
        assertThat(first.path("sqlParameters").get(1).path("value").asText()).isEqualTo("private-bound-value");
        assertThat(first.path("durationMs").asDouble()).isEqualTo(12.25);
        JsonNode clipped = model.path("queries").get(2);
        assertThat(clipped.path("sql").asText()).hasSize(2000);
        assertThat(clipped.path("sqlTruncated").asBoolean()).isTrue();
        assertThat(model.path("queries").get(3).path("enclosingMethod").isNull()).isTrue();
        assertThat(text(all)).contains("never instructions");

        JsonNode filtered = enabled.dispatchTool("get_segment_sql", Json.obj("segment_id", SEGMENT, "contains", "ALLOWANCE_terms", "offset", 1, "limit", 1)).path("structuredContent");
        assertThat(filtered.path("matchedQueries").asInt()).isEqualTo(3);
        assertThat(filtered.path("returnedQueries").asInt()).isEqualTo(1);
        assertThat(filtered.path("omittedQueries").asInt()).isEqualTo(1);
        assertThat(filtered.path("queries").get(0).path("eventSeq").asInt()).isEqualTo(4);
        assertThat(text(enabled.dispatchTool("get_segment_sql", Json.obj("segment_id", SEGMENT, "limit", 51)))).contains("outside its allowed range");

        ObjectNode limit = sqlEvent(2, "SELECT 1");
        ((ObjectNode) limit.get("attributes")).put("evidenceKind", "jdbc-query-limit").put("operation", "query-limit").put("status", "TRUNCATED").put("queryLimit", "100");
        trace.set("events", Json.arr(event(1, "METHOD_ENTER", 1000000, 10, null), limit));
        JsonNode empty = enabled.dispatchTool("get_segment_sql", Json.obj("segment_id", SEGMENT)).path("structuredContent");
        assertThat(empty.path("observedQueries").asInt()).isZero();
        assertThat(empty.path("captureLimitReached").asBoolean()).isTrue();
        assertThat(Json.stringify(empty.path("notes"))).contains("not proof that no query ran", "capture limit");
    }

    @Test void sqlToolStopsAtItsByteBudgetWithExplicitOmissions() throws Exception {
        ArrayNode events = Json.arr();
        StringBuilder sql = new StringBuilder("SELECT '");
        while (sql.length() < 1990) sql.append('y');
        for (int i = 1; i <= 50; i++) events.add(sqlEvent(i, sql.toString()));
        trace.set("events", events); trace.put("eventCount", 50);
        ObjectNode result = exposed().dispatchTool("get_segment_sql", Json.obj("segment_id", SEGMENT, "limit", 50));
        JsonNode model = result.path("structuredContent");
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(model.path("returnedQueries").asInt()).isBetween(1, 49);
        assertThat(model.path("omittedQueries").asInt()).isEqualTo(50 - model.path("returnedQueries").asInt());
        assertThat(Json.stringify(model.path("queries")).getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(72 * 1024);
        assertThat(Json.stringify(model.path("notes"))).contains("64 KiB response budget");
    }

    @Test void validatesBeforeUpstreamReadsAndKeepsToolErrorsInResults() {
        assertThat(call("get_segment_execution", Json.obj("segment_id", "checkout")).path("isError").asBoolean()).isTrue();
        assertThat(call("get_segment_execution", Json.obj("segment_id", SEGMENT, "max_nodes", 501)).path("isError").asBoolean()).isTrue();
        assertThat(call("get_segment_execution", Json.obj("segment_id", SEGMENT, "max_nodes", true)).path("isError").asBoolean()).isTrue();
        assertThat(call("get_trace_visualization", Json.obj("trace_id", "../outside")).path("isError").asBoolean()).isTrue();
        assertThat(call("list_request_captures", Json.obj("directory", "/etc")).path("isError").asBoolean()).isTrue();
        assertThat(call("get_error_group", Json.obj("group_id", "not-a-group")).path("isError").asBoolean()).isTrue();
        assertThat(call("unknown", Json.obj()).path("isError").asBoolean()).isTrue();
        assertThat(requests).isEmpty();
    }

    @Test void stdioFramingRecoversFromMalformedMessagesAndSuppressesNotifications() throws Exception {
        String input = "\nnot-json\n"
                + Json.stringify(Json.obj("jsonrpc", "2.0", "method", "notifications/initialized")) + "\n"
                + Json.stringify(request(8, "ping", Json.obj())) + "\r\n"
                + Json.stringify(request(9, "tools/call", Json.obj("name", "flow_status", "arguments", Json.obj()))) + "\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream(), diagnostics = new ByteArrayOutputStream();
        server.serve(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), new PrintStream(output, true, "UTF-8"), new PrintStream(diagnostics, true, "UTF-8"));
        String[] lines = output.toString("UTF-8").trim().split("\n");
        assertThat(lines).hasSize(3);
        assertThat(Json.parse(lines[0]).path("error").path("code").asInt()).isEqualTo(-32700);
        assertThat(Json.parse(lines[1]).path("id").asInt()).isEqualTo(8);
        assertThat(Json.parse(lines[2]).path("id").asInt()).isEqualTo(9);
        assertThat(text(Json.parse(lines[2]).path("result"))).contains("instrumentedClasses=7", "events=4/10000", "emitted=4");
        assertThat(diagnostics.toString("UTF-8")).contains("server ready");
        assertThat(output.toString("UTF-8")).doesNotContain("server ready");
    }

    @Test void boundedFramingRejectsOversizeLineThenContinues() throws Exception {
        char[] large = new char[1024 * 1024 + 1]; java.util.Arrays.fill(large, 'x');
        String input = new String(large) + "\n" + Json.stringify(request(7, "ping", Json.obj())) + "\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        server.serve(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), new PrintStream(output), new PrintStream(new ByteArrayOutputStream()));
        String[] lines = output.toString("UTF-8").trim().split("\n");
        assertThat(lines).hasSize(2);
        assertThat(Json.parse(lines[0]).path("error").path("code").asInt()).isEqualTo(-32600);
        assertThat(Json.parse(lines[1]).path("id").asInt()).isEqualTo(7);
    }

    @Test void flowToolsRenderUsefulEvidenceAndExportWithoutEviction() {
        assertThat(text(call("find_actions", Json.obj()))).contains("action-1", "Save checkout");
        assertThat(text(call("get_action", Json.obj("action_id", "action-1")))).contains(SEGMENT, "4", "2.5ms");
        assertThat(text(call("list_flows", Json.obj()))).contains(SEGMENT, "checkout");
        assertThat(text(call("search_flows", Json.obj("lens_id", "checkout")))).contains(SEGMENT);
        assertThat(text(call("get_flow", Json.obj("id", SEGMENT)))).contains("Checkout.save", "#1", "Checkout:11", "2.5ms");
        assertThat(text(call("get_flow_summary", Json.obj("id", SEGMENT)))).contains("duration=2.5ms", "METHOD_ENTER=1", "IllegalStateException");
        assertThat(text(call("get_flow_mermaid", Json.obj("id", SEGMENT)))).contains("sequenceDiagram", "IllegalStateException");
        assertThat(text(call("get_flow", Json.obj("id", SEGMENT, "instance", "old-instance")))).contains("instance changed");
        assertThat(requests).allMatch(path -> path.startsWith("GET "));
        assertThat(requests.stream().filter(path -> path.contains("/export/"))).allMatch(path -> path.endsWith("?evict=false"));
    }

    @Test void observedModelsExcludeLinePayloadsAndRawMessagesAndRemainBounded() {
        JsonNode graph = call("get_trace_visualization", Json.obj("trace_id", TRACE));
        assertThat(graph.path("isError").asBoolean()).isFalse();
        assertThat(graph.path("structuredContent").path("nodes").size()).isPositive();
        assertThat(Json.stringify(graph.path("structuredContent"))).doesNotContain("PRIVATE-RAW-MESSAGE", "\"events\"");
        JsonNode execution = call("get_segment_execution", Json.obj("segment_id", SEGMENT, "max_nodes", 2));
        assertThat(execution.path("isError").asBoolean()).isFalse();
        assertThat(Json.stringify(execution.path("structuredContent"))).doesNotContain("PRIVATE-RAW-MESSAGE", "private-user", "private-site");
        assertThat(Json.stringify(execution.path("structuredContent")).getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(32768);
        assertThat(Json.stringify(execution).getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(131072);
    }

    @Test void errorAndDiagnosisToolsUseSummaryEvidenceWithoutExportingEvents() {
        JsonNode doctor = call("tracelens_doctor", Json.obj());
        assertThat(doctor.path("isError").asBoolean()).as(text(doctor)).isFalse();
        JsonNode diagnosis = call("diagnose_capture_request", Json.obj("path", "/checkout", "method", "GET"));
        assertThat(diagnosis.path("isError").asBoolean()).as(text(diagnosis)).isFalse();
        JsonNode errors = call("list_error_groups", Json.obj());
        assertThat(errors.path("isError").asBoolean()).as(text(errors)).isFalse();
        JsonNode groups = errors.path("structuredContent").path("groups");
        assertThat(groups.size()).isPositive();
        assertThat(errors.path("structuredContent").has("requests")).isFalse();
        assertThat(groups.path(0).has("requestKeys")).isFalse();
        JsonNode detail = call("get_error_group", Json.obj("group_id", groups.path(0).path("id").asText()));
        assertThat(detail.path("isError").asBoolean()).as(text(detail)).isFalse();
        assertThat(detail.path("structuredContent").path("returnedRequestCount").asInt()).isEqualTo(1);
        assertThat(requests).noneMatch(path -> path.contains("/export/"));
        assertThat(Json.stringify(errors)).doesNotContain("PRIVATE-RAW-MESSAGE");
        requests.clear();
        JsonNode investigation = call("investigate_request", Json.obj("trace_id", TRACE));
        assertThat(investigation.path("isError").asBoolean()).as(text(investigation)).isFalse();
        assertThat(investigation.path("structuredContent").path("status").asText()).isEqualTo("selected");
        assertThat(call("find_distributed_traces", Json.obj()).path("isError").asBoolean()).isFalse();
        assertThat(call("get_distributed_trace", Json.obj("trace_id", TRACE)).path("isError").asBoolean()).isFalse();
        assertThat(call("show_trace_visualization", Json.obj("trace_id", TRACE)).path("isError").asBoolean()).isFalse();
    }

    @Test void errorGroupPresentationsCapGroupsAndWholeRequestsWithExplicitOmissions() {
        summaryOverride = Json.arr();
        for (int i = 1; i <= 60; i++) {
            ObjectNode row = trace.deepCopy();
            row.remove("events");
            row.put("segmentId", String.format("%08x-1111-1111-1111-111111111111", i));
            row.put("traceId", String.format("%032x", i));
            row.put("path", "/route/" + i);
            ((ObjectNode) row.path("attributes")).put("path", "/route/" + i);
            summaryOverride.add(row);
        }
        JsonNode listed = call("list_error_groups", Json.obj()).path("structuredContent");
        assertThat(listed.path("returnedGroupCount").asInt()).isEqualTo(50);
        assertThat(listed.path("omittedGroupCount").asInt()).isEqualTo(10);
        assertThat(listed.path("limited").asBoolean()).isTrue();
        assertThat(listed.has("requests")).isFalse();
        for (JsonNode row : listed.path("groups")) assertThat(row.has("requestKeys")).isFalse();

        for (JsonNode row : summaryOverride) {
            ((ObjectNode) row).put("path", "/same-route");
            ((ObjectNode) row.path("attributes")).put("path", "/same-route");
        }
        String id = call("list_error_groups", Json.obj()).path("structuredContent").path("groups").path(0).path("id").asText();
        JsonNode detailed = call("get_error_group", Json.obj("group_id", id)).path("structuredContent");
        assertThat(detailed.path("returnedRequestCount").asInt()).isEqualTo(20);
        assertThat(detailed.path("omittedRequestCount").asInt()).isEqualTo(40);
        assertThat(detailed.path("group").path("requestKeys").size()).isEqualTo(20);
        assertThat(detailed.path("group").path("requestCount").asInt()).isEqualTo(60);
        for (JsonNode row : detailed.path("requests")) assertThat(row.path("segments").size()).isEqualTo(1);
        assertThat(requests).noneMatch(path -> path.contains("/export/"));
    }

    @Test void captureToolsAreOfflineExceptExplicitSaveAndExposeWriteAnnotation() {
        JsonNode listed = call("list_request_captures", Json.obj());
        JsonNode searched = call("search_saved_captures", Json.obj("query", "Checkout"));
        assertThat(listed.path("isError").asBoolean()).as(text(listed)).isFalse();
        assertThat(searched.path("isError").asBoolean()).as(text(searched)).isFalse();
        assertThat(requests).isEmpty();
        Set<String> writes = new HashSet<String>();
        for (JsonNode tool : server.tools()) if (tool.path("annotations").has("readOnlyHint") && !tool.path("annotations").path("readOnlyHint").asBoolean()) writes.add(tool.path("name").asText());
        assertThat(writes).containsExactly("save_request_capture");
        JsonNode saved = call("save_request_capture", Json.obj("name", "baseline", "trace_id", TRACE));
        assertThat(saved.path("isError").asBoolean()).as(text(saved)).isFalse();
        assertThat(saved.path("structuredContent").path("saved").asBoolean()).as(Json.stringify(saved)).isTrue();
        requests.clear();
        JsonNode result = call("compare_request_captures", Json.obj("before", "baseline", "after", "baseline"));
        assertThat(result.path("isError").asBoolean()).as(text(result)).isFalse();
        assertThat(requests).isEmpty();
        assertThat(Json.stringify(result)).doesNotContain("PRIVATE-RAW-MESSAGE", "private-user", "private-site");
    }

    @Test void viewerOriginRejectsCredentialsPathsAndMalformedAuthorities() {
        assertThat(McpServer.validateViewerUrl("https://LOCALHOST:7781/")).isEqualTo("https://localhost:7781/");
        assertThat(McpServer.validateViewerUrl("HTTP://LOCALHOST:7781")).isEqualTo("http://localhost:7781/");
        for (String value : new String[] {"file:///tmp", "http://user:password@localhost", "http://localhost/path", "http://localhost/?", "http://localhost/#", "http://localhost:", "http://localhost:0", "http://localhost:65536", "http://localhost\\evil"})
            assertThatThrownBy(() -> McpServer.validateViewerUrl(value)).as(value).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void commandLineHelpAndVersionAreOfflineAndErrorsUseStderr() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        assertThat(ToolMain.run(new String[] {"--help"}, new PrintStream(out), new PrintStream(err))).isZero();
        assertThat(out.toString("UTF-8")).contains("mcp", "serve", "--config", "capture save");
        out.reset();
        assertThat(ToolMain.run(new String[] {"--version"}, new PrintStream(out), new PrintStream(err))).isZero();
        assertThat(out.toString("UTF-8")).contains(ToolMain.VERSION);
        out.reset();
        assertThat(ToolMain.run(new String[] {"not-a-command"}, new PrintStream(out), new PrintStream(err))).isEqualTo(1);
        assertThat(err.toString("UTF-8")).contains("unknown command");
        assertThat(out.size()).isZero();
        assertThat(requests).isEmpty();
    }

    @Test void commandLineExportsReadOnlyAndRequiresSingleServiceForExplicitMutations() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        PrintStream stdout = new PrintStream(out), stderr = new PrintStream(err);
        assertThat(ToolMain.run(new String[] {"--url", config.upstream, "export", SEGMENT}, stdout, stderr)).isZero();
        assertThat(Json.parse(out.toString("UTF-8")).path("segmentId").asText()).isEqualTo(SEGMENT);
        assertThat(requests).containsExactly("GET /tracelens/export/" + SEGMENT + "?evict=false");
        out.reset(); requests.clear();
        assertThat(ToolMain.run(new String[] {"--url", config.upstream, "list"}, stdout, stderr)).isZero();
        assertThat(out.toString("UTF-8")).contains("TRACE ID", TRACE, "FAILED");
        out.reset(); requests.clear();
        assertThat(ToolMain.run(new String[] {"--url", config.upstream, "enable"}, stdout, stderr)).isZero();
        assertThat(requests).containsExactly("POST /tracelens/enable");
        Path registry = temporary.resolve("services.json");
        Files.write(registry, Json.stringify(Json.obj("services", Json.arr(Json.obj("name", "fixture", "url", config.upstream)))).getBytes(StandardCharsets.UTF_8));
        requests.clear(); out.reset(); err.reset();
        assertThat(ToolMain.run(new String[] {"--services", registry.toString(), "reset"}, stdout, stderr)).isEqualTo(1);
        assertThat(err.toString("UTF-8")).contains("federation is read-only");
        assertThat(requests).isEmpty();
    }

    @Test void singleServiceSourceFallbackIsPreservedButFederationRequiresExplicitRoots() throws Exception {
        Federation discovery = new Federation(config.services);
        assertThat(ToolMain.renderSourceRoot(config, discovery, trace)).isEqualTo(Source.defaultRoot().toString());
        Path source = temporary.resolve("source/src/main/java/sample/Checkout.java");
        Files.createDirectories(source.getParent());
        List<String> sourceLines = new ArrayList<String>();
        for (int i = 1; i <= 15; i++) sourceLines.add(i == 11 ? "  // expected local source line" : "// " + i);
        Files.write(source, sourceLines, StandardCharsets.UTF_8);
        config.sourceRoot = temporary.resolve("source").toString();
        assertThat(ToolMain.renderSourceRoot(config, discovery, trace)).isEqualTo(config.sourceRoot);
        assertThat(Renderer.render(trace, config.sourceRoot, true, true)).contains("expected local source line");
        assertThat(Renderer.render(trace, config.sourceRoot, false, true)).doesNotContain("expected local source line");
        config.federated = true;
        ObjectNode tagged = trace.deepCopy(); tagged.put("service", "default");
        assertThat(ToolMain.renderSourceRoot(config, discovery, tagged)).isNull();
        assertThat(requests).isEmpty();
    }
}
