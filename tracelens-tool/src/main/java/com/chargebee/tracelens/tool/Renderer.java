package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Terminal rendering of observed events. Source lookup is always operator-owned. */
public final class Renderer {
    private Renderer() {}

    public static String render(JsonNode trace, String sourceRoot, boolean showSource, boolean collapse) {
        List<JsonNode> events = Json.rows(trace, "events");
        List<String> out = new ArrayList<String>();
        long start = trace.path("startNanos").asLong();
        String header = "trace " + clip(Json.text(trace, "traceId", "?"), 8) + "… \""
                + Json.text(trace, "lensId", "?") + "\" — user=" + Json.text(trace, "user", "?")
                + " channel=" + Json.text(trace, "entryChannel", "?") + " status=" + Json.text(trace, "status", "?");
        JsonNode attrs = trace.path("attributes");
        if (attrs.hasNonNull("httpStatus")) header += " http=" + attrs.path("httpStatus").asText();
        if (trace.hasNonNull("durationMs")) header += " duration=" + trace.path("durationMs").asText() + "ms";
        header += " events=" + trace.path("eventCount").asInt(events.size());
        if (trace.path("truncated").asBoolean()) header += " [TRUNCATED]";
        out.add(header);
        out.add(repeat("─", Math.min(header.length() + 20, 120)));
        if (attrs.hasNonNull("siteId") || attrs.hasNonNull("siteType"))
            out.add("site: " + Json.text(attrs, "siteId", "?") + " (" + Json.text(attrs, "siteType", "?") + ")"
                    + (attrs.hasNonNull("requestId") ? "  requestId=" + attrs.path("requestId").asText() : ""));
        if (attrs.hasNonNull("datadogTraceId"))
            out.add("datadog: trace_id=" + attrs.path("datadogTraceId").asText()
                    + " (queryable via the Datadog MCP / UI; spans tagged tracelens.id=\""
                    + Json.text(trace, "lensId", "?") + "\")");
        Source source = showSource && sourceRoot != null ? new Source(Paths.get(sourceRoot)) : null;
        int depth = 0;
        String previousThread = null;
        for (int i = 0; i < events.size(); i++) {
            JsonNode event = events.get(i);
            int run = 1;
            while (collapse && i + 1 < events.size() && same(event, events.get(i + 1))) { run++; i++; }
            String type = Json.text(event, "type", "UNKNOWN");
            String thread = Json.text(event, "thread", "?");
            if (!thread.equals(previousThread)) {
                out.add("── thread: " + (thread.startsWith("http-nio-") ? "http-exec-" + thread.substring(thread.lastIndexOf('-') + 1) : thread)
                        + " " + repeat("─", 40));
                previousThread = thread;
            }
            if ("METHOD_EXIT".equals(type)) depth = Math.max(0, depth - 1);
            String indent = repeat("  ", Math.min(depth, 1000));
            String prefix = String.format(Locale.ROOT, "%6s %8.3fms %s", "#" + event.path("seq").asText("?"),
                    (event.path("nanoTime").asLong(start) - start) / 1000000.0, indent);
            String suffix = run > 1 ? "  (x" + run + ")" : "";
            String cls = Json.text(event, "className", "?");
            String shortClass = cls.substring(cls.lastIndexOf('.') + 1);
            String location = shortClass + "." + Json.text(event, "methodName", "?");
            if ("METHOD_ENTER".equals(type)) { out.add(prefix + "▶ " + location + suffix); depth++; }
            else if ("METHOD_EXIT".equals(type)) out.add(prefix + "◀ " + location + suffix);
            else if ("LINE".equals(type)) {
                out.add(prefix + "· " + shortClass + ":" + event.path("line").asText("?") + suffix);
                String line = source == null ? null : source.sourceLine(cls, event.path("line").asInt(-1));
                if (line != null && !line.isEmpty())
                    out.add("                 " + indent + "  │ " + (line.codePointCount(0, line.length()) > 96 ? clip(line, 95) + "…" : line));
            } else if ("EXCEPTION".equals(type)) {
                out.add(prefix + "✖ " + location + " threw " + Json.text(event.path("attributes"), "exceptionType", "?")
                        + ": " + Json.text(event, "message", "") + suffix);
            } else if ("EXTERNAL_CALL".equals(type) || "DB_ROUTE".equals(type) || "MARKER".equals(type)) {
                JsonNode boundary=Dependencies.boundarySummary(event);
                if(boundary!=null&&(Dependencies.sqlExecution(boundary)||Dependencies.sqlLimit(boundary))){
                    String summary=("clickhouse".equals(Json.text(boundary,"system"))?"Native ":"JDBC ")+Json.text(boundary,"system")+" "+Json.text(boundary,"operation")+" · "+Json.text(boundary,"status");
                    if(boundary.hasNonNull("durationMs"))summary+=" · "+boundary.path("durationMs").asText()+" ms client-side";
                    if(boundary.hasNonNull("batchSize"))summary+=" · batch size "+boundary.path("batchSize").asText();
                    if(boundary.hasNonNull("queryLimit"))summary+=" · limit "+boundary.path("queryLimit").asText()+"; additional queries omitted";
                    if(boundary.hasNonNull("errorType"))summary+=" · "+boundary.path("errorType").asText();
                    if(boundary.hasNonNull("sql"))summary+=" · raw SQL available in local viewer";
                    out.add(prefix+"◆ "+summary);continue;
                }
                String message = Json.text(event, "message");
                out.add(prefix + "◆ " + type + ": " + (message == null || message.isEmpty() ? attributes(event) : message) + suffix);
            } else out.add(prefix + type + suffix);
        }
        return String.join("\n", out);
    }

    private static boolean same(JsonNode a, JsonNode b) {
        if(Dependencies.sqlExecution(a.path("attributes"))||Dependencies.sqlExecution(b.path("attributes"))
                ||Dependencies.sqlLimit(a.path("attributes"))||Dependencies.sqlLimit(b.path("attributes")))return false;
        for (String key : new String[] {"type", "className", "methodName", "line", "thread"})
            if (!Objects.equals(nullable(a.get(key)), nullable(b.get(key)))) return false;
        return true;
    }

    private static JsonNode nullable(JsonNode node) { return node == null || node.isNull() ? null : node; }

    private static String attributes(JsonNode event) {
        List<String> parts = new ArrayList<String>();
        Iterator<Map.Entry<String, JsonNode>> iterator = event.path("attributes").fields();
        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> field = iterator.next();
            parts.add(field.getKey() + "=" + field.getValue().asText());
        }
        return String.join(" ", parts);
    }

    private static String clip(String value, int limit) {
        return value.substring(0, value.offsetByCodePoints(0, Math.min(value.codePointCount(0, value.length()), limit)));
    }
    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
