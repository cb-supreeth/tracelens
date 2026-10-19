package com.chargebee.tracelens.servlet;

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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Framework-independent implementation of the /tracelens debug API. The filter
 * performs access checks and delegates here before entering the application's
 * servlet chain. Responses retain the Spring adapter's CLI/MCP-compatible schema.
 */
public final class TraceLensDebugApi {

    private final TraceStore store;
    private final TraceExporter exporter;
    private final TraceLensConfig config;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public TraceLensDebugApi(TraceStore store, TraceExporter exporter, TraceLensConfig config) {
        this.store = store;
        this.exporter = exporter;
        this.config = config;
    }

    /**
     * Handle a decoded path relative to /tracelens, for example /summary. A blank
     * path or / returns the summary. Only reset accepts DELETE; other mutations
     * require POST. Calling this helper never falls through to application routes.
     */
    public void handle(String path, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        path = normalizePath(path);
        String allowed = allowedMethods(path);
        if (allowed == null) {
            error(resp, HttpServletResponse.SC_NOT_FOUND, "unknown route " + path);
            return;
        }
        String method = req.getMethod();
        boolean accepted = "GET".equals(allowed) ? "GET".equals(method)
                : "POST".equals(method) || ("POST, DELETE".equals(allowed) && "DELETE".equals(method));
        if (!accepted) {
            resp.setHeader("Allow", allowed);
            error(resp, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "method not allowed");
            return;
        }

        if (path.equals("/summary")) {
            writeJson(resp, summary());
        } else if (path.equals("/traces")) {
            writeJson(resp, traces(store.summaries()));
        } else if (path.startsWith("/export/")) {
            export(path.substring("/export/".length()), req, resp);
        } else if (path.startsWith("/find-by-lens-id/")) {
            List<TraceSummary> matches = new ArrayList<>();
            for (Trace trace : store.findByLensId(path.substring("/find-by-lens-id/".length()))) {
                matches.add(new TraceSummary(trace));
            }
            writeJson(resp, traces(matches));
        } else if (path.equals("/metadata")) {
            writeJson(resp, metadata());
        } else if (path.equals("/instrumented-classes")) {
            writeJson(resp, instrumentedClasses());
        } else if (path.equals("/enable")) {
            store.setRecordingEnabled(true);
            writeJson(resp, status("recording enabled"));
        } else if (path.equals("/disable")) {
            store.setRecordingEnabled(false);
            writeJson(resp, status("recording disabled"));
        } else if (path.equals("/reset")) {
            int cleared = store.resetAll();
            writeJson(resp, status("cleared " + cleared + " traces"));
        } else if (store.reset(path.substring("/reset/".length()))) {
            writeJson(resp, status("trace cleared"));
        } else {
            error(resp, HttpServletResponse.SC_NOT_FOUND, "no such trace");
        }
    }

    private static String allowedMethods(String path) {
        if (path.equals("/summary") || path.equals("/traces") || path.startsWith("/export/")
                || path.startsWith("/find-by-lens-id/") || path.equals("/metadata")
                || path.equals("/instrumented-classes")) {
            return "GET";
        }
        if (path.equals("/enable") || path.equals("/disable")) {
            return "POST";
        }
        if (path.equals("/reset") || path.startsWith("/reset/")) {
            return "POST, DELETE";
        }
        return null;
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

    private void export(String segmentId, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // MCP/CLI reads keep the timeline in the store; omitted parameter preserves
        // the configured evictOnExport behavior, matching the existing adapter.
        Boolean evictOverride = "false".equalsIgnoreCase(req.getParameter("evict")) ? Boolean.FALSE : null;
        Optional<Trace> trace = store.export(segmentId, evictOverride);
        if (!trace.isPresent()) {
            error(resp, HttpServletResponse.SC_NOT_FOUND, "no such trace: " + segmentId);
            return;
        }
        prepareJson(resp);
        resp.getWriter().write(exporter.export(trace.get()));
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
        for (String name : AgentStatus.instrumentedClassNames()) {
            names.add(name);
        }
        return node;
    }

    private ObjectNode status(String message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("status", message);
        node.put("recordingEnabled", store.isRecordingEnabled());
        node.put("traceCount", store.size());
        return node;
    }

    private void error(HttpServletResponse resp, int code, String message) throws IOException {
        resp.setStatus(code);
        ObjectNode node = mapper.createObjectNode();
        node.put("error", message);
        writeJson(resp, node);
    }

    private void writeJson(HttpServletResponse resp, ObjectNode node) throws IOException {
        prepareJson(resp);
        resp.getWriter().write(mapper.writeValueAsString(node));
    }

    private static void prepareJson(HttpServletResponse resp) {
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("application/json");
        resp.setHeader("Cache-Control", "no-store");
    }

    private static String normalizePath(String path) {
        if (path == null || path.trim().isEmpty() || path.equals("/")) {
            return "/summary";
        }
        // A trailing slash can be part of an exact API label, not a route delimiter.
        if (path.startsWith("/find-by-lens-id/") || path.startsWith("/export/")
                || path.startsWith("/reset/")) {
            return path;
        }
        return path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
    }
}
