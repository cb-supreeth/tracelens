package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HttpOrderProjectionTest {
    private ObjectNode journey(ObjectNode call) {
        return Json.obj("segments",Json.arr(Json.obj("segmentKey","app","segmentId","segment","service","cb-app","serviceInstanceId","instance")),
                "httpCalls",Json.arr(call));
    }
    private ObjectNode call() {
        return Json.obj("callKey","call","ownerSegmentKey","app","service","cb-app","serviceInstanceId","instance",
                "startedAt","1793018824000","durationNanos","9123456","timingBoundary","caller-local");
    }
    private JsonNode http(ObjectNode model) {
        for(JsonNode node:model.path("nodes"))if("http-call".equals(node.path("kind").asText()))return node;
        throw new AssertionError("HTTP node absent");
    }
    @Test void retainsCallerLocalTimingWithoutInventingReceiverLinks() {
        ObjectNode call=call(),input=journey(call),before=input.deepCopy();
        ObjectNode model=Visualization.build(input);JsonNode node=http(model);
        assertEquals(1793018824000L,node.path("startedAtMillis").asLong());
        assertEquals(9.123456,node.path("durationMs").asDouble());
        assertEquals("caller-local",node.path("timingBoundary").asText());
        assertEquals("not-observed",node.path("downstreamState").asText());
        assertEquals(1,model.path("edges").size());assertEquals(before,input);
        call.put("startedAtMillis",100);
        assertEquals(100,http(Visualization.build(journey(call))).path("startedAtMillis").asLong());
    }
    @Test void omitsMalformedAndUnsafeTimestamps() {
        for(JsonNode bad:Json.arr(null,true,Json.obj(),Json.arr(),-1,1.5,"NaN","bad",9007199254740992L)) {
            ObjectNode call=call();call.set("startedAt",bad);call.set("timingBoundary",Json.obj("secret","value"));
            JsonNode node=http(Visualization.build(journey(call)));
            assertFalse(node.has("startedAtMillis"),bad.toString());assertFalse(node.has("timingBoundary"));
        }
    }
}
