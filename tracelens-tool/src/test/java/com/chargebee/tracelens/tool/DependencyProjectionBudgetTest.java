package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DependencyProjectionBudgetTest {
    private static ObjectNode journey(int app, int ems, int ledger, int go) {
        ArrayNode segments = Json.arr(), calls = Json.arr();
        String[] services = {"cb-app", "cb-ems", "cb-consumption-ledger", "cb-grant-orchestrator"};
        int[] counts = {app, ems, ledger, go};
        for (int index = 0; index < services.length; index++) {
            String service = services[index];
            segments.add(Json.obj("segmentKey", service, "segmentId", service + "-segment", "service", service));
            for (int call = 0; call < counts[index]; call++) calls.add(Json.obj(
                    "callKey", service + ":dependency:" + call, "ownerSegmentKey", service,
                    "service", service, "eventSeq", call + 1, "boundaryKind", "database", "system", "mysql",
                    "direction", "outbound", "evidenceKind", "client-boundary", "operation", "executeQuery", "status", "OBSERVED"));
        }
        return Json.obj("segments", segments, "externalCalls", calls);
    }

    private static Map<String, Integer> counts(JsonNode graph) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (JsonNode node : graph.path("nodes")) if ("dependency-call".equals(node.path("kind").asText())) {
            String service = node.path("service").asText(); counts.put(service, counts.getOrDefault(service, 0) + 1);
            assertTrue(node.hasNonNull("ownerId"), "Every retained dependency must retain its explicit owner");
            assertEquals("not-observed", node.path("downstreamState").asText());
        }
        return counts;
    }

    @Test void appHeavyMultiServiceTraceRetainsDownstreamBoundariesBeyondOld128Limit() {
        ObjectNode input = journey(96, 9, 47, 17), before = input.deepCopy();
        ObjectNode graph = Visualization.build(input);
        Map<String, Integer> counts = counts(graph);
        assertEquals(96, counts.get("cb-app")); assertEquals(9, counts.get("cb-ems"));
        assertEquals(47, counts.get("cb-consumption-ledger")); assertEquals(17, counts.get("cb-grant-orchestrator"));
        assertEquals(169, graph.path("edges").size());
        assertFalse(graph.path("warnings").toString().contains("limit reached"));
        assertEquals(before, input);
        ArrayNode reverse = Json.arr();
        for (int i = input.path("externalCalls").size() - 1; i >= 0; i--) reverse.add(input.path("externalCalls").get(i));
        input.set("externalCalls", reverse);
        assertEquals(graph, Visualization.build(input), "Projection must not depend on input arrival order");
    }

    @Test void exact512BoundaryBudgetRetainsEveryServiceAnd513IsExplicitlyLimited() {
        ObjectNode full = Visualization.build(journey(480, 8, 12, 12));
        assertEquals(512, Visualization.MAX_DEPENDENCIES);
        assertEquals(4, counts(full).size());
        assertEquals(516, full.path("nodes").size()); assertEquals(512, full.path("edges").size());
        assertFalse(full.path("warnings").toString().contains("dependency-call limit reached"));
        ObjectNode limited = Visualization.build(journey(481, 8, 12, 12));
        assertEquals(516, limited.path("nodes").size());
        assertTrue(limited.path("warnings").toString().contains("Visualization dependency-call limit reached (512); evidence is incomplete"));
        assertTrue(limited.path("partial").asBoolean());
    }
}
