package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** Readiness/unknown/failure semantics from Python test_doctor.py. */
class ParityDoctorTest {
    @TempDir Path temp;
    static ObjectNode summary() {
        return Json.obj("agentActive", true, "recordingEnabled", true, "instrumentedClasses", 18,
                "failedClasses", 0, "traceCount", 2, "totalEvents", 10, "maxTrackedRequests", 200,
                "maxTrackedEvents", 500000, "serviceInstanceId", "instance-a", "bridge",
                Json.obj("sinkRegistered", true, "emitted", 12, "droppedNoTrace", 8, "droppedNoSink", 3));
    }
    static ObjectNode metadata() {
        return Json.obj("agentActive", true, "packages", Json.arr("com.example"), "idleTtlMinutes", 60,
                "lineCaptureEnabled", true, "requireLabels", false, "serviceInstanceId", "instance-a");
    }
    static class Fake extends DebugClient {
        ObjectNode summary = ParityDoctorTest.summary(), metadata = ParityDoctorTest.metadata();
        List<String> calls = new ArrayList<>();
        Integer summaryError, metadataError;
        Fake() { super("http://127.0.0.1:1"); }
        @Override public ObjectNode request(String method, String path, int bytes, int timeout) {
            assertThat(method).isEqualTo("GET");
            assertThat(bytes).isEqualTo(65536);
            assertThat(timeout).isEqualTo(5000);
            calls.add(path);
            Integer error = path.equals("/summary") ? summaryError : metadataError;
            if (error != null) throw new ToolException(error, "PRIVATE_UPSTREAM_MESSAGE");
            return (path.equals("/summary") ? summary : metadata).deepCopy();
        }
    }
    ObjectNode run(Fake client, String source) {
        Federation federation = new Federation(Collections.singletonList(new Service("app", "http://localhost:7778", source)));
        federation.clients.put("app", client);
        return Doctor.runDoctor(federation);
    }
    JsonNode check(JsonNode report, String id) {
        for (JsonNode check : report.path("services").get(0).path("checks"))
            if (id.equals(check.path("id").asText())) return check;
        throw new AssertionError("Missing check: " + id);
    }
    @Test void healthyIsReadOnlyButDoesNotClaimCoverage() {
        Fake client = new Fake();
        JsonNode report = run(client, temp.toString());
        assertThat(report.path("ready").asBoolean()).isTrue();
        assertThat(report.path("partial").asBoolean()).isFalse();
        assertThat(report.path("status").asText()).isEqualTo("ready");
        assertThat(report.path("readOnly").asBoolean()).isTrue();
        assertThat(report.path("nextSteps")).isEmpty();
        assertThat(report.path("caveat").asText()).contains("not a performance benchmark");
        assertThat(client.calls).containsExactly("/summary", "/metadata");
    }
    @ParameterizedTest @ValueSource(strings = {"true", "\"12\"", "-1", "1.1", "1180591620717411303424", "[]", "{}"})
    void malformedCountsRemainUnknown(String invalid) {
        Fake client = new Fake();
        client.summary.set("traceCount", Json.parse(invalid));
        client.summary.set("instrumentedClasses", Json.parse(invalid));
        JsonNode report = run(client, null);
        assertThat(report.path("services").get(0).path("facts").path("traceCount").isNull()).isTrue();
        assertThat(check(report, "captures").path("status").asText()).isEqualTo("unknown");
        assertThat(report.path("ready").asBoolean()).isFalse();
        assertThat(report.path("partial").asBoolean()).isTrue();
    }
    @ParameterizedTest @ValueSource(strings = {"\"true\"", "1", "[]", "{}", "\"false\""})
    void malformedBooleanDoesNotCoerceToTrue(String invalid) {
        Fake client = new Fake();
        client.summary.set("recordingEnabled", Json.parse(invalid));
        JsonNode report = run(client, null);
        assertThat(check(report, "recording").path("status").asText()).isEqualTo("unknown");
        assertThat(report.path("ready").asBoolean()).isFalse();
    }
    @Test void freshStoreAndOptionalSourceAreWarningsNotCaptureFailures() {
        Fake client = new Fake();
        client.summary.put("traceCount", 0).put("totalEvents", 0);
        JsonNode report = run(client, null);
        assertThat(report.path("ready").asBoolean()).isTrue();
        assertThat(report.path("status").asText()).isEqualTo("attention");
        assertThat(report.path("partial").asBoolean()).isFalse();
        assertThat(check(report, "captures").path("status").asText()).isEqualTo("warn");
        assertThat(check(report, "source").path("status").asText()).isEqualTo("warn");
    }
    @Test void disabledAgentRecordingAndSinkAreKnownFailures() {
        Fake client = new Fake();
        client.summary.put("agentActive", false).put("recordingEnabled", false);
        ((ObjectNode) client.summary.get("bridge")).put("sinkRegistered", false);
        client.metadata.put("agentActive", false);
        JsonNode report = run(client, temp.toString());
        assertThat(report.path("ready").asBoolean()).isFalse();
        assertThat(report.path("partial").asBoolean()).isFalse();
        for (String id : Arrays.asList("agent", "recording", "sink"))
            assertThat(check(report, id).path("status").asText()).isEqualTo("fail");
        assertThat(report.toString()).contains("-javaagent", "restart");
    }
    @Test void metadataFallbacksAndMalformedFlagsPreserveUnknowns() {
        Fake client = new Fake();
        client.metadata.remove("lineCaptureEnabled");
        assertThat(check(run(client, null), "scope").path("status").asText()).isEqualTo("pass");
        client.summary.put("lineCaptureEnabled", true);
        client.metadata.put("lineCaptureEnabled", "true");
        assertThat(check(run(client, null), "scope").path("status").asText()).isEqualTo("unknown");
        client.metadataError = 404;
        client.summary = Json.obj("traceCount", 3);
        JsonNode report = run(client, null);
        assertThat(report.path("partial").asBoolean()).isTrue();
        assertThat(report.path("services").get(0).path("facts").path("totalEvents").isNull()).isTrue();
        assertThat(check(report, "metadata").path("status").asText()).isEqualTo("unknown");
    }
    @Test void retentionAndTransformationProblemsHaveDistinctStatuses() {
        Fake client = new Fake();
        client.summary.put("failedClasses", 2).put("traceCount", 200).put("totalEvents", 500000);
        client.metadata.put("requireLabels", true);
        JsonNode report = run(client, null);
        assertThat(report.path("ready").asBoolean()).isFalse();
        for (String id : Arrays.asList("instrumentation", "request_retention", "event_retention", "http_labels"))
            assertThat(check(report, id).path("status").asText()).isEqualTo("warn");
        assertThat(report.toString()).contains("tracelens-id");
    }
    @Test void changedInstanceIsNotSilentlyCombinedAndPrivatePayloadsAreNotReflected() {
        Fake client = new Fake();
        client.summary.put("password", "PRIVATE_SECRET");
        client.metadata.put("serviceInstanceId", "PRIVATE_NEW_INSTANCE").put("agentActive", false);
        client.metadata.set("packages", Json.arr("PRIVATE\nVALUE"));
        JsonNode report = run(client, temp.toString());
        assertThat(report.toString()).doesNotContain("PRIVATE");
        assertThat(report.path("ready").asBoolean()).isFalse();
        assertThat(report.path("partial").asBoolean()).isTrue();
        assertThat(check(report, "instance").path("status").asText()).isEqualTo("unknown");
        assertThat(check(report, "agent").path("status").asText()).isEqualTo("unknown");
        assertThat(check(report, "scope").path("details").path("packageCount").isNull()).isTrue();
    }
    @Test void offlineServiceDoesNotHideHealthyServiceAndErrorsStaySanitized() {
        Federation federation = new Federation(Arrays.asList(new Service("app", "http://localhost:7778", temp.toString()),
                new Service("ems", "http://localhost:7779", temp.toString())));
        Fake healthy = new Fake(), offline = new Fake();
        offline.summaryError = 403;
        offline.metadataError = 502;
        federation.clients.put("app", healthy);
        federation.clients.put("ems", offline);
        JsonNode report = Doctor.runDoctor(federation);
        assertThat(report.path("status").asText()).isEqualTo("attention");
        assertThat(report.path("partial").asBoolean()).isTrue();
        assertThat(report.path("services").get(0).path("ready").asBoolean()).isTrue();
        assertThat(report.path("services").get(1).path("ready").asBoolean()).isFalse();
        assertThat(report.toString()).doesNotContain("PRIVATE").contains("do not disable authentication");
        assertThat(offline.calls).containsExactly("/summary", "/metadata");
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void thirtyTwoServiceReportsKeepEveryStatusWithinBudget(boolean offline) {
        List<Service> services = new ArrayList<>();
        for (int i = 0; i < 32; i++)
            services.add(new Service("service-" + i + String.join("", Collections.nCopies(50, "x")),
                    "http://" + String.join("", Collections.nCopies(200, "h")) + ":" + (10000 + i), null));
        Federation federation = new Federation(services);
        for (Service service : services) {
            Fake client = new Fake();
            if (offline) { client.summaryError = 503; client.metadataError = 503; }
            else {
                client.summary.put("traceCount", 0).put("totalEvents", 0);
                client.metadata.put("requireLabels", true);
            }
            federation.clients.put(service.name, client);
        }
        JsonNode report = Doctor.runDoctor(federation);
        assertThat(report.path("services")).hasSize(32);
        assertThat(Json.canonical(report).getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(96 * 1024);
        Set<String> fixes = new HashSet<>();
        for (JsonNode fix : report.path("nextSteps")) fixes.add(fix.path("id").asText());
        for (JsonNode service : report.path("services"))
            for (JsonNode check : service.path("checks"))
                if (check.has("fixId")) assertThat(fixes).contains(check.path("fixId").asText());
    }
}
