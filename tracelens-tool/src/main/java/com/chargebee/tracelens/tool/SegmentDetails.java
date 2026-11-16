package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Identity-pinned bounded detail reads. Cursor failures never restart from live data. */
final class SegmentDetails {
    static final int PAGE_BYTES = 256 * 1024, MAX_PAGE_BYTES = 1024 * 1024, PAGE_EVENTS = 500;
    private final ToolConfig config;
    private final Federation federation;
    private final DebugClient single;
    SegmentDetails(ToolConfig config, Federation federation, DebugClient single) {
        this.config = config; this.federation = federation; this.single = single;
    }
    private DebugClient client(String service) {
        if (!config.federated) return single;
        DebugClient result = federation.clients.get(service);
        if (result == null) throw new IllegalArgumentException("Choose a configured service");
        return result;
    }
    static void id(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Invalid segment identity");
    }
    static void identity(JsonNode doc, String id, String instance, String traceId) {
        if (!id.equals(Json.text(doc, "segmentId")) || instance != null && !instance.equals(Json.text(doc, "serviceInstanceId"))
                || traceId != null && !traceId.equals(Json.text(doc, "traceId")))
            throw new ToolException(409, "Segment identity changed or does not match the requested saved evidence.");
    }
    static long number(JsonNode value, String field) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0
                || value.longValue() > 9007199254740991L) throw new ToolException(502, "Invalid detail " + field + ".");
        return value.longValue();
    }
    static long parameter(Map<String, String> params, String key, long fallback, long maximum) {
        if (!params.containsKey(key)) return fallback;
        String value = params.get(key);
        if (!value.matches("[0-9]{1,16}")) throw new IllegalArgumentException("Invalid detail coordinate");
        long result = Long.parseLong(value);
        if (result > maximum) throw new IllegalArgumentException("Detail coordinate exceeds the limit");
        return result;
    }
    private ObjectNode tag(ObjectNode doc, String service) {
        String name = config.federated ? service : config.services.get(0).name;
        doc.put("service", name);
        doc.put("segmentKey", Json.stringify(Json.arr(name, doc.path("serviceInstanceId"), doc.path("segmentId"))));
        return doc;
    }
    ObjectNode overview(String id, String service, String instance) {
        id(id); ObjectNode doc = client(service).overview(id);
        identity(doc, id, instance, null); number(doc.get("through"), "high-water");
        if (!doc.path("events").isArray()) throw new ToolException(502, "Invalid compact overview events.");
        return tag(doc, service);
    }
    ObjectNode requestData(String id, String service, String instance, String traceId) {
        id(id); ObjectNode doc = client(service).requestData(id);
        identity(doc, id, instance, traceId); return tag(doc, service);
    }
    ObjectNode enrichForCapture(ObjectNode page,String service,int maxBytes) {
        String root=config.federated?federation.sourceRoot(service):config.sourceRoot;
        return root==null?page:new Source(java.nio.file.Paths.get(root)).enrichBounded(page,maxBytes);
    }
    ObjectNode events(String id, String service, String instance, String traceId, long after, long through,
                      int limit, int maxBytes, String kind) {
        id(id); validateParameters(after, through, limit, maxBytes, kind);
        ObjectNode doc = client(service).events(id, after, through, limit, maxBytes, kind);
        validatePage(doc, id, instance, traceId, after, through, limit, kind);
        doc.put("after", after);
        ObjectNode tagged=tag(doc, service);
        if(Captures.bytes(tagged).length>maxBytes)throw new ToolException(502,"Tagged event page exceeds the requested byte budget.");
        return tagged;
    }
    static void validateParameters(long after, long through, int limit, int maxBytes, String kind) {
        if (after < 0 || through < after || through > 9007199254740991L || limit < 1 || limit > 1000
                || maxBytes < 4096 || maxBytes > MAX_PAGE_BYTES || !Arrays.asList("all", "methods", "boundaries").contains(kind))
            throw new IllegalArgumentException("Invalid event page limits");
    }
    static void validatePage(JsonNode doc, String id, String instance, String traceId, long after, long through,
                             int limit, String kind) {
        identity(doc, id, instance, traceId);
        if (number(doc.get("through"), "high-water") != through || !doc.path("events").isArray()
                || doc.path("events").size() > limit || !doc.path("hasMore").isBoolean() || !doc.path("partial").isBoolean()
                || !doc.path("warnings").isArray() || doc.path("warnings").size()>256)
            throw new ToolException(502, "Invalid event page envelope.");
        long next = number(doc.get("nextAfter"), "cursor"), previous = after, previousIndex = -1;
        if (next < after || next > through || doc.path("hasMore").asBoolean() && (next <= after || next >= through))
            throw new ToolException(502, "Invalid or non-progressing event cursor.");
        if(!doc.path("hasMore").asBoolean()&&next<through&&!doc.path("partial").asBoolean())
            throw new ToolException(502,"Incomplete terminal page must explicitly report partial evidence.");
        for (JsonNode event : doc.path("events")) {
            long seq = number(event.get("seq"), "sequence"), index = number(event.get("eventIndex"), "event index");
            if (seq <= previous || seq > next || index <= previousIndex)
                throw new ToolException(502, "Invalid or reordered event evidence.");
            previous = seq; previousIndex = index;
        }
    }
}
