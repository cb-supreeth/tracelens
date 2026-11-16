package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Production-size budgets omitted from the compact Python golden fixture set. */
class ParityEvidenceLimitsTest {
    private static ObjectNode event(int seq, String type) {
        return Json.obj("seq", seq, "nanoTime", 1000000L * seq, "thread", "worker",
                "type", type, "className", "com.example.Service", "methodName", "run",
                "attributes", Json.obj());
    }
    private static ObjectNode trace(ArrayNode events) {
        return Json.obj("status", "COMPLETED", "eventCount", events.size(), "events", events);
    }
    private static boolean warning(JsonNode model, String code) {
        for (JsonNode row : model.path("warnings")) if (code.equals(Json.text(row, "code"))) return true;
        return false;
    }
    private static void referencesResolve(JsonNode model) {
        Set<String> nodes = new HashSet<>(), lanes = new HashSet<>();
        for (JsonNode node : model.path("nodes")) nodes.add(Json.text(node, "id"));
        for (JsonNode lane : model.path("lanes")) lanes.add(Json.text(lane, "id"));
        for (JsonNode node : model.path("nodes")) {
            assertTrue(lanes.contains(Json.text(node, "laneId")));
            assertTrue(!node.hasNonNull("parentId") || nodes.contains(Json.text(node, "parentId")));
        }
    }

    @Test void eventProcessingLimitDoesNotInventTheUnprocessedReturn() {
        ArrayNode events = Json.arr(event(1, "METHOD_ENTER"));
        for (int i = 2; i <= Execution.MAX_EVENTS; i++) events.add(event(i, "MARKER"));
        events.add(event(Execution.MAX_EVENTS + 1, "METHOD_EXIT"));
        ObjectNode model = Execution.build(trace(events), 200);
        assertEquals(Execution.MAX_EVENTS, model.path("counts").path("eventsProcessed").asInt());
        assertEquals(1, model.path("counts").path("eventsOmitted").asInt());
        assertFalse(model.path("nodes").get(0).path("complete").asBoolean());
        assertTrue(model.path("nodes").get(0).path("durationMs").isNull());
        assertTrue(warning(model, "event_processing_limit"));
    }

    @Test void depthAndLaneLimitsStopInsteadOfInventingNesting() {
        ArrayNode events = Json.arr();
        for (int i = 1; i <= Execution.MAX_DEPTH + 1; i++) events.add(event(i, "METHOD_ENTER"));
        ObjectNode model = Execution.build(trace(events), 500);
        assertEquals(Execution.MAX_DEPTH, model.path("counts").path("eventsProcessed").asInt());
        assertTrue(warning(model, "depth_processing_limit"));
        for (JsonNode node : model.path("nodes")) assertFalse(node.path("complete").asBoolean());
        referencesResolve(model);
        events = Json.arr();
        for (int i = 1; i <= Execution.MAX_LANES + 1; i++)
            events.add(event(i, "METHOD_ENTER").put("thread", "thread-" + i));
        model = Execution.build(trace(events), 200);
        assertEquals(Execution.MAX_LANES, model.path("lanes").size());
        assertTrue(warning(model, "lane_processing_limit"));
        for (JsonNode node : model.path("nodes")) assertTrue(node.path("parentId").isNull());
        referencesResolve(model);
    }

    @Test void asciiEscapedUnicodeHonorsBothMcpAndViewerByteBudgets() {
        ArrayNode events = Json.arr();
        String longName = String.join("", Collections.nCopies(256, "界"));
        for (int i = 0; i < 500; i++) {
            events.add(event(i * 2 + 1, "METHOD_ENTER").put("className", longName));
            events.add(event(i * 2 + 2, "METHOD_EXIT").put("className", longName));
        }
        int previous = 0;
        for (int budget : new int[]{Execution.MODEL_MAX_BYTES, Execution.VIEWER_MODEL_MAX_BYTES}) {
            ObjectNode model = Execution.build(trace(events), 500, budget);
            assertTrue(Evidence.canonical(model).length() <= budget);
            assertTrue(warning(model, "model_byte_limit"));
            assertEquals(events.size(), model.path("counts").path("eventsProcessed").asInt());
            assertTrue(model.path("nodes").size() >= previous);
            previous = model.path("nodes").size();
            referencesResolve(model);
        }
    }

    @Test void dependencyAndGraphDisplayCapsRetainVisibleIncompleteness() {
        ArrayNode events = Json.arr();
        for (int i = 1; i <= Dependencies.MAX_EXTERNAL_CALLS + 1; i++)
            events.add(Json.obj("seq", i, "thread", "worker", "type", "DB_ROUTE",
                    "attributes", Json.obj("route", "TEST_MASTER")));
        ObjectNode boundaries = Dependencies.extractBoundaries(events);
        assertEquals(Dependencies.MAX_EXTERNAL_CALLS, boundaries.path("externalCalls").size());
        assertTrue(boundaries.path("externalCallsTruncated").asBoolean());
        ObjectNode segment = Json.obj("segmentKey", "ems", "service", "cb-ems", "segmentId", "one",
                "events", events, "httpCalls", Json.arr());
        ObjectNode journey = Federation.joinTrace("trace", Json.arr(segment), Json.arr());
        assertEquals(Dependencies.MAX_EXTERNAL_CALLS, journey.path("externalCalls").size());
        ObjectNode graph = Visualization.build(journey);
        assertEquals(Visualization.MAX_DEPENDENCIES + 1, graph.path("nodes").size());
        assertTrue(graph.path("partial").asBoolean());
        assertTrue(graph.path("warnings").toString().contains("limit reached"));
    }

    @Test void errorGroupingCapCannotClaimCompleteRetainedCoverage() {
        ObjectNode row = Json.obj("traceId", "11111111111111111111111111111111", "segmentId", "one",
                "service", "app", "httpStatus", 500, "startedAtMillis", 10000);
        ArrayNode rows = Json.arr();
        for (int i = 0; i <= ErrorGroups.MAX_SEGMENTS; i++) rows.add(row);
        ObjectNode result = ErrorGroups.build(Json.obj("traces", rows), Json.obj("since_minutes", null), 20000);
        assertTrue(result.path("partial").asBoolean());
        assertTrue(result.path("limited").asBoolean());
        assertEquals(1, result.path("groups").size());
        assertEquals(1, result.path("counts").path("matchingRequests").asInt());
        assertTrue(result.path("warnings").toString().contains("10000 segments"));
    }
}
