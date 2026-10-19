package com.chargebee.tracelens.spring;

import com.chargebee.tracelens.bridge.AgentStatus;
import com.chargebee.tracelens.bridge.TraceRuntime;
import com.chargebee.tracelens.core.config.TraceLensConfig;
import com.chargebee.tracelens.core.export.TraceExporter;
import com.chargebee.tracelens.core.store.TraceStore;
import com.chargebee.tracelens.core.store.TraceSummary;
import com.chargebee.tracelens.core.trace.Trace;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * The /tracelens/* debug API. Plain HttpServlet (registered via ServletRegistrationBean
 * today, plain servlet registration on cb-framework tomorrow) — no Spring MVC, so the
 * API surface survives the framework migration unchanged.
 *
 *   GET    /tracelens/summary                  store + agent status (verification entry point)
 *   GET    /tracelens/traces                   retained trace summaries
 *   GET    /tracelens/export/{traceId}         full timeline JSON (destructive when evictOnExport)
 *   GET    /tracelens/find-by-lens-id/{label}  summaries for a label (labels may repeat)
 *   GET    /tracelens/metadata                 effective config + agent status
 *   GET    /tracelens/instrumented-classes     woven class proof (bounded list)
 *   POST   /tracelens/enable | /disable        recording toggle
 *   POST|DELETE /tracelens/reset[/{traceId}]   destructive clear
 */
public final class TraceLensDebugServlet extends HttpServlet {

    private final TraceStore store;
    private final TraceExporter exporter;
    private final TraceLensConfig config;
    private final transient ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public TraceLensDebugServlet(TraceStore store, TraceExporter exporter, TraceLensConfig config) {
        this.store = store;
        this.exporter = exporter;
        this.config = config;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = pathInfo(req);
        if (path.equals("/summary")) {
            writeJson(resp, summary());
        } else if (path.equals("/traces")) {
            writeJson(resp, traces(store.summaries()));
        } else if (path.startsWith("/export/")) {
            export(path.substring("/export/".length()), req, resp);
        } else if (path.startsWith("/find-by-lens-id/")) {
            findByLensId(path.substring("/find-by-lens-id/".length()), resp);
        } else if (path.equals("/metadata")) {
            writeJson(resp, metadata());
        } else if (path.equals("/instrumented-classes")) {
            writeJson(resp, instrumentedClasses());
        } else {
            notFound(resp, "unknown route " + path);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = pathInfo(req);
        switch (path) {
            case "/enable" -> {
                store.setRecordingEnabled(true);
                writeJson(resp, status("recording enabled"));
            }
            case "/disable" -> {
                store.setRecordingEnabled(false);
                writeJson(resp, status("recording disabled"));
            }
            case "/reset" -> {
                int cleared = store.resetAll();
                writeJson(resp, status("cleared " + cleared + " traces"));
            }
            default -> {
                if (path.startsWith("/reset/")) {
                    boolean removed = store.reset(path.substring("/reset/".length()));
                    if (removed) {
                        writeJson(resp, status("trace cleared"));
                    } else {
                        notFound(resp, "no such trace");
                    }
                } else {
                    notFound(resp, "unknown route " + path);
                }
            }
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        doPost(req, resp); // FlowLens uses DELETE for resets; accept both
    }

    private ObjectNode summary() {
        ObjectNode node = mapper.createObjectNode();
        node.put("recordingEnabled", store.isRecordingEnabled());
        node.put("traceCount", store.size());
        node.put("totalEvents", store.totalEvents());
        node.put("maxTrackedEvents", config.getMaxTrackedEvents());
        node.put("maxTrackedRequests", config.getMaxTrackedRequests());
        node.put("agentActive", AgentStatus.agentActive());
        node.put("instrumentedClasses", AgentStatus.instrumentedCount());
        node.put("failedClasses", AgentStatus.failedCount());
        node.put("premainMs", AgentStatus.premainMillis());
        node.put("scope", AgentStatus.scopeDescription());
        ObjectNode bridge = node.putObject("bridge");
        bridge.put("sinkRegistered", TraceRuntime.hasSink());
        bridge.put("emitted", TraceRuntime.emittedCount());
        bridge.put("droppedNoSink", TraceRuntime.droppedNoSinkCount());
        bridge.put("droppedNoTrace", TraceRuntime.droppedNoTraceCount());
        return node;
    }

    private ObjectNode traces(List<TraceSummary> summaries) {
        ObjectNode node = mapper.createObjectNode();
        ArrayNode array = node.putArray("traces");
        for (TraceSummary summary : summaries) {
            array.add(mapper.valueToTree(summary));
        }
        node.put("count", summaries.size());
        return node;
    }

    private void export(String traceId, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // ?evict=false -> inspect without removing (MCP/CLI reads; keeps the UI's copy)
        Boolean evictOverride = "false".equalsIgnoreCase(req.getParameter("evict")) ? Boolean.FALSE : null;
        Optional<Trace> trace = store.export(traceId, evictOverride);
        if (trace.isEmpty()) {
            notFound(resp, "no such trace: " + traceId);
            return;
        }
        resp.setContentType("application/json");
        resp.getWriter().write(exporter.export(trace.get()));
    }

    private void findByLensId(String lensId, HttpServletResponse resp) throws IOException {
        List<TraceSummary> matches = store.findByLensId(lensId).stream().map(TraceSummary::new).toList();
        writeJson(resp, traces(matches));
    }

    private ObjectNode metadata() {
        ObjectNode node = mapper.createObjectNode();
        node.put("applicationId", config.getApplicationId());
        node.put("applicationVersion", config.getApplicationVersion());
        node.putPOJO("packages", config.getPackages());
        node.putPOJO("excludedPackages", config.getExcludedPackages());
        node.putPOJO("blacklistedNodes", config.getBlacklistedNodes());
        node.put("capturePayloads", config.isCapturePayloads());
        node.put("trackMethods", config.isTrackMethods());
        node.put("trackExceptions", config.isTrackExceptions());
        node.put("instrumentExecutors", config.isInstrumentExecutors());
        node.put("idleTtlMinutes", config.getIdleTtlMinutes());
        node.put("evictOnExport", config.isEvictOnExport());
        node.put("agentActive", AgentStatus.agentActive());
        return node;
    }

    private ObjectNode instrumentedClasses() {
        ObjectNode node = mapper.createObjectNode();
        node.put("count", AgentStatus.instrumentedCount());
        node.put("failed", AgentStatus.failedCount());
        node.put("retainedNamesCap", true);
        ArrayNode names = node.putArray("names");
        AgentStatus.instrumentedClassNames().forEach(names::add);
        return node;
    }

    private ObjectNode status(String message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("status", message);
        node.put("recordingEnabled", store.isRecordingEnabled());
        node.put("traceCount", store.size());
        return node;
    }

    private void notFound(HttpServletResponse resp, String message) throws IOException {
        resp.setStatus(404);
        ObjectNode node = mapper.createObjectNode();
        node.put("error", message);
        writeJson(resp, node);
    }

    private void writeJson(HttpServletResponse resp, ObjectNode node) throws IOException {
        resp.setContentType("application/json");
        resp.getWriter().write(mapper.writeValueAsString(node));
    }

    private static String pathInfo(HttpServletRequest req) {
        String path = req.getPathInfo();
        if (path == null || path.isBlank()) {
            return "/summary";
        }
        return path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
    }
}
