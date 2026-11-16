package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RequestSearchTest {
    private static ObjectNode identity(String segment) {
        return Json.obj("service", "cb-app", "serviceInstanceId", "instance-1", "segmentId", segment, "traceId", "trace-1");
    }
    private static ObjectNode exported(String segment, ObjectNode attributes) {
        return Json.obj("applicationId", "cb-app", "serviceInstanceId", "instance-1", "segmentId", segment,
                "traceId", "trace-1", "attributes", attributes);
    }
    private static ObjectNode body(String keyword, JsonNode... identities) {
        return Json.obj("keyword", keyword, "segments", Json.arr((Object[]) identities));
    }
    private static ObjectNode captured(String query, String body) {
        return Json.obj("httpQueryParametersStatus", "captured", "httpQueryParameters", query,
                "httpRequestBodyStatus", "captured", "httpRequestBody", body, "httpRequestBodyFormat", "json");
    }
    private static final class FakeClient extends DebugClient {
        final Map<String, ObjectNode> documents = new ConcurrentHashMap<>();
        final AtomicInteger calls = new AtomicInteger();
        volatile int size = 100, maxBytes, timeoutMillis; volatile String path; volatile long delay;
        FakeClient() { super("http://127.0.0.1:1"); }
        @Override public ObjectNode request(String method, String path, int maxBytes, int timeoutMillis) {
            calls.incrementAndGet(); this.path = path; this.maxBytes = maxBytes; this.timeoutMillis = timeoutMillis;
            assertThat(method).isEqualTo("GET"); assertThat(path).startsWith("/request-data/");
            if (delay > 0) try { Thread.sleep(delay); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            ObjectNode trace = documents.get(path.substring("/request-data/".length()));
            if (trace == null) throw new ToolException(503, "PRIVATE unavailable message");
            return trace;
        }
        @Override public int lastResponseBytes() { return size; }
    }
    private static RequestSearch search(FakeClient client, boolean federated) {
        return new RequestSearch(federated, Collections.singletonMap("cb-app", client), client);
    }

    @Test void searchesOnlyCapturedRequestFieldNamesAndValuesAndReturnsIdentityOnly() {
        FakeClient client = new FakeClient();
        ObjectNode attrs = captured("{\"lookup\":[\"Xero-integration\"]}", "{\"feature\":{\"name\":\"xero-integration\"}}");
        attrs.put("secretNotARequestField", "PRIVATE");
        ObjectNode trace = exported("s1", attrs);
        trace.set("events", Json.arr(Json.obj("sourceLine", "PRIVATE", "attributes", Json.obj("sql", "PRIVATE"))));
        client.documents.put("s1", trace);
        try (RequestSearch search = search(client, true)) {
            ObjectNode result = search.search(body("XERO-INTEGRATION", identity("s1")));
            assertThat(result.path("matches")).isEqualTo(Json.arr(identity("s1").set("fields", Json.arr("query", "body"))));
            assertThat(result.path("counts")).isEqualTo(Json.obj("requested", 1, "scanned", 1, "withData", 1,
                    "matched", 1, "unavailable", 0, "unsearched", 0));
            assertThat(Json.stringify(result)).doesNotContain("XERO", "Xero", "PRIVATE", "httpRequestBody", "lookup");
            assertThat(search.search(body("PRIVATE", identity("s1"))).path("matches")).isEmpty();
            assertThat(search.search(body("LOOKUP", identity("s1"))).path("matches").get(0).path("fields")).isEqualTo(Json.arr("query"));
            assertThat(client.maxBytes).isEqualTo(RequestSearch.MAX_FETCH_BYTES);
            assertThat(client.timeoutMillis).isBetween(1, 2000);
        }
    }

    @Test void decodesJsonNamesAndValuesWithoutRoundingNumericTokensOrTreatingKeywordsAsRegex() {
        ObjectNode trace = exported("s1", captured("{\"na\\u006de\":[\"caf\\u00e9\",\"literal.*value\"]}",
                "{\"id\":9007199254740993123456789,\"amount\":1.23000000000000000001,\"exp\":1e+40,\"active\":true}"));
        for (String term : new String[]{"café", "name", "literal.*", "9007199254740993123456789", "1.23000000000000000001", "1e+40", "true"})
            assertThat(RequestSearch.matchingFields(trace, term).path("fields")).isNotEmpty();
        assertThat(RequestSearch.matchingFields(trace, "literal.+").path("fields")).isEmpty();
        assertThat(RequestSearch.matchingFields(trace, "9007199254740993000000000").path("fields")).isEmpty();
    }

    @Test void encodedLegacyQueryAndFormValuesAreDecodedWherePossible() {
        ObjectNode attributes = captured("feature=xero%20integration&other=plus%2Bvalue", "name=plan%20one");
        attributes.put("httpRequestBodyFormat", "form");
        ObjectNode trace = exported("s1", attributes);
        assertThat(RequestSearch.matchingFields(trace, "xero integration").path("fields")).isEqualTo(Json.arr("query"));
        assertThat(RequestSearch.matchingFields(trace, "plus+value").path("fields")).isEqualTo(Json.arr("query"));
        assertThat(RequestSearch.matchingFields(trace, "plan one").path("fields")).isEqualTo(Json.arr("body"));
        attributes.put("httpQueryParameters", "literal%broken");
        assertThat(RequestSearch.matchingFields(trace, "literal%broken").path("fields")).isEqualTo(Json.arr("query"));
    }

    @Test void payloadFieldLimitsBoundParsingAndAreReportedInsteadOfSilentlyTruncating() {
        FakeClient client = new FakeClient();
        client.documents.put("s1", exported("s1", captured(null,
                "{\"value\":\"" + String.join("", Collections.nCopies(16384, "x")) + "needle\"}")));
        try (RequestSearch search = search(client, true)) {
            ObjectNode result = search.search(body("needle", identity("s1")));
            assertThat(result.path("limited").asBoolean()).isTrue();
            assertThat(result.path("counts").path("scanned").asInt()).isEqualTo(1);
            assertThat(result.path("counts").path("withData").asInt()).isZero();
            assertThat(result.path("matches")).isEmpty();
        }
    }

    @Test void missingOmittedAndUnconsumedDataRemainScannedButAreNotSearchable() {
        FakeClient client = new FakeClient();
        client.documents.put("s1", exported("s1", Json.obj()));
        client.documents.put("s2", exported("s2", Json.obj("httpQueryParameters", "PRIVATE", "httpQueryParametersStatus", "omitted-too-large",
                "httpRequestBody", "PRIVATE", "httpRequestBodyStatus", "incomplete")));
        client.documents.put("s3", exported("s3", captured(null, "{\"some\":\"other\"}")));
        try (RequestSearch search = search(client, true)) {
            ObjectNode result = search.search(body("PRIVATE", identity("s1"), identity("s2"), identity("s3")));
            assertThat(result.path("counts")).isEqualTo(Json.obj("requested", 3, "scanned", 3, "withData", 1,
                    "matched", 0, "unavailable", 0, "unsearched", 0));
            assertThat(result.path("limited").asBoolean()).isFalse();
        }
    }

    @Test void rejectsEntireMalformedBatchAndUnknownServicesBeforeAnyNetworkRead() {
        FakeClient client = new FakeClient();
        ArrayNode oversized = Json.arr(); for (int i = 0; i <= 200; i++) oversized.add(identity("s" + i));
        try (RequestSearch search = search(client, true)) {
            for (JsonNode invalid : new JsonNode[]{Json.arr(), Json.obj(), body("", identity("s1")), body("  ", identity("s1")),
                    body(String.join("", Collections.nCopies(257, "x")), identity("s1")),
                    body("private", identity("s1")).put("url", "https://private.invalid"),
                    body("private", identity("s1"), identity("s2").put("service", "unknown")),
                    body("private", identity("s1").put("segmentId", "../export/other")),
                    body("private", identity("s1").put("serviceInstanceId", "")),
                    body("private", identity("s1").putNull("traceId")),
                    body("private", identity("s1").put("upstream", "https://private.invalid")),
                    body("private", identity("s1"), identity("s1")),
                    Json.obj("keyword", "private", "segments", oversized), body("private", Json.obj("service", "cb-app")),
                    Json.obj("keyword", 123, "segments", Json.arr()), Json.obj("keyword", "private", "segments", null)}) {
                assertThatThrownBy(() -> search.search(invalid)).isInstanceOf(IllegalArgumentException.class);
            }
            assertThat(client.calls).hasValue(0);
        }
    }

    @Test void rejectsChangedSegmentInstanceAndTraceAndReportsUnavailableWithoutPrivateErrors() {
        FakeClient client = new FakeClient();
        client.documents.put("s1", exported("other", captured("{\"needle\":[]}", null)));
        client.documents.put("s2", exported("s2", captured("{\"needle\":[]}", null)).put("serviceInstanceId", "restarted"));
        client.documents.put("s3", exported("s3", captured("{\"needle\":[]}", null)).put("traceId", "different"));
        try (RequestSearch search = search(client, true)) {
            ObjectNode result = search.search(body("needle", identity("s1"), identity("s2"), identity("s3"), identity("missing")));
            assertThat(result.path("counts")).isEqualTo(Json.obj("requested", 4, "scanned", 0, "withData", 0,
                    "matched", 0, "unavailable", 4, "unsearched", 0));
            assertThat(Json.stringify(result)).doesNotContain("PRIVATE", "needle", "restarted", "different");
        }
    }

    @Test void singleModeAcceptsLocalAliasOrExactRecordedApplicationButNotAnotherService() {
        FakeClient client = new FakeClient(); client.documents.put("s1", exported("s1", captured("{\"needle\":[]}", null)));
        try (RequestSearch search = new RequestSearch(false, Collections.singletonMap("default", client), client)) {
            for (String name : new String[]{"local", "default", "cb-app"}) {
                ObjectNode row = identity("s1").put("service", name); row.remove("traceId");
                ObjectNode result = search.search(body("needle", row));
                assertThat(result.path("matches")).isEqualTo(Json.arr(row.deepCopy().set("fields", Json.arr("query"))));
            }
            assertThat(search.search(body("needle", identity("s1").put("service", "cb-ems"))).path("counts").path("unavailable").asInt()).isEqualTo(1);
        }
    }

    @Test void byteBudgetReturnsPartialResultsAndConstrainsTheLastFetch() {
        FakeClient client = new FakeClient(); client.size = 150;
        for (int i = 0; i < 4; i++) client.documents.put("s" + i, exported("s" + i, captured("{\"needle\":[]}", null)));
        try (RequestSearch search = new RequestSearch(true, Collections.singletonMap("cb-app", client), client, 200, 250, 2000, 12000)) {
            ObjectNode result = search.search(body("needle", identity("s0"), identity("s1"), identity("s2"), identity("s3")));
            assertThat(result.path("limited").asBoolean()).isTrue();
            assertThat(result.path("counts").path("scanned").asInt()).isEqualTo(2);
            assertThat(result.path("counts").path("unsearched").asInt()).isEqualTo(2);
            assertThat(client.calls).hasValue(2); assertThat(client.maxBytes).isEqualTo(100);
        }
    }

    @Test void deadlineBoundsSlowFetchesAndReturnsPartialCounts() {
        FakeClient client = new FakeClient(); client.delay = 3000;
        client.documents.put("s1", exported("s1", captured("{\"needle\":[]}", null)));
        long started = System.nanoTime();
        try (RequestSearch search = new RequestSearch(true, Collections.singletonMap("cb-app", client), client, 200, 400, 500, 50)) {
            ObjectNode result = search.search(body("needle", identity("s1"), identity("s2")));
            assertThat(result.path("counts").path("unavailable").asInt()).isEqualTo(1);
            assertThat(result.path("counts").path("unsearched").asInt()).isEqualTo(1);
            assertThat(result.path("limited").asBoolean()).isTrue();
            assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(1000);
            assertThat(client.timeoutMillis).isBetween(1, 50);
        }
    }
}
