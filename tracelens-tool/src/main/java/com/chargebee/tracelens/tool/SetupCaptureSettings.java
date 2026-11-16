package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Bounded setup edits; unknown application configuration is never round-tripped through a bean. */
final class SetupCaptureSettings {
    private static final ObjectNode DEFAULTS = Json.obj(
            "captureMode", "detailed", "detailedCapturePaths", Json.arr(), "detailedCaptureUntilEpochMillis", 0L,
            "packages", Json.arr(), "excludedPackages", Json.arr(), "trackMethods", true, "trackExceptions", true,
            "instrumentExecutors", true, "instrumentScheduled", true, "chargebeeJobTypes", Json.arr(),
            "captureHttpQueryParameters", false, "captureHttpRequestBodies", false, "redactHttpPayloads", true,
            "maxHttpPayloadLength", 4096, "httpClientCaptureEnabled", false, "distributedTracingEnabled", false,
            "httpPropagationAllowedDestinations", Json.arr(), "captureDataStoreBoundaries", true,
            "captureMessagingBoundaries", true, "captureJdbcQueries", false, "captureJdbcParameters", false,
            "captureClickHouseQueries", false, "captureClickHouseParameters", false, "maxSqlLength", 4096,
            "maxSqlQueriesPerRequest", 100, "maxTrackedBytes", 268435456L, "maxSegmentBytes", 33554432L,
            "maxTrackedEvents", 500000L, "maxTrackedRequests", 200, "idleTtlMinutes", 60L, "httpRetentionPercent", 75);
    private static final Set<String> LONGS = new HashSet<String>(Arrays.asList("detailedCaptureUntilEpochMillis",
            "maxTrackedBytes", "maxSegmentBytes", "maxTrackedEvents", "idleTtlMinutes"));

    private SetupCaptureSettings() {}

    static ObjectNode current(JsonNode config) {
        ObjectNode result = DEFAULTS.deepCopy();
        DEFAULTS.fieldNames().forEachRemaining(field -> {
            if (config.hasNonNull(field)) result.set(field, config.get(field).deepCopy());
        });
        if (!config.hasNonNull("httpClientCaptureEnabled"))
            result.put("httpClientCaptureEnabled", config.path("distributedTracingEnabled").asBoolean(false));
        if (!config.hasNonNull("captureMessagingBoundaries"))
            result.put("captureMessagingBoundaries", config.path("instrumentMessaging").asBoolean(true));
        return result;
    }

    static ObjectNode merge(ObjectNode original, JsonNode input, ArrayNode changes) {
        if (input == null || !input.isObject()) throw new IllegalArgumentException("capture must be an object");
        ObjectNode result = original.deepCopy(), before = current(original);
        Iterator<Map.Entry<String, JsonNode>> fields = input.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey(); JsonNode value = field.getValue(), type = DEFAULTS.get(name);
            if (type == null) throw new IllegalArgumentException("Unknown capture setting: " + name);
            if (value == null || value.isNull() || (type.isBoolean() && !value.isBoolean())
                    || (type.isTextual() && !value.isTextual()) || (type.isArray() && !value.isArray())
                    || (type.isIntegralNumber() && (!value.isIntegralNumber() || !value.canConvertToLong()
                    || (!LONGS.contains(name) && !value.canConvertToInt()))))
                throw new IllegalArgumentException(name + " has an invalid value type");
            JsonNode previous = before.get(name);
            boolean same = value.equals(previous) || value.isIntegralNumber() && previous.isIntegralNumber()
                    && value.longValue() == previous.longValue();
            if (!same) {
                result.set(name, value.deepCopy());
                changes.add(Json.obj("field", name, "before", before.get(name), "after", value));
            }
        }
        validate(result);
        return result;
    }

    private static void validate(ObjectNode config) {
        ObjectNode values = current(config);
        String mode = values.path("captureMode").asText();
        if (!Arrays.asList("overview", "methods", "detailed").contains(mode))
            throw new IllegalArgumentException("captureMode must be overview, methods or detailed");
        range(values, "detailedCaptureUntilEpochMillis", 0, 4102444800000L);
        range(values, "maxSegmentBytes", 1048576, 268435456);
        range(values, "maxTrackedBytes", values.path("maxSegmentBytes").asLong(), 2147483648L);
        range(values, "maxTrackedEvents", 1000, Long.MAX_VALUE);
        range(values, "maxTrackedRequests", 1, Integer.MAX_VALUE);
        range(values, "idleTtlMinutes", 1, Long.MAX_VALUE);
        range(values, "httpRetentionPercent", 0, 99);
        range(values, "maxHttpPayloadLength", 1, 16384);
        range(values, "maxSqlLength", 1, 16384);
        range(values, "maxSqlQueriesPerRequest", 1, 1000);
        for (String engine : Arrays.asList("Jdbc", "ClickHouse"))
            if (values.path("capture" + engine + "Parameters").asBoolean() && !values.path("capture" + engine + "Queries").asBoolean())
                throw new IllegalArgumentException(engine + " parameters require query capture");
        for (String field : Arrays.asList("packages", "excludedPackages", "chargebeeJobTypes", "detailedCapturePaths", "httpPropagationAllowedDestinations")) {
            JsonNode list = values.path(field); Set<String> seen = new HashSet<String>();
            int maximum = "chargebeeJobTypes".equals(field) || "detailedCapturePaths".equals(field) ? 32 : 64;
            if (!list.isArray() || list.size() > maximum) throw new IllegalArgumentException(field + " requires at most " + maximum + " values");
            for (JsonNode item : list) {
                if (!item.isTextual() || item.textValue().isEmpty() || !seen.add(item.textValue()))
                    throw new IllegalArgumentException(field + " requires unique nonempty strings");
                String value = item.textValue();
                if ("packages".equals(field) || "excludedPackages".equals(field)) {
                    if (value.length() > 512 || !value.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*\\.?"))
                        throw new IllegalArgumentException(field + " requires literal Java package prefixes");
                } else if ("chargebeeJobTypes".equals(field)) {
                    if (!value.matches("[A-Za-z0-9_.-]{1,128}")) throw new IllegalArgumentException("chargebeeJobTypes requires exact job names");
                } else if ("detailedCapturePaths".equals(field)) {
                    if (!value.startsWith("/") || value.length() > 512 || value.matches(".*[? #*\\x00-\\x20\\x7f].*"))
                        throw new IllegalArgumentException("detailedCapturePaths requires literal path prefixes");
                } else {
                    try {
                        URI uri = new URI("http://" + value);
                        if (value.length() > 255 || value.contains("*") || uri.getHost() == null || uri.getUserInfo() != null
                                || uri.getPort() == 0 || uri.getPort() > 65535 || !value.equals(uri.getRawAuthority())) throw new IllegalArgumentException();
                    } catch (Exception invalid) { throw new IllegalArgumentException("httpPropagationAllowedDestinations requires exact host[:port] authorities"); }
                }
            }
        }
    }

    private static void range(JsonNode values, String field, long minimum, long maximum) {
        JsonNode value = values.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < minimum || value.longValue() > maximum)
            throw new IllegalArgumentException(field + " must be between " + minimum + " and " + maximum);
    }

}
