package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Federation contracts ported from test_services.py using inert upstream doubles. */
class ParityEvidenceFederationTest {
    private static final String TRACE = "1234567890abcdef1234567890abcdef";

    private static ObjectNode segment(String id, String instance, String parent) {
        return Json.obj("traceId", TRACE, "segmentId", id, "serviceInstanceId", instance,
                "spanId", id + "-span", "parentSpanId", parent, "entryChannel", "HTTP",
                "status", "COMPLETED", "startedAtMillis", 2000000000000L,
                "lastActivityMillis", 2000000000100L, "httpMethod", "GET", "path", "/features",
                "user", "alice", "attributes", Json.obj(), "events", Json.arr(), "httpCalls", Json.arr());
    }

    private static class Upstream extends DebugClient {
        ObjectNode list, exported;
        int lookupStatus, exports, listings;
        boolean offline;
        Upstream(ObjectNode segment) {
            super("http://127.0.0.1:1");
            exported = segment;
            list = Json.obj("traces", Json.arr(segment.deepCopy()));
        }
        @Override public ObjectNode traces() { checkOnline(); listings++; return list.deepCopy(); }
        @Override public ObjectNode summary() { checkOnline(); return Json.obj("traceCount", list.path("traces").size()); }
        private void checkOnline() { if (offline) throw new ToolException(503, "synthetic offline service"); }
        @Override public ObjectNode findByTraceId(String id) {
            checkOnline();
            assertEquals(TRACE, id);
            if (lookupStatus != 0) throw new ToolException(lookupStatus, "synthetic failure");
            return list.deepCopy();
        }
        @Override public ObjectNode export(String id) {
            assertEquals(exported.path("segmentId").asText(), id);
            exports++;
            return exported.deepCopy();
        }
        @Override public ObjectNode overview(String id) { throw new ToolException(404, "Legacy agent does not support overview"); }
    }

    private static Federation federation(Upstream app, Upstream ems) {
        Federation f = new Federation(Arrays.asList(
                new Service("cb-app", "http://127.0.0.1:1", "/tmp/app-checkout",
                        Json.obj("ems:8082", "cb-ems")),
                new Service("cb-ems", "http://127.0.0.1:2", null)));
        f.clients.put("cb-app", app);
        f.clients.put("cb-ems", ems);
        return f;
    }

    @Test void joinsThroughExplicitSpanKeepsAliasesAndTypedSystemsWithoutPayloads() {
        ObjectNode app = segment("app", "app-instance", null);
        app.set("httpCalls", Json.arr(Json.obj("spanId", "http-span", "peer", "ems:8082",
                "httpMethod", "GET", "path", "/features", "httpStatus", 200)));
        ObjectNode ems = segment("ems", "ems-instance", "http-span");
        ArrayNode events = Json.arr();
        for (String[] boundary : new String[][]{
                {"database", "clickhouse", "query"}, {"cache", "redis", "get"},
                {"messaging", "sqs", "sendMessage"}}) {
            events.add(Json.obj("seq", events.size() + 1, "thread", "worker-1", "type", "EXTERNAL_CALL",
                    "attributes", Json.obj("boundaryKind", boundary[0], "system", boundary[1],
                            "operation", boundary[2], "direction", "messaging".equals(boundary[0]) ? "publish" : "outbound",
                            "status", "OBSERVED", "evidenceKind", "client-boundary",
                            "payload", "PRIVATE", "sql", "PRIVATE", "key", "PRIVATE")));
        }
        ems.set("events", events);
        Upstream appClient = new Upstream(app), emsClient = new Upstream(ems);
        Federation f = federation(appClient, emsClient);
        ObjectNode result = f.getDistributedTrace(TRACE);
        assertEquals(2, result.path("segments").size());
        assertEquals(1, result.path("links").size());
        assertEquals("cb-ems", result.path("httpCalls").get(0).path("destinationName").asText());
        assertEquals("observed", result.path("httpCalls").get(0).path("downstreamState").asText());
        assertEquals(3, result.path("externalCalls").size());
        assertFalse(result.toString().contains("PRIVATE"));
        assertEquals(1, appClient.exports);
        assertEquals(1, emsClient.exports);
        for (JsonNode call : result.path("externalCalls"))
            assertEquals("not-observed", call.path("downstreamState").asText());
        assertEquals("/tmp/app-checkout", f.sourceRoot("cb-app"));
        assertNull(f.sourceRoot("cb-ems"), "Missing roots must never use another service's checkout");
    }

    @Test void legacyLookupFallsBackOnlyOn404AndMissingEventsAreAnEmptyLegacyCapture() {
        Upstream app = new Upstream(segment("app", "a", null));
        Upstream ems = new Upstream(segment("ems", "e", null));
        app.lookupStatus = 404;
        app.exported.remove("events");
        ems.list.set("traces", Json.arr());
        Federation f = federation(app, ems);
        ObjectNode result = f.getDistributedTrace(TRACE);
        assertEquals(1, app.listings);
        assertEquals(1, result.path("segments").size());
        assertFalse(result.path("partial").asBoolean(), "Older exports without event collections must remain readable");
        app.lookupStatus = 503;
        app.listings = 0;
        result = f.getDistributedTrace(TRACE);
        assertEquals(0, app.listings);
        assertEquals(0, result.path("segments").size());
        assertEquals(1, result.path("errors").size());
        assertTrue(result.path("partial").asBoolean());
    }

