package com.chargebee.tracelens.core.export;

import com.chargebee.tracelens.core.config.TraceLensConfig;
import com.chargebee.tracelens.core.event.TraceEvent;
import com.chargebee.tracelens.core.trace.Trace;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded read-only projections. Full exports are never constructed on this path. */
public final class TraceProjection {
    public static final int MAX_PAGE_BYTES = 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SUMMARY_ATTRIBUTES = new HashSet<>(Arrays.asList(
            "spanId", "parentSpanId", "serviceInstanceId", "httpMethod", "path", "httpStatus", "siteId", "route",
            "actionId", "actionName", "sessionId", "contentType", "requestSizeBytes", "filterException", "asyncTimeout",
            "jobType", "jobId", "jobOutcome", "jobAuditStatus", "jobFailureType", "scheduler",
            "httpQueryParametersStatus", "httpRequestBodyStatus", "httpPayloadRedaction"));
    private static final Set<String> BOUNDARY_ATTRIBUTES = new HashSet<>(Arrays.asList(
            "boundaryKind", "system", "operation", "direction", "destination", "peer", "status", "evidenceKind",
            "durationMs", "startedAtMillis", "endedAtMillis", "count", "sizeBytes", "region", "route",
            "sqlSource", "sqlTruncated", "sqlParametersStatus", "batchSize", "queryLimit", "exceptionType",
            "callingEventSeq", "callingThread", "callingThreadId", "callingContextStatus", "sourceClass", "sourceMethod", "sourceLine"));
    private final TraceExporter exporter;

    public TraceProjection(TraceExporter exporter) { this.exporter = exporter; }

    public static void capabilities(ObjectNode node, TraceLensConfig config) {
        node.put("projectionVersion", 1);
        node.putArray("capabilities").add("overview-v1").add("events-v1").add("request-data-v1");
        node.put("maxEventPageBytes", MAX_PAGE_BYTES);
        node.put("captureMode", config.getCaptureMode());
        boolean withinWindow = config.getDetailedCaptureUntilEpochMillis() == 0
                || System.currentTimeMillis() <= config.getDetailedCaptureUntilEpochMillis();
        boolean codeCapture = withinWindow && !config.getPackages().isEmpty()
                && (!"overview".equals(config.getCaptureMode()) || !config.getDetailedCapturePaths().isEmpty());
        node.put("methodCaptureEnabled", codeCapture && config.isTrackMethods());
        node.put("lineCaptureEnabled", codeCapture && !"methods".equals(config.getCaptureMode()));
        node.put("maxSegmentBytes", config.getMaxSegmentBytes());
        node.put("maxTrackedBytes", config.getMaxTrackedBytes());
        node.putPOJO("detailedCapturePaths", config.getDetailedCapturePaths());
        node.put("detailedCaptureUntilEpochMillis", config.getDetailedCaptureUntilEpochMillis());
        node.set("recordingManifest", CaptureProvenance.manifest(config, CaptureProvenance.fingerprint(config)));
        node.set("integrationStatus", JSON.valueToTree(com.chargebee.tracelens.bridge.AgentStatus.integrations()));
        ObjectNode diagnostics = node.putObject("runtimeDiagnostics");
        diagnostics.put("observerFailures", com.chargebee.tracelens.bridge.TraceRuntime.observerFailureCount());
        diagnostics.put("skippedMessagePropagation", com.chargebee.tracelens.bridge.TraceRuntime.skippedMessagePropagationCount());
        diagnostics.put("executorPropagatedTasks", com.chargebee.tracelens.bridge.ExecutorTraceRuntime.propagatedTaskCount());
        diagnostics.put("executorSkippedTasks", com.chargebee.tracelens.bridge.ExecutorTraceRuntime.skippedCount());
        diagnostics.put("executorPendingTasks", com.chargebee.tracelens.bridge.ExecutorTraceRuntime.pendingCount());
        diagnostics.put("executorQuarantinedPools", com.chargebee.tracelens.bridge.ExecutorTraceRuntime.quarantinedExecutorCount());
        diagnostics.put("executorAssociationDisabled", com.chargebee.tracelens.bridge.ExecutorTraceRuntime.associationDisabled());
        diagnostics.put("executorCoverage", "Automatic propagation: exact ThreadPoolExecutor with exact LinkedBlockingQueue, ArrayBlockingQueue or SynchronousQueue; executor API-mediated submissions only; direct queue mutation is unsupported. Other executors/queues require explicit context propagation.");
    }

