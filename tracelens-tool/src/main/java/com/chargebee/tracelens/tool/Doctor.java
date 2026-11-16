package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;

/** Read-only readiness diagnostics, never proof of execution coverage. */
public final class Doctor {
    public static final int MAX_RESPONSE_BYTES = 65536;
    public static final int MAX_REPORT_BYTES = 96 * 1024;
    private static final String PORT_GUIDANCE = "Use the TraceLens debug base URL, not the application UI URL. "
            + "Standalone examples use cb-app port 7778 and cb-ems port 7779. Confirm your configured debug port.";
    private static final String SCOPE_GUIDANCE = "Reproduce one API inside the configured package scope. "
            + "Check exclusions, HTTP path filters and requireLabels. Compile with line-number debug information "
            + "and use the matching source checkout. Restart the service after agent configuration or bytecode changes.";
    private static final String[] INTEGRATIONS = {"application-code", "jdbc", "clickhouse", "data-store"};
    private static final String INTEGRATION_GUIDANCE = "Review the integration's capture setting and startup diagnostics. "
            + "Matching classes may not be loaded yet; reproduce an intended call to verify executed hooks.";
    private Doctor() {}

    public static ObjectNode probe(DebugClient client, String endpoint) {
        return probe(client, endpoint, null);
    }
    private static ObjectNode probe(DebugClient client, String endpoint, BoundedReads.Budget budget) {
        try {
            ObjectNode doc = budget == null ? client.request("GET", "/" + endpoint, MAX_RESPONSE_BYTES, 5000)
                    : budget.call(() -> client.request("GET", "/" + endpoint, MAX_RESPONSE_BYTES, 5000), MAX_RESPONSE_BYTES);
            String[] fields = "summary".equals(endpoint)
                    ? new String[]{"agentActive", "recordingEnabled", "traceCount", "totalEvents", "bridge", "integrationStatus"}
                    : new String[]{"agentActive", "packages", "idleTtlMinutes", "trackMethods", "applicationId", "integrationStatus"};
            boolean known = false;
            for (String field : fields) known |= doc.has(field);
            return Json.obj("document", known ? doc : Json.obj(), "error",
                    known ? null : Json.obj("kind", "malformed", "httpStatus", null));
        } catch (BoundedReads.Limit exc) {
            return limitedProbe();
        } catch (DebugClient.Failure exc) {
            return Json.obj("document", Json.obj(), "error", Json.obj("kind", exc.kind,
                    "httpStatus", "http".equals(exc.kind) ? exc.status : null));
        } catch (ToolException exc) {
            return Json.obj("document", Json.obj(), "error", Json.obj(
                    "kind", exc.status == 502 ? "unreachable" : "http",
                    "httpStatus", exc.status == 502 ? null : exc.status));
        } catch (Exception exc) {
            return Json.obj("document", Json.obj(), "error", Json.obj("kind", "malformed", "httpStatus", null));
        }
    }
    private static ObjectNode limitedProbe() {
        return Json.obj("document", Json.obj(), "error", Json.obj("kind", "budget", "httpStatus", null));
    }
    private static JsonNode integer(JsonNode value, boolean positive) {
        return value != null && value.isIntegralNumber() && value.canConvertToLong()
                && value.longValue() >= (positive ? 1 : 0) ? value : Json.node(null);
    }
    private static JsonNode bool(JsonNode value) {
        return value != null && value.isBoolean() ? value : Json.node(null);
    }
    private static void check(ArrayNode checks, String id, String status, String message, String fix, ObjectNode details) {
        ObjectNode check = Json.obj("id", id, "status", status, "message", message);
        if (fix != null) check.put("fix", fix);
        if (details != null) check.set("details", details);
        checks.add(check);
    }
    private static void probeCheck(ArrayNode checks, String id, JsonNode error) {
        String endpoint = "debug_api".equals(id) ? "summary" : "metadata";
        if (error == null || error.isNull()) {
            check(checks, id, "pass", "Read /tracelens/" + endpoint + ".", null, null);
            return;
        }
        if ("budget".equals(Json.text(error, "kind"))) {
            check(checks, id, "debug_api".equals(id) ? "fail" : "unknown",
                    "Diagnostic read limit reached; this does not establish service failure.",
                    "Retry when shared read capacity is available, or inspect fewer services.", Json.copy(error));
            return;
        }
        int status = error.path("httpStatus").asInt();
        String fix = status == 401 || status == 403
                ? "Check endpoint access policy with its owner; do not disable authentication to make diagnostics pass."
                : status == 404 && "metadata".equals(endpoint)
                ? "This may be an older adapter. Summary checks still work; use compatible metadata."
                : PORT_GUIDANCE + " Start the intended service with its agent.";
        check(checks, id, "debug_api".equals(id) ? "fail" : "unknown",
                "Could not inspect /tracelens/" + endpoint + "; response bodies and exceptions are not exposed.", fix, Json.copy(error));
    }
    private static void integrationChecks(ArrayNode checks, JsonNode summary, JsonNode metadata) {
        if (!summary.has("integrationStatus") && !metadata.has("integrationStatus")) return;
        JsonNode source = metadata.has("integrationStatus") ? metadata.get("integrationStatus") : summary.get("integrationStatus");
        if (!source.isObject()) {
            check(checks, "integration_status", "unknown", "Integration status is malformed or unavailable.", INTEGRATION_GUIDANCE, null); return;
        }
        boolean known = false;
        for (String id : INTEGRATIONS) {
            if (!source.has(id)) continue;
            known = true; JsonNode row = source.get(id);
            ObjectNode details = Json.obj("supported", bool(row.get("supported")), "configured", bool(row.get("configured")),
                    "transformerInstalled", bool(row.get("transformerInstalled")),
                    "transformedClasses", integer(row.get("transformedClasses"), false), "failures", integer(row.get("failures"), false));
            String state = Json.text(row, "state");
            if (!Arrays.asList("unreported", "disabled", "degraded", "transformation-observed", "awaiting-class-load", "not-installed").contains(state)) state = null;
            details.set("state", Json.node(state));
            boolean valid = row.isObject(); for (JsonNode value : details) valid &= !value.isNull();
            String expected = !details.path("configured").asBoolean() ? "disabled" : details.path("failures").asLong() > 0 ? "degraded"
                    : details.path("transformedClasses").asLong() > 0 ? "transformation-observed"
                    : details.path("transformerInstalled").asBoolean() ? "awaiting-class-load" : "not-installed";
            valid &= expected.equals(state) && ("disabled".equals(state) || details.path("supported").asBoolean())
                    && (!"transformation-observed".equals(state) || details.path("transformerInstalled").asBoolean());
            String status = !valid ? "unknown" : "disabled".equals(state) || "transformation-observed".equals(state) ? "pass" : "warn";
            String message = !valid ? "Integration status is unavailable, malformed or inconsistent."
                    : "disabled".equals(state) ? "Integration is disabled; no hook execution is established."
                    : "transformation-observed".equals(state) ? "Class transformation was observed, not hook execution or request coverage."
                    : "awaiting-class-load".equals(state) ? "Transformer installed; matching classes may not have loaded yet."
                    : "degraded".equals(state) ? "Agent reports integration setup or transformation failures; coverage may be incomplete."
                    : "Configured integration has no installed transformer reported.";
            check(checks, "integration_" + id.replace('-', '_'), status, message, "pass".equals(status) ? null : INTEGRATION_GUIDANCE, details);
        }
        if (!known) check(checks, "integration_status", "unknown", "No recognized integration status was reported.", INTEGRATION_GUIDANCE, null);
    }
    private static ObjectNode report(Service service, DebugClient client, BoundedReads.Budget budget) {
        return report(service, probe(client, "summary", budget), probe(client, "metadata", budget));
    }
    private static ObjectNode report(Service service, ObjectNode summaryProbe, ObjectNode metadataProbe) {
        JsonNode summary = summaryProbe.get("document"), metadata = metadataProbe.get("document"), bridge = summary.path("bridge");
        ArrayNode checks = Json.arr();
        probeCheck(checks, "debug_api", summaryProbe.get("error"));
        probeCheck(checks, "metadata", metadataProbe.get("error"));
        JsonNode agent = bool(summary.get("agentActive")), metadataAgent = bool(metadata.get("agentActive"));
        if (!summary.has("agentActive")) agent = metadataAgent;
        else if (!metadataAgent.isNull() && !metadataAgent.equals(agent)) agent = Json.node(null);
        ObjectNode facts = Json.obj("agentActive", agent, "recordingEnabled", bool(summary.get("recordingEnabled")),
                "sinkRegistered", bool(bridge.get("sinkRegistered")),
                "instrumentedClasses", integer(summary.get("instrumentedClasses"), false),
                "failedClasses", integer(summary.get("failedClasses"), false),
                "traceCount", integer(summary.get("traceCount"), false), "totalEvents", integer(summary.get("totalEvents"), false),
                "maxTrackedRequests", integer(summary.get("maxTrackedRequests"), true),
                "maxTrackedEvents", integer(summary.get("maxTrackedEvents"), true),
                "idleTtlMinutes", integer(metadata.get("idleTtlMinutes"), true),
                "lineCaptureEnabled", bool(metadata.has("lineCaptureEnabled") ? metadata.get("lineCaptureEnabled") : summary.get("lineCaptureEnabled")),
                "requireLabels", bool(metadata.get("requireLabels")),
                "emitted", integer(bridge.get("emitted"), false), "droppedNoSink", integer(bridge.get("droppedNoSink"), false),
                "droppedNoTrace", integer(bridge.get("droppedNoTrace"), false));
        for (String[] row : new String[][]{
                {"agent", "agentActive", "Agent premain reports active; this does not prove desired API coverage.",
                        "Attach the built TraceLens agent with -javaagent and restart the service JVM."},
                {"recording", "recordingEnabled", "Recording is enabled for eligible new segments.",
                        "Review the intended recording setting; diagnostics never change it."},
                {"sink", "sinkRegistered", "The in-JVM event sink is registered.",
                        "Use one store owner per JVM and matching TraceLens artifacts; check startup diagnostics."}}) {
            JsonNode value = facts.get(row[1]);
            check(checks, row[0], value.isNull() ? "unknown" : value.booleanValue() ? "pass" : "fail",
                    value.asBoolean() ? row[2] : row[1] + " is false, unavailable, malformed or inconsistent.",
                    value.asBoolean() ? null : row[3], Json.obj("value", value));
        }
        JsonNode transformed = facts.get("instrumentedClasses"), failed = facts.get("failedClasses");
        String instrumentation = transformed.isNull() || failed.isNull() ? "unknown"
                : transformed.longValue() == 0 || failed.longValue() > 0 ? "warn" : "pass";
        check(checks, "instrumentation", instrumentation,
                "Main-transformer counters do not cover every specialized hook or prove line-number coverage.",
                "pass".equals(instrumentation) ? null : SCOPE_GUIDANCE,
                Json.obj("instrumentedClasses", transformed, "failedClasses", failed));
        integrationChecks(checks, summary, metadata);
        JsonNode packages = metadata.path("packages");
        boolean validPackages = packages.isArray() && packages.size() <= 64;
        if (validPackages) for (JsonNode value : packages)
            validPackages &= value.isTextual() && value.textValue().length() <= 256
                    && value.textValue().matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*\\.?");
        if (!metadata.has("lineCaptureEnabled") && !summary.has("lineCaptureEnabled") && validPackages)
            facts.put("lineCaptureEnabled", packages.size() > 0);
        JsonNode line = facts.get("lineCaptureEnabled");
        check(checks, "scope", line.isNull() ? "unknown" : line.asBoolean() ? "pass" : "warn",
                line.asBoolean() ? "Line capture is configured; matching requests and compiled line tables are still required."
                        : "Line scope is unavailable or empty; no coverage is assumed.",
                line.asBoolean() ? null : SCOPE_GUIDANCE,
                Json.obj("lineCaptureEnabled", line, "packageCount", validPackages ? packages.size() : null));
        JsonNode count = facts.get("traceCount"), events = facts.get("totalEvents");
        String captureStatus = count.isNull() || events.isNull() ? "unknown"
                : count.longValue() == 0 || events.longValue() == 0 ? "warn" : "pass";
        check(checks, "captures", captureStatus, "Retained evidence is not proof that the desired API or lines were captured.",
                "pass".equals(captureStatus) ? null : SCOPE_GUIDANCE, Json.obj("traceCount", count, "totalEvents", events));
        for (String[] row : new String[][]{{"request_retention", "traceCount", "maxTrackedRequests"},
                {"event_retention", "totalEvents", "maxTrackedEvents"}}) {
            JsonNode actual = facts.get(row[1]), capacity = facts.get(row[2]);
            String status = actual.isNull() || capacity.isNull() ? "unknown" : actual.longValue() >= capacity.longValue() ? "warn" : "pass";
            check(checks, row[0], status, "Retention is bounded in JVM memory, not a database.",
                    "warn".equals(status) ? "Save the target request promptly; narrow scope before raising memory limits."
                            : "unknown".equals(status) ? "Use compatible summary fields; missing values are not zero." : null,
                    Json.obj("count", actual, "capacity", capacity));
        }
        JsonNode ttl = facts.get("idleTtlMinutes");
        check(checks, "retention_ttl", ttl.isNull() ? "unknown" : "pass", "Reading evidence does not refresh its activity-based TTL.",
                ttl.isNull() ? "Check idleTtlMinutes in agent configuration." : null, Json.obj("minutes", ttl));
        if (facts.path("requireLabels").asBoolean())
            check(checks, "http_labels", "warn", "HTTP capture requires a reproduction label.",
                    "Supply the tracelens-id header; UI filters do not enable recording.", null);
        if (service.sourceRoot == null)
            check(checks, "source", "warn", "No sourceRoot configured; recording does not require a checkout.",
                    "Set sourceRoot to the matching local checkout for source navigation.", Json.obj("configured", false));
        else {
            boolean readable = Files.isDirectory(Paths.get(service.sourceRoot)) && Files.isReadable(Paths.get(service.sourceRoot));
            check(checks, "source", readable ? "pass" : "fail",
                    readable ? "Source directory is readable; build correspondence is not verified." : "Source directory is unavailable.",
                    readable ? null : "Point sourceRoot at an accessible matching checkout.", Json.obj("configured", true, "readable", readable));
        }
        boolean changed = Json.text(summary, "serviceInstanceId") != null && Json.text(metadata, "serviceInstanceId") != null
                && !Json.text(summary, "serviceInstanceId").equals(Json.text(metadata, "serviceInstanceId"));
        if (changed) check(checks, "instance", "unknown", "Service instance changed between reads.",
                "Wait for restart completion and retry.", null);
        Set<String> core = new HashSet<>(Arrays.asList("debug_api", "agent", "recording", "sink", "instrumentation", "scope"));
        boolean ready = !changed, partial = !summaryProbe.path("error").isNull() || !metadataProbe.path("error").isNull(), attention = false;
        for (JsonNode check : checks) {
            String status = Json.text(check, "status");
            if (core.contains(Json.text(check, "id"))) ready &= "pass".equals(status);
            partial |= "unknown".equals(status);
            attention |= !"pass".equals(status);
        }
        URI uri = URI.create(service.url);
        String endpoint = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        return Json.obj("service", service.name, "endpoint", endpoint, "ready", ready, "status",
                !summaryProbe.path("error").isNull() ? "unavailable" : attention ? "attention" : "ready",
                "partial", partial, "facts", facts, "checks", checks);
    }
    public static ObjectNode runDoctor(Federation discovery) {
        return runDoctor(discovery, BoundedReads.MAX_MILLIS);
    }
    static ObjectNode runDoctor(Federation discovery, int maxMillis) {
        if (discovery.services.isEmpty() || discovery.services.size() > 32) throw new IllegalArgumentException("Expected 1–32 services");
        if (maxMillis < 1 || maxMillis > BoundedReads.MAX_MILLIS) throw new IllegalArgumentException("Invalid diagnostic deadline");
        List<Service> selected = new ArrayList<>(discovery.services.values());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxMillis);
        int serviceMillis = Math.max(1, maxMillis / ((selected.size() + 7) / 8));
        List<Future<ObjectNode>> futures = new ArrayList<>();
        ArrayNode reports = Json.arr();
        try {
            for (Service service : selected) {
                try {
                    futures.add(BoundedReads.submit(() -> report(service, discovery.clients.get(service.name),
                            new BoundedReads.Budget(Math.min(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(serviceMillis)),
                                    2, 2 * MAX_RESPONSE_BYTES))));
                } catch (BoundedReads.Limit busy) {
                    futures.add(CompletableFuture.completedFuture(report(service, limitedProbe(), limitedProbe())));
                }
            }
            for (int i = 0; i < futures.size(); i++) {
                try { reports.add(BoundedReads.await(futures.get(i), deadline)); }
                catch (RuntimeException unavailable) { reports.add(report(selected.get(i), limitedProbe(), limitedProbe())); }
            }
        } finally { for (Future<ObjectNode> future : futures) if (!future.isDone()) future.cancel(true); }
        boolean ready = true, partial = false, offline = true, attention = false;
        ArrayNode nextSteps = Json.arr();
        Map<String, String> guidance = new LinkedHashMap<>();
        for (JsonNode service : reports) {
            ready &= service.path("ready").asBoolean();
            partial |= service.path("partial").asBoolean();
            offline &= "unavailable".equals(Json.text(service, "status"));
            for (JsonNode item : service.path("checks")) {
                ObjectNode check = (ObjectNode) item;
                attention |= !"pass".equals(Json.text(check, "status"));
                String fix = Json.text(check, "fix");
                if (fix != null) {
                    if (!guidance.containsKey(fix)) {
                        String id = "step-" + (guidance.size() + 1);
                        guidance.put(fix, id);
                        nextSteps.add(Json.obj("id", id, "instruction", fix));
                    }
                    check.remove("fix");
                    check.put("fixId", guidance.get(fix));
                }
            }
        }
        ObjectNode report = Json.obj("schemaVersion", 1, "readOnly", true, "ready", ready, "partial", partial,
                "status", offline ? "unavailable" : attention ? "attention" : "ready",
                "services", reports, "nextSteps", nextSteps, "omissions", Json.obj("checkDetails", 0, "checkMessages", 0),
                "limits", Json.obj("maxServices", 32, "maxResponseBytes", MAX_RESPONSE_BYTES, "maxReportBytes", MAX_REPORT_BYTES,
                        "maxWorkers", 8, "socketTimeoutSeconds", 5.0),
                "caveat", "Setup snapshot, not a performance benchmark, production-safety assessment, or proof of complete execution coverage.");
        return compactReport(report);
    }
    static ObjectNode compactReport(ObjectNode report) {
        ObjectNode omissions = (ObjectNode) report.get("omissions");
        for (String field : new String[]{"details", "message"}) {
            String counter = "details".equals(field) ? "checkDetails" : "checkMessages";
            for (JsonNode service : report.path("services")) for (JsonNode item : service.path("checks")) {
                if (reportBytes(report) <= MAX_REPORT_BYTES) return report;
                ObjectNode check = (ObjectNode) item;
                // Keep probe HTTP status/error kind when optional check details are elided.
                if ("details".equals(field) && Arrays.asList("debug_api", "metadata").contains(Json.text(check, "id"))) continue;
                if (check.has(field)) {
                    check.remove(field);
                    omissions.put(counter, omissions.path(counter).asInt() + 1);
                }
            }
        }
        if (reportBytes(report) > MAX_REPORT_BYTES)
            throw new ToolException(413, "Diagnostic report exceeds its bounded response limit.");
        return report;
    }
    private static int reportBytes(JsonNode report) {
        return Json.canonical(report).getBytes(StandardCharsets.UTF_8).length;
    }
}