    @Test void anInstanceOrTraceChangeCannotAttachStaleEvidence() {
        Upstream app = new Upstream(segment("app", "old", null));
        Upstream ems = new Upstream(segment("ems", "e", null));
        ems.list.set("traces", Json.arr());
        Federation f = federation(app, ems);
        app.exported.put("serviceInstanceId", "new");
        assertThrows(ToolException.class, () -> f.export("app", "cb-app", "old"));
        ObjectNode changed=f.getDistributedTrace(TRACE);
        assertEquals(1, changed.path("segments").size());
        assertFalse(changed.path("segments").get(0).path("detailsAvailable").asBoolean());
        assertEquals("old",changed.path("segments").get(0).path("serviceInstanceId").asText());
        assertEquals(0,changed.path("links").size());
        app.exported.put("serviceInstanceId", "old");
        app.exported.put("traceId", "different-trace");
        changed=f.getDistributedTrace(TRACE);
        assertEquals(1, changed.path("segments").size());
        assertEquals(TRACE,changed.path("segments").get(0).path("traceId").asText());
        assertFalse(changed.path("segments").get(0).path("detailsAvailable").asBoolean());
    }

    @Test void repeatedCallSpanAndRepeatedServicesRemainExplicitlyAmbiguous() {
        ObjectNode appRow = segment("app", "a", null);
        appRow.set("httpCalls", Json.arr(Json.obj("spanId", "same"), Json.obj("spanId", "same")));
        Federation f = federation(new Upstream(appRow), new Upstream(segment("ems", "e", "same")));
        ObjectNode result = f.getDistributedTrace(TRACE);
        assertEquals(2, result.path("httpCalls").size());
        assertEquals(0, result.path("links").size());
        assertTrue(result.path("warnings").toString().contains("Ambiguous parent"));
        for (JsonNode call : result.path("httpCalls"))
            assertEquals("not-observed", call.path("downstreamState").asText());
    }

    @Test void listingFiltersPreserveUpstreamUncertaintyAndUseRootAttributes() {
        ObjectNode appRow = segment("app", "a", null);
        appRow.putNull("httpMethod");
        appRow.putNull("path");
        appRow.set("attributes", Json.obj("httpMethod", "POST", "path", "/api/v2/features"));
        Upstream app = new Upstream(appRow), ems = new Upstream(segment("ems", "e", null));
        app.list.put("partial", true);
        app.list.put("limited", true);
        Federation f = federation(app, ems);
        ObjectNode result = f.traces(Json.obj("method", "post", "path", "FEATURES", "user", "ALICE"));
        assertEquals(1, result.path("count").asInt());
        assertEquals("cb-app", result.path("traces").get(0).path("service").asText());
        assertTrue(result.path("partial").asBoolean());
        assertTrue(result.path("limited").asBoolean());
        assertEquals(0, f.traces(Json.obj("method", "DELETE")).path("count").asInt());
        assertTrue(f.traces(Json.obj("method", "DELETE")).path("partial").asBoolean());
        assertThrows(IllegalArgumentException.class, () -> f.traces(Json.obj("service", "unconfigured")));
    }

    @Test void registryCanonicalizationPreservesEncodedPathIdentity() {
        assertDoesNotThrow(() -> new Federation(Arrays.asList(
                new Service("literal", "http://localhost/debug/a/b", null),
                new Service("encoded", "http://localhost/debug/a%2Fb", null))));
        assertThrows(IllegalArgumentException.class, () -> new Federation(Arrays.asList(
                new Service("first", "http://LOCALHOST:80/debug/", null),
                new Service("second", "http://localhost/debug", null))));
    }

    @Test void oneOfflineServiceDoesNotHideHealthyServicesAndRecoversWithoutRegistryChanges() {
        Upstream app = new Upstream(segment("app", "a", null));
        Upstream ems = new Upstream(segment("ems", "e", null));
        ems.offline = true;
        Federation f = federation(app, ems);
        ObjectNode summary = f.summary();
        assertEquals(1, summary.path("services").size());
        assertEquals("cb-app", summary.path("services").get(0).path("service").asText());
        assertEquals("cb-ems", summary.path("errors").get(0).path("service").asText());
        assertTrue(summary.path("partial").asBoolean());
        ObjectNode traces = f.traces();
        assertEquals(1, traces.path("count").asInt());
        assertEquals("cb-app", traces.path("traces").get(0).path("service").asText());
        assertTrue(traces.path("partial").asBoolean());
        ObjectNode journey = f.getDistributedTrace(TRACE);
        assertEquals(1, journey.path("segments").size());
        assertEquals("cb-app", journey.path("segments").get(0).path("service").asText());
        assertTrue(journey.path("partial").asBoolean());
        ems.offline = false;
        assertEquals(2, f.summary().path("services").size());
        assertEquals(2, f.traces().path("count").asInt());
        assertEquals(2, f.getDistributedTrace(TRACE).path("segments").size());
        assertEquals(0, f.summary().path("errors").size());
    }
}
