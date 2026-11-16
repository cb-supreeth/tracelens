package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.List;

/** Request data belongs only to explicit full exports, never discovery/search/MCP summaries. */
final class RequestEvidence {
    private static final List<String> FIELDS=Arrays.asList("httpQueryParameters","httpQueryParametersStatus",
            "httpRequestBody","httpRequestBodyStatus","httpRequestBodyFormat","httpPayloadRedacted","httpPayloadRedaction");
    private RequestEvidence() {}
    static ObjectNode summary(JsonNode source) {
        ObjectNode result=Json.copy(source);removePayload(result);return result;
    }
    static void removePayload(JsonNode node) {
        if(node.isObject()) {((ObjectNode)node).remove(FIELDS);for(JsonNode child:node)removePayload(child);}
        else if(node.isArray())for(JsonNode child:node)removePayload(child);
    }
}
