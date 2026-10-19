package com.chargebee.tracelens.core.export;

import com.chargebee.tracelens.core.config.TraceLensConfig;
import com.chargebee.tracelens.core.trace.Trace;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Serializes a trace to the export JSON format: metadata header + ordered timeline.
 * This is the artifact the CLI renders, the MCP server serves, and (later) the
 * deployed backend indexes.
 */
public final class TraceExporter {

    private final ObjectMapper mapper;
    private final TraceLensConfig config;

    public TraceExporter(TraceLensConfig config) {
        this(config, false);
    }

    public TraceExporter(TraceLensConfig config, boolean pretty) {
        this.config = config;
        this.mapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        if (pretty) {
            this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        }
    }

    public String export(Trace trace) {
        try {
            return mapper.writeValueAsString(toJson(trace));
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize trace " + trace.getTraceId(), e);
        }
    }

    public ObjectNode toJson(Trace trace) {
        ObjectNode root = mapper.createObjectNode();
        root.put("traceId", trace.getTraceId());
        root.put("segmentId", trace.getSegmentId());
        root.put("schemaVersion", 2);
        putIfNotNull(root, "spanId", trace.getAttributes().get("spanId"));
        putIfNotNull(root, "parentSpanId", trace.getAttributes().get("parentSpanId"));
        putIfNotNull(root, "serviceInstanceId", trace.getAttributes().get("serviceInstanceId"));
        putIfNotNull(root, "parentSegmentId", trace.getParentSegmentId());
        putIfNotNull(root, "lensId", trace.getLensId());
        putIfNotNull(root, "user", trace.getUser());
        root.put("entryChannel", trace.getEntryChannel().name());
        root.put("applicationId", config.getApplicationId());
        root.put("applicationVersion", config.getApplicationVersion());
        root.put("status", trace.getStatus().name());
        root.put("startedAt", trace.getStartedAtMillis());
        root.put("startNanos", trace.getStartNanos());
        if (trace.getEndedAtMillis() != null) {
            root.put("endedAt", trace.getEndedAtMillis().longValue());
            root.put("durationMs", trace.durationMillis().longValue());
        }
        root.put("eventCount", trace.eventCount());
        root.put("truncated", trace.isTruncated());
        root.put("httpCallsTruncated", trace.isHttpCallsTruncated());
        root.set("httpCalls", mapper.valueToTree(trace.httpCalls()));

        ObjectNode attributes = root.putObject("attributes");
        trace.getAttributes().forEach(attributes::put);

        ObjectNode contextParams = root.putObject("contextParams");
        for (String jvmArg : config.getContextParams().getJvmArgs()) {
            String value = System.getProperty(jvmArg);
            if (value != null) {
                contextParams.put(jvmArg, value);
            }
        }

        root.set("events", mapper.valueToTree(trace.events()));
        return root;
    }

    private static void putIfNotNull(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }
}