    public ObjectNode overview(Trace trace) {
        ObjectNode result = exporter.metadata(trace);
        ObjectNode attributes = result.putObject("attributes");
        trace.getAttributes().forEach((key, value) -> { if (SUMMARY_ATTRIBUTES.contains(key)) attributes.put(key, value); });
        result.put("projectionVersion", 1);
        result.put("detailsAvailable", true);
        result.put("codeDetailAvailable", !"overview".equals(trace.captureMode()) || trace.eventCount() > 0 && !trace.dropReasons().containsKey("capture-mode-overview"));
        result.put("through", trace.lastSequence());
        boolean metadataOmitted = false;
        ArrayNode calls = (ArrayNode) result.get("httpCalls");
        int metadataBytes = encodedSize(result);
        while (metadataBytes > MAX_PAGE_BYTES - 4096 && calls.size() > 0) {
            calls.remove(calls.size() - 1);
            metadataOmitted = true;
            result.put("httpCallsTruncated", true);
            metadataBytes = encodedSize(result);
        }
        if (metadataBytes > MAX_PAGE_BYTES - 4096) {
            result = minimalOverview(trace, result.path("through").asLong());
            metadataOmitted = true;
        }
        result.put("overviewMetadataTruncated", metadataOmitted);
        ArrayNode events = result.putArray("events"), warnings = result.putArray("warnings");
        if (metadataOmitted) warnings.add("Overview metadata was limited to fit the byte budget; missing calls or fields are not proof of non-execution.");
        int remaining = MAX_PAGE_BYTES - encodedSize(result) - 2048;
        List<TraceEvent> selected = trace.eventSlice(0, result.path("through").asLong(), 5001, "boundaries");
        boolean omitted = false;
        for (TraceEvent event : selected) {
            ObjectNode item = event(event, true);
            int size = encodedSize(item) + 1;
            if (events.size() == 5000 || size > remaining) { omitted = true; break; }
            events.add(item);
            remaining -= size;
        }
        result.put("overviewTruncated", omitted || metadataOmitted);
        result.put("partial", omitted || metadataOmitted);
        if (omitted) warnings.add("Overview boundary limit reached; use event pages for remaining retained evidence.");
        if (encodedSize(result) > MAX_PAGE_BYTES) {
            result = minimalOverview(trace, result.path("through").asLong());
            result.putArray("events");
            result.putArray("warnings").add("Overview byte limit reached; only bounded segment metadata is shown.");
            result.put("overviewMetadataTruncated", true);
            result.put("overviewTruncated", true);
            result.put("partial", true);
        }
        if (encodedSize(result) > MAX_PAGE_BYTES) throw new IllegalStateException("Bounded overview identity exceeds the byte limit");
        return result;
    }

    private static ObjectNode minimalOverview(Trace trace, long through) {
        ObjectNode result = identity(trace);
        result.put("schemaVersion", 2);
        result.put("status", trace.getStatus().name());
        result.put("entryChannel", trace.getEntryChannel().name());
        result.put("startedAt", trace.getStartedAtMillis());
        result.put("eventCount", trace.eventCount());
        result.put("through", through);
        result.put("detailsAvailable", true);
        result.put("captureMode", trace.captureMode());
        result.put("detailTruncated", trace.isTruncated());
        result.put("truncated", true);
        result.put("httpCallsTruncated", true);
        result.putArray("httpCalls");
        result.putObject("attributes");
        for (String key : Arrays.asList("spanId", "parentSpanId")) {
            String value = trace.getAttributes().get(key);
            if (value != null && value.length() <= 128) result.put(key, value);
        }
        return result;
    }

    public ObjectNode requestData(Trace trace) {
        ObjectNode result = identity(trace);
        result.set("attributes", JSON.valueToTree(trace.getAttributes()));
        result.put("captureMode", trace.captureMode());
        result.set("dropReasons", JSON.valueToTree(trace.dropReasons()));
        result.put("omittedDetailCallbacks", trace.omittedDetailCallbacks());
        if (encodedSize(result) > MAX_PAGE_BYTES) throw new IllegalArgumentException("Request-data projection exceeds the byte limit");
        return result;
    }

