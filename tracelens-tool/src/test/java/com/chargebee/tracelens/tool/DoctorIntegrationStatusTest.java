package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class DoctorIntegrationStatusTest {
    @TempDir Path temp;
    private static ObjectNode integration(boolean configured, boolean installed, long transformed, long failures, String state) {
        return Json.obj("supported", true, "configured", configured, "transformerInstalled", installed,
                "transformedClasses", transformed, "failures", failures, "state", state,
                "note", "PRIVATE_UPSTREAM_NOTE");
    }
    private ObjectNode run(ParityDoctorTest.Fake client) { return new ParityDoctorTest().run(client, temp.toString()); }
    private JsonNode check(JsonNode report, String id) { return new ParityDoctorTest().check(report, id); }

    @Test void absentOptionalFieldsPreserveLegacyReportExactly() {
        ParityDoctorTest.Fake client = new ParityDoctorTest.Fake();
        ObjectNode legacy = run(client);
        List<String> ids = new ArrayList<>();
        for (JsonNode item : legacy.path("services").get(0).path("checks")) ids.add(item.path("id").asText());
        assertThat(ids).containsExactly("debug_api", "metadata", "agent", "recording", "sink", "instrumentation",
                "scope", "captures", "request_retention", "event_retention", "retention_ttl", "source");
        client.metadata.set("runtimeDiagnostics", Json.obj("observerFailures", 99, "executorCoverage", "PRIVATE_COVERAGE"));
        assertThat(run(client)).isEqualTo(legacy);
    }

    @Test void optionalIntegrationStatesDistinguishTransformationFromExecution() {
        ParityDoctorTest.Fake client = new ParityDoctorTest.Fake();
        client.metadata.set("integrationStatus", Json.obj(
                "application-code", integration(true, true, 3, 0, "transformation-observed"),
                "jdbc", integration(true, true, 0, 0, "awaiting-class-load"),
                "clickhouse", integration(true, true, 1, 2, "degraded"),
                "data-store", integration(false, false, 0, 0, "disabled"),
                "PRIVATE_UNKNOWN_INTEGRATION", Json.obj("state", "PRIVATE_STATE")));
        JsonNode report = run(client);
        assertThat(report.path("ready").asBoolean()).isTrue();
        assertThat(report.path("status").asText()).isEqualTo("attention");
        assertThat(report.path("partial").asBoolean()).isFalse();
        assertThat(check(report, "integration_application_code").path("status").asText()).isEqualTo("pass");
        assertThat(check(report, "integration_application_code").path("message").asText()).contains("not hook execution or request coverage");
        assertThat(check(report, "integration_jdbc").path("status").asText()).isEqualTo("warn");
        assertThat(check(report, "integration_jdbc").path("message").asText()).contains("may not have loaded").doesNotContain("failed");
        assertThat(check(report, "integration_clickhouse").path("status").asText()).isEqualTo("warn");
        assertThat(check(report, "integration_data_store").path("status").asText()).isEqualTo("pass");
        assertThat(check(report, "integration_clickhouse").path("details").path("failures").asLong()).isEqualTo(2);
        assertThat(report.toString()).doesNotContain("PRIVATE");
        assertThat(report.path("services").get(0).path("checks")).hasSize(16);
    }

    @Test void summaryFallbackIsUsedOnlyWhenMetadataDoesNotReportTheField() {
        ParityDoctorTest.Fake client = new ParityDoctorTest.Fake();
        client.summary.set("integrationStatus", Json.obj("jdbc", integration(true, false, 0, 0, "not-installed")));
        assertThat(check(run(client), "integration_jdbc").path("status").asText()).isEqualTo("warn");
        client.metadata.set("integrationStatus", Json.obj("jdbc", integration(false, false, 0, 0, "disabled")));
        assertThat(check(run(client), "integration_jdbc").path("status").asText()).isEqualTo("pass");
        client.metadata.putNull("integrationStatus");
        assertThat(check(run(client), "integration_status").path("status").asText()).isEqualTo("unknown");
    }

    @ParameterizedTest @ValueSource(strings = {"null", "[]", "true", "\"PRIVATE_STATE\"", "{}", "{\"PRIVATE_KEY\":1}"})
    void malformedOrUnrecognizedCatalogRemainsUnknown(String invalid) {
        ParityDoctorTest.Fake client = new ParityDoctorTest.Fake();
        client.metadata.set("integrationStatus", Json.parse(invalid));
        JsonNode report = run(client);
        assertThat(check(report, "integration_status").path("status").asText()).isEqualTo("unknown");
        assertThat(report.path("partial").asBoolean()).isTrue();
        assertThat(report.toString()).doesNotContain("PRIVATE");
    }

    @Test void malformedAndContradictoryValuesAreNeverCoercedIntoAHealthyIntegration() {
        for (ObjectNode invalid : Arrays.asList(
                integration(true, true, 3, 0, "transformation-observed").put("configured", "true"),
                integration(true, true, 3, 0, "transformation-observed").put("transformedClasses", "3"),
                integration(true, true, 3, -1, "transformation-observed"),
                integration(true, false, 3, 0, "transformation-observed"),
                integration(true, true, 3, 0, "disabled"),
                integration(true, true, 3, 0, "transformation-observed").put("supported", false),
                integration(true, true, 3, 0, "PRIVATE_STATE"),
                integration(true, false, 0, 0, "unreported").putNull("configured"))) {
            ParityDoctorTest.Fake client = new ParityDoctorTest.Fake();
            client.metadata.set("integrationStatus", Json.obj("jdbc", invalid));
            JsonNode report = run(client);
            assertThat(check(report, "integration_jdbc").path("status").asText()).isEqualTo("unknown");
            assertThat(report.path("partial").asBoolean()).isTrue();
            assertThat(report.toString()).doesNotContain("PRIVATE");
        }
    }

    @Test void thirtyTwoServicesRetainEveryIntegrationStatusWithinTheExistingReportBudget() {
        List<Service> services = new ArrayList<>();
        for (int i = 0; i < 32; i++) services.add(new Service("service-" + i + String.join("", Collections.nCopies(50, "x")),
                "http://" + String.join("", Collections.nCopies(200, "h")) + ":" + (10000 + i), null));
        Federation federation = new Federation(services);
        for (Service service : services) {
            ParityDoctorTest.Fake client = new ParityDoctorTest.Fake();
            client.summary.put("traceCount", 0).put("totalEvents", 0);
            client.metadata.put("requireLabels", true);
            ObjectNode integrations = Json.obj();
            for (String id : Arrays.asList("application-code", "jdbc", "clickhouse", "data-store"))
                integrations.set(id, integration(true, true, Long.MAX_VALUE, Long.MAX_VALUE, "degraded"));
            client.metadata.set("integrationStatus", integrations); federation.clients.put(service.name, client);
        }
        JsonNode report = Doctor.runDoctor(federation);
        assertThat(report.path("services")).hasSize(32);
        assertThat(Json.canonical(report).getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(96 * 1024);
        Federation single = new Federation(Collections.singletonList(services.get(0)));
        single.clients.put(services.get(0).name, federation.clients.get(services.get(0).name));
        JsonNode complete = Doctor.runDoctor(single).path("services").get(0).path("checks");
        int detailsOmitted = 0, messagesOmitted = 0;
        for (JsonNode service : report.path("services")) {
            assertThat(service.path("checks")).hasSize(complete.size());
            for (int i = 0; i < complete.size(); i++) {
                JsonNode item = service.path("checks").get(i), original = complete.get(i);
                assertThat(item.path("id")).isEqualTo(original.path("id"));
                assertThat(item.path("status")).isEqualTo(original.path("status"));
                if (original.has("details") && !item.has("details")) detailsOmitted++;
                if (original.has("message") && !item.has("message")) messagesOmitted++;
            }
            int integrations = 0;
            for (JsonNode item : service.path("checks")) if (item.path("id").asText().startsWith("integration_")) {
                integrations++; assertThat(item.path("status").asText()).isEqualTo("warn");
            }
            assertThat(integrations).isEqualTo(4);
        }
        assertThat(detailsOmitted).isPositive();
        assertThat(report.path("omissions").path("checkDetails").asInt()).isEqualTo(detailsOmitted);
        assertThat(report.path("omissions").path("checkMessages").asInt()).isEqualTo(messagesOmitted);
    }

    @Test void compactionRemovesProseWhenNecessaryButPreservesProbeFactsAndEveryCheckStatus() {
        ObjectNode report = Json.obj("services", Json.arr(Json.obj("service", "app", "facts", Json.obj("traceCount", 0),
                "checks", Json.arr(
                        Json.obj("id", "debug_api", "status", "fail", "message", "界".repeat(40000), "details", Json.obj("kind", "http", "httpStatus", 503), "fixId", "step-1"),
                        Json.obj("id", "integration_jdbc", "status", "warn", "message", "Review integration.", "details", Json.obj("failures", 3))))),
                "omissions", Json.obj("checkDetails", 0, "checkMessages", 0), "nextSteps", Json.arr(Json.obj("id", "step-1", "instruction", "Check the configured endpoint.")));
        assertThat(Json.stringify(report).length()).isLessThan(Doctor.MAX_REPORT_BYTES);
        assertThat(Json.stringify(report).getBytes(StandardCharsets.UTF_8).length).isGreaterThan(Doctor.MAX_REPORT_BYTES);
        Doctor.compactReport(report);
        assertThat(Json.canonical(report).getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(Doctor.MAX_REPORT_BYTES);
        JsonNode checks = report.path("services").get(0).path("checks");
        assertThat(checks).hasSize(2);
        assertThat(checks.get(0)).isEqualTo(Json.obj("id", "debug_api", "status", "fail", "details", Json.obj("kind", "http", "httpStatus", 503), "fixId", "step-1"));
        assertThat(checks.get(1)).isEqualTo(Json.obj("id", "integration_jdbc", "status", "warn", "message", "Review integration."));
        assertThat(report.path("omissions")).isEqualTo(Json.obj("checkDetails", 1, "checkMessages", 1));
        assertThat(report.path("services").get(0).path("facts")).isEqualTo(Json.obj("traceCount", 0));
        assertThat(report.path("nextSteps")).hasSize(1);
    }

    @Test void underBudgetCompactionLeavesTheLegacyReportUnchanged() {
        ObjectNode report = run(new ParityDoctorTest.Fake()), before = report.deepCopy();
        assertThat(Doctor.compactReport(report)).isEqualTo(before);
        assertThat(report.path("omissions")).isEqualTo(Json.obj("checkDetails", 0, "checkMessages", 0));
    }

    @Test void wholeOperationDeadlineRetainsHealthyPeersAndBoundsSlowDiagnosticReads() throws Exception {
        Service slow = new Service("slow", "http://localhost:7778", null), healthy = new Service("healthy", "http://localhost:7779", null);
        Federation federation = new Federation(Arrays.asList(slow, healthy));
        CountDownLatch healthyRead = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        federation.clients.put(slow.name, new ParityDoctorTest.Fake() {
            @Override public ObjectNode request(String method, String path, int bytes, int timeout) {
                try { healthyRead.await(2, TimeUnit.SECONDS); new CountDownLatch(1).await(); }
                catch (InterruptedException expected) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                return super.request(method, path, bytes, timeout);
            }
        });
        federation.clients.put(healthy.name, new ParityDoctorTest.Fake() {
            @Override public ObjectNode request(String method, String path, int bytes, int timeout) {
                assertThat(BoundedReads.allowance()).isNotNull();
                ObjectNode result = super.request(method, path, bytes, timeout); healthyRead.countDown(); return result;
            }
        });
        long start = System.nanoTime();
        JsonNode report = Doctor.runDoctor(federation, 500);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(2500);
        assertThat(report.path("services")).hasSize(2);
        JsonNode slowReport = report.path("services").get(0), healthyReport = report.path("services").get(1);
        assertThat(slowReport.path("status").asText()).isEqualTo("unavailable");
        assertThat(slowReport.path("checks").get(0).path("details").path("kind").asText()).isEqualTo("budget");
        assertThat(healthyReport.path("ready").asBoolean()).isTrue();
        assertThat(report.path("partial").asBoolean()).isTrue();
        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
    }
}
