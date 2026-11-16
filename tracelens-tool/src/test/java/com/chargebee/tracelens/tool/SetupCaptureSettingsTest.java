package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SetupCaptureSettingsTest {
    @Test void whitelistAcceptsIndependentCaptureControlsAndDoesNotDropUnknownConfig() {
        ObjectNode original = Json.obj("applicationId", "test", "custom", Json.obj("preserve", true));
        ObjectNode changes = Json.obj("captureMode", "methods", "detailedCapturePaths", Json.arr("/api"),
                "detailedCaptureUntilEpochMillis", 4102444800000L, "packages", Json.arr("com.example"),
                "excludedPackages", Json.arr("com.example.generated"), "trackMethods", false, "trackExceptions", false,
                "instrumentExecutors", false, "instrumentScheduled", false, "chargebeeJobTypes", Json.arr("billing_job"),
                "captureHttpQueryParameters", true, "captureHttpRequestBodies", true, "redactHttpPayloads", false,
                "maxHttpPayloadLength", 16384, "httpClientCaptureEnabled", true, "distributedTracingEnabled", true,
                "httpPropagationAllowedDestinations", Json.arr("localhost:8080", "[::1]:8081"),
                "captureDataStoreBoundaries", false, "captureMessagingBoundaries", false,
                "captureJdbcQueries", true, "captureJdbcParameters", true, "captureClickHouseQueries", true,
                "captureClickHouseParameters", true, "maxSqlLength", 16384, "maxSqlQueriesPerRequest", 1000,
                "maxTrackedBytes", 2147483648L, "maxSegmentBytes", 268435456L, "maxTrackedEvents", 1000L,
                "maxTrackedRequests", 1, "idleTtlMinutes", 1L, "httpRetentionPercent", 0);
        ArrayNode review = Json.arr(); ObjectNode result = SetupCaptureSettings.merge(original, changes, review);
        assertEquals(changes.size(), review.size()); assertEquals(original.path("custom"), result.path("custom"));
        changes.fields().forEachRemaining(field -> assertEquals(field.getValue(), result.get(field.getKey()), field.getKey()));
        assertEquals(2, original.size());
    }

    @Test void rejectsMalformedTypesBoundsUnknownKeysAndUnsafeScope() {
        for (JsonNode input : Arrays.asList(Json.arr(), Json.obj("viewer", Json.obj()), Json.obj("capturePayloads", true),
                Json.obj("captureMode", "invalid"), Json.obj("captureMode", true), Json.obj("trackExceptions", "false"),
                Json.obj("maxSqlLength", 1.5), Json.obj("maxSqlLength", 0), Json.obj("maxSqlLength", 16385),
                Json.obj("maxHttpPayloadLength", 16385), Json.obj("maxSqlQueriesPerRequest", 1001),
                Json.obj("maxTrackedBytes", 2147483649L), Json.obj("maxTrackedBytes", 1048576),
                Json.obj("maxSegmentBytes", 1048575), Json.obj("maxSegmentBytes", 268435457),
                Json.obj("maxTrackedEvents", 999), Json.obj("maxTrackedRequests", 0), Json.obj("idleTtlMinutes", 0),
                Json.obj("httpRetentionPercent", 100), Json.obj("httpRetentionPercent", -1),
                Json.obj("detailedCaptureUntilEpochMillis", 4102444800001L), Json.obj("detailedCaptureUntilEpochMillis", -1),
                Json.obj("captureJdbcParameters", true), Json.obj("captureClickHouseParameters", true),
                Json.obj("packages", Json.arr("com.example.*")), Json.obj("excludedPackages", Json.arr("bad package")),
                Json.obj("chargebeeJobTypes", Json.arr("*")), Json.obj("chargebeeJobTypes", Json.arr("same", "same")),
                Json.obj("detailedCapturePaths", Json.arr("/api?token=x")), Json.obj("detailedCapturePaths", Json.arr("/api*")),
                Json.obj("httpPropagationAllowedDestinations", Json.arr("https://localhost:8080")),
                Json.obj("httpPropagationAllowedDestinations", Json.arr("localhost:0")),
                Json.obj("httpPropagationAllowedDestinations", Json.arr("user@localhost:8080")),
                Json.obj("httpPropagationAllowedDestinations", Json.arr("*.example.com")), Json.obj("packages", null))) {
            assertThrows(IllegalArgumentException.class, () -> SetupCaptureSettings.merge(Json.obj(), input, Json.arr()), Json.stringify(input));
        }
    }

    @Test void boundedArraysAndNumericRoundTripsDoNotIntroduceImplicitChanges() {
        ArrayNode jobs = Json.arr(), destinations = Json.arr();
        for (int index = 0; index < 33; index++) jobs.add("job_" + index);
        for (int index = 0; index < 65; index++) destinations.add("host" + index + ":8080");
        assertThrows(IllegalArgumentException.class, () -> SetupCaptureSettings.merge(Json.obj(), Json.obj("chargebeeJobTypes", jobs), Json.arr()));
        assertThrows(IllegalArgumentException.class, () -> SetupCaptureSettings.merge(Json.obj(), Json.obj("httpPropagationAllowedDestinations", destinations), Json.arr()));
        ObjectNode original = Json.obj("instrumentMessaging", false, "distributedTracingEnabled", true);
        JsonNode current = Json.parse(Json.stringify(SetupCaptureSettings.current(original)));
        ArrayNode review = Json.arr();
        assertEquals(original, SetupCaptureSettings.merge(original, current, review)); assertEquals(0, review.size());
        assertFalse(current.path("captureMessagingBoundaries").asBoolean()); assertTrue(current.path("httpClientCaptureEnabled").asBoolean());
    }
}