    public ObjectNode events(Trace trace, Map<String, String> params) {
        for (String key : params.keySet()) if (!Arrays.asList("after", "through", "limit", "maxBytes", "kind").contains(key))
            throw new IllegalArgumentException("Unknown event-page parameter");
        long last = trace.lastSequence(), after = number(params, "after", 0, 0, last);
        long through = number(params, "through", last, after, last);
        int limit = (int) number(params, "limit", 500, 1, 5000);
        int maxBytes = (int) number(params, "maxBytes", 262144, 4096, MAX_PAGE_BYTES);
        String kind = params.containsKey("kind") ? params.get("kind") : "all";
        if (!Arrays.asList("all", "methods", "boundaries").contains(kind)) throw new IllegalArgumentException("Invalid event-page kind");
        ObjectNode result = identity(trace);
        result.put("through", through);
        result.put("nextAfter", after);
        result.put("hasMore", false);
        result.put("partial", trace.isTruncated());
        result.put("captureMode", trace.captureMode());
        result.put("detailTruncated", trace.isTruncated());
        result.set("dropReasons", JSON.valueToTree(trace.dropReasons()));
        result.put("omittedDetailCallbacks", trace.omittedDetailCallbacks());
        ArrayNode rows = result.putArray("events"), warnings = result.putArray("warnings");
        int remaining = maxBytes - encodedSize(result) - 2048;
        if (remaining < 0) throw new IllegalArgumentException("Event-page byte budget cannot fit metadata");
        List<TraceEvent> candidates = trace.eventSlice(after, through, limit + 1, kind);
        long next = after;
        int skipped = 0;
        boolean more = false, gapPending = false;
        for (TraceEvent event : candidates) {
            if (rows.size() >= limit) { more = true; break; }
            ObjectNode item = event(event, false);
            if (gapPending) item.put("detailGapBefore", true);
            int bytes = encodedSize(item) + 1;
            if (bytes > remaining) {
                if (rows.size() > 0) { more = true; break; }
                next = event.getSeq();
                skipped++;
                gapPending = true;
                continue;
            }
            rows.add(item);
            gapPending = false;
            remaining -= bytes;
            next = event.getSeq();
        }
        if (!more && candidates.size() == limit + 1) more = next < through;
        if (!more) next = through;
        result.put("nextAfter", next);
        result.put("hasMore", more);
        if (skipped > 0) {
            result.put("partial", true);
            warnings.add(skipped + " oversized retained event(s) skipped to make cursor progress; increase maxBytes up to 1048576 to inspect them.");
        }
        if (trace.isTruncated()) warnings.add("Some detail was not captured; inspect dropReasons. Retained pages do not restore omitted events.");
        if (encodedSize(result) > maxBytes) throw new IllegalArgumentException("Event-page byte budget cannot fit metadata");
        return result;
    }

    private static ObjectNode event(TraceEvent source, boolean compact) {
        ObjectNode event = JSON.valueToTree(source);
        event.put("eventIndex", source.getSeq() - 1);
        if (compact) {
            event.remove("message");
            ObjectNode attributes = event.putObject("attributes");
            source.getAttributes().forEach((key, value) -> {
                if (BOUNDARY_ATTRIBUTES.contains(key)) attributes.put(key, value.substring(0, Math.min(value.length(), 512)));
            });
            String sql = source.getAttributes().get("sql");
            if (sql != null) {
                attributes.put("sqlPreview", sql.substring(0, Math.min(sql.length(), 256)));
                attributes.put("sqlPreviewTruncated", sql.length() > 256);
                attributes.put("sqlDetailsAvailable", true);
            }
            if (source.getAttributes().containsKey("sqlParameters") || source.getAttributes().containsKey("sqlParameterSets"))
                { attributes.put("sqlParametersStatus", "omitted-overview"); attributes.put("sqlParametersAvailable", true); }
        }
        return event;
    }

    private static ObjectNode identity(Trace trace) {
        ObjectNode result = JSON.createObjectNode();
        result.put("projectionVersion", 1);
        result.put("traceId", trace.getTraceId());
        result.put("segmentId", trace.getSegmentId());
        result.put("serviceInstanceId", trace.getAttributes().getOrDefault("serviceInstanceId", ""));
        return result;
    }

    private static int encodedSize(Object value) {
        try { return JSON.writeValueAsBytes(value).length; }
        catch (IOException invalid) { throw new IllegalArgumentException("Cannot encode retained evidence", invalid); }
    }
    public static String serialize(ObjectNode value) throws IOException { return JSON.writeValueAsString(value); }
    private static long number(Map<String, String> params, String key, long fallback, long min, long max) {
        String raw = params.get(key);
        if (raw == null) return fallback;
        if (!raw.matches("[0-9]{1,19}")) throw new IllegalArgumentException("Invalid event-page " + key);
        try {
            long value = Long.parseLong(raw);
            if (value < min || value > max) throw new IllegalArgumentException("Invalid event-page " + key);
            return value;
        } catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid event-page " + key); }
    }
    public static Map<String, String> query(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return result;
        if (raw.length() > 1024) throw new IllegalArgumentException("Event-page query too long");
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            try {
                String key = URLDecoder.decode(parts[0], "UTF-8");
                if (parts.length != 2 || result.put(key, URLDecoder.decode(parts[1], "UTF-8")) != null)
                    throw new IllegalArgumentException("Invalid or repeated event-page parameter");
            } catch (java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
        }
        return result;
    }
}
