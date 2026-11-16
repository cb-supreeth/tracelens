package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import java.io.*;
import static org.assertj.core.api.Assertions.*;

class LargeTraceViewerTest {
    @TempDir Path temp;
    static final String TRACE = "0123456789abcdef0123456789abcdef", SEGMENT = "11111111-1111-1111-1111-111111111111";
    HttpServer agent; ViewerServer viewer; String origin; ToolConfig config;
    ArrayNode events; ObjectNode overview; AtomicInteger exports = new AtomicInteger(); boolean badCursor;
    @BeforeEach void setup() throws Exception {
        temp = temp.toRealPath();
        events = Json.arr(); overview = identity(); overview.setAll(Json.obj("captureMode", "detailed", "detailsAvailable", true,
                "events", Json.arr(), "httpCalls", Json.arr(), "status", "COMPLETED", "eventCount", 0, "through", 0));
        agent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        agent.createContext("/tracelens", exchange -> {
            String path = exchange.getRequestURI().getPath(); ObjectNode doc; int status = 200;
            if (path.contains("/overview/")) doc = overview;
            else if (path.contains("/find-by-trace-id/") || path.endsWith("/traces")) doc = Json.obj("traces", Json.arr(overview));
            else if (path.contains("/request-data/")) { doc = identity(); doc.set("attributes", Json.obj("httpRequestBody", "{\"search\":\"needle\"}", "httpRequestBodyStatus", "captured")); }
            else if (path.contains("/events/")) {
                Map<String, String> query = new HashMap<>(); for (String part : exchange.getRequestURI().getRawQuery().split("&")) { String[] pair = part.split("="); query.put(pair[0], pair[1]); }
                long after = Long.parseLong(query.get("after")), through = Long.parseLong(query.get("through"));
                int limit = Integer.parseInt(query.get("limit")), bytes = Integer.parseInt(query.get("maxBytes"));
                ArrayNode selected = Json.arr(); int used = 1024; long next = after;
                for (int i = (int) after; i < Math.min(through, events.size()); i++) {
                    JsonNode event = events.get(i); int size = Captures.bytes(event).length;
                    if (selected.size() >= limit || used + size > bytes) break;
                    used += size; selected.add(event); next = i + 1;
                }
                doc = identity(); doc.setAll(Json.obj("after", after, "through", through, "nextAfter", badCursor ? after : next,
                        "hasMore", badCursor || next < through, "events", selected, "partial", false, "warnings", Json.arr()));
            } else { exports.incrementAndGet(); doc = Json.obj("error", "Full export forbidden in large trace test"); status = 413; }
            byte[] bytes = Captures.bytes(doc); exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); agent.start();
        config = ToolConfig.fromArgs(new String[]{"--url", "http://127.0.0.1:" + agent.getAddress().getPort(), "--source-root",temp.toString(),"--snapshot-dir", temp.resolve("snapshots").toString(),
                "--capture-dir", temp.resolve("captures").toString(), "serve", "--port", "0"});
        viewer = new ViewerServer(config); viewer.start(); origin = "http://127.0.0.1:" + viewer.port();
    }
    @AfterEach void close() { if (viewer != null) viewer.close(); if (agent != null) agent.stop(0); }
    ObjectNode identity() { return Json.obj("traceId", TRACE, "segmentId", SEGMENT, "serviceInstanceId", "instance-1", "applicationId", "default"); }
    void populate(int count, int payloadSize) {
        char[] chars = new char[payloadSize]; Arrays.fill(chars, 'x'); String padding = new String(chars);
        for (int i = 0; i < count; i++) events.add(Json.obj("seq", i + 1, "eventIndex", i, "type", i == 0 ? "METHOD_ENTER" : i == count - 1 ? "METHOD_EXIT" : "LINE",
                "className", "sample.Service", "methodName", "run", "thread", "worker", "nanoTime", i + 1, "line", 12, "attributes", Json.obj("synthetic", padding)));
        overview.put("eventCount", count); overview.put("through", count);
    }
    HttpResponse<byte[]> request(String method, String path, ObjectNode body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(origin + path));
        if (body != null) builder.header("Content-Type", "application/json").header("Origin", origin).method(method, HttpRequest.BodyPublishers.ofByteArray(Captures.bytes(body)));
        else builder.method(method, HttpRequest.BodyPublishers.noBody());
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    JsonNode json(HttpResponse<byte[]> response, int status) { assertThat(response.statusCode()).as(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(status); return Json.parse(response.body()); }
    @Test void capturesMoreThanTwelveMiBWithoutExportAndReadsOfflineLastPageAndZip() throws Exception {
        populate(14000, 1024);
        JsonNode journey = json(request("GET", "/api/distributed-trace/" + TRACE, null), 200);
        assertThat(journey.path("segments").size()).isEqualTo(1); assertThat(exports.get()).isZero();
        HttpResponse<byte[]> saved = request("POST", "/api/snapshots/capture", Json.obj("traceId", TRACE, "name", "Large retained trace"));
        JsonNode manifest = json(saved, 201); String id = manifest.path("id").asText();
        assertThat(manifest.path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(manifest.path("rawBytes").asLong()).isGreaterThan(12 * 1024 * 1024);
        assertThat(manifest.path("segments").get(0).path("savedEventCount").asInt()).isEqualTo(14000);
        assertThat(saved.body().length).isLessThan(100000); assertThat(exports.get()).isZero();
        agent.stop(0); agent = null;
        String detail = "/api/snapshots/" + id + "/segments/" + SEGMENT;
        JsonNode last = json(request("GET", detail + "/events?service=default&instance=instance-1&after=13999&through=14000&limit=1", null), 200);
        assertThat(last.path("events").get(0).path("seq").asInt()).isEqualTo(14000); assertThat(last.path("hasMore").asBoolean()).isFalse();
        JsonNode execution = json(request("GET", detail + "/execution?service=default&instance=instance-1", null), 200);
        assertThat(execution.path("nodes").get(0).path("complete").asBoolean()).isTrue();
        assertThat(execution.path("counts").path("eventsProcessed").asInt()).isEqualTo(14000);
        assertThat(json(request("GET", "/api/snapshots", null), 200).path("snapshots").size()).isEqualTo(1);
        HttpResponse<byte[]> zip = request("GET", "/api/snapshots/" + id + "/download", null); assertThat(zip.statusCode()).isEqualTo(200);
        int entries = 0; try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip.body()))) {
            ZipEntry entry; while ((entry = input.getNextEntry()) != null) { assertThat(entry.getName()).doesNotContain("/", ".."); entries++; input.closeEntry(); }
        }
        assertThat(entries).isGreaterThan(50);
        ViewerSnapshotStore store = new ViewerSnapshotStore(config.snapshotDir, ViewerServer.viewerContext(config).path("namespace").asText());
        ObjectNode imported=store.chunked.importArchive(new ByteArrayInputStream(zip.body()));
        assertThat(imported.path("sourceSnapshotId").asText()).isEqualTo(id);
        assertThat(imported.path("id").asText()).isNotEqualTo(id);
        ObjectNode importedLast=store.chunked.events(imported.path("id").asText(),SEGMENT,"default","instance-1",13999,14000,1,262144,"all");
        assertThat(importedLast.path("events").get(0).path("seq").asInt()).isEqualTo(14000);
        ObjectNode projection = store.projection(id); assertThat(projection.path("partial").asBoolean()).isTrue();
        assertThat(Captures.bytes(projection).length).isLessThan(ViewerSnapshotStore.MAX_BYTES);
        assertThat(CaptureTools.projectViewerSnapshot(projection, null, null).path("partial").asBoolean()).isTrue();
        assertThat(json(request("POST", "/api/snapshots/" + id + "/rename", Json.obj("name", "Renamed")), 200).path("name").asText()).isEqualTo("Renamed");
    }
    @Test void rejectsCursorReorderingIdentityAndInvalidPageBudgets() {
        ObjectNode page = identity(); page.setAll(Json.obj("through", 20, "nextAfter", 2, "hasMore", true, "events", Json.arr(Json.obj("seq", 2, "eventIndex", 1), Json.obj("seq", 1, "eventIndex", 0))));
        assertThatThrownBy(() -> SegmentDetails.validatePage(page, SEGMENT, "instance-1", TRACE, 0, 20, 10, "all")).isInstanceOf(ToolException.class);
        page.set("events", Json.arr()); page.put("nextAfter", 0);
        assertThatThrownBy(() -> SegmentDetails.validatePage(page, SEGMENT, "instance-1", TRACE, 0, 20, 10, "all")).isInstanceOf(ToolException.class);
        assertThatThrownBy(() -> SegmentDetails.validateParameters(1, 0, 1, 4096, "all")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SegmentDetails.validateParameters(0, 2, 1001, 4096, "all")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SegmentDetails.validateParameters(0, 2, 1, Integer.MAX_VALUE, "all")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void chunkedHttpCallsRemainInOfflineComparisonWithoutDuplicateObservations() throws Exception {
        populate(3,0);overview.set("httpCalls",Json.arr(Json.obj("peer","http://ems.test","httpMethod","GET","path","/subscriptions/missing","httpStatus",404,"status","COMPLETED","spanId","bbbbbbbbbbbbbbbb")));
        JsonNode saved=json(request("POST","/api/snapshots/capture",Json.obj("traceId",TRACE,"name","HTTP evidence")),201);String id=saved.path("id").asText();assertThat(saved.path("schemaVersion").asInt()).isEqualTo(2);
        String second=json(request("POST","/api/snapshots/capture",Json.obj("traceId",TRACE,"name","Second HTTP evidence")),201).path("id").asText();
        agent.stop(0);agent=null;
        config.services.set(0,new Service("default",config.upstream,null,Json.obj("http://ems.test","cb-ems")));
        JsonNode result=json(request("POST","/api/capture-tools/compare",Json.obj("before",id,"after",id)),200);JsonNode calls=result.path("behavior").path("dependencies");
        assertThat(calls.size()).isEqualTo(1);assertThat(calls.get(0).path("disposition").asText()).isEqualTo("unchanged");assertThat(calls.get(0).path("before").path("count").asInt()).isEqualTo(1);
        assertThat(calls.get(0).path("destination").asText()).isEqualTo("http://ems.test");assertThat(calls.get(0).path("destinationName").asText()).isEqualTo("cb-ems");assertThat(calls.get(0).path("destinationNameSource").asText()).isEqualTo("configured");
        assertThat(calls.get(0).path("before").path("statuses").get(0).path("httpStatus").asInt()).isEqualTo(404);assertThat(calls.get(0).path("before").path("evidence").get(0).path("snapshotId").asText()).isEqualTo(id);
        JsonNode search=json(request("POST","/api/capture-tools/search",Json.obj("query","ems.test","category","destination","snapshotIds",Json.arr(id,second))),200);
        assertThat(search.path("scope").asText()).isEqualTo("selected-snapshots");assertThat(search.path("snapshotIds")).isEqualTo(Json.arr(id,second));assertThat(search.path("returned").asInt()).isEqualTo(2);assertThat(search.path("results").get(0).path("serviceInstanceId").asText()).isEqualTo("instance-1");
        assertThat(request("POST","/api/capture-tools/search",Json.obj("query","ems.test","snapshotIds",Json.arr(id,id))).statusCode()).isEqualTo(400);
        assertThat(exports.get()).isZero();
    }
    @Test void compactJourneyPreservesLateRawEventAnchors() {
        ObjectNode boundary=Json.obj("seq",150001,"eventIndex",150000,"type","EXTERNAL_CALL","attributes",Json.obj("boundaryKind","database","system","jdbc","direction","outbound","operation","execute","status","OBSERVED","evidenceKind","client-boundary"));
        ObjectNode segment=identity();segment.put("service","default");segment.put("segmentKey",Json.stringify(Json.arr("default","instance-1",SEGMENT)));
        segment.set("events",Json.arr(boundary));segment.setAll(Dependencies.extractBoundaries(segment.get("events")));
        ObjectNode journey=Federation.joinTrace(TRACE,Json.arr(segment),Json.arr());
        assertThat(journey.path("externalCalls").get(0).path("callKey").asText()).endsWith(":dependency:150000");
        assertThat(journey.path("externalCalls").get(0).path("eventSeq").asInt()).isEqualTo(150001);
    }
    @Test void malformedCursorCreatesExplicitPartialCaptureAndNeverLoops() throws Exception {
        populate(3, 0); badCursor = true;
        JsonNode saved = json(request("POST", "/api/snapshots/capture", Json.obj("traceId", TRACE, "name", "Partial")), 201);
        assertThat(saved.path("partial").asBoolean()).isTrue(); assertThat(saved.path("warnings").toString()).contains("cursor");
        assertThat(saved.path("segments").get(0).path("detailComplete").asBoolean()).isFalse();
    }
    @Test void rejectsTamperedChunkPathAndDecompressionBomb() throws Exception {
        populate(10, 1024);
        JsonNode saved = json(request("POST", "/api/snapshots/capture", Json.obj("traceId", TRACE, "name", "Tamper")), 201);
        String id = saved.path("id").asText(); ViewerSnapshotStore store = new ViewerSnapshotStore(config.snapshotDir, ViewerServer.viewerContext(config).path("namespace").asText());
        Path manifestPath = store.chunked.root.resolve(id).resolve("manifest.json"); ObjectNode original = (ObjectNode) Json.parse(Files.readAllBytes(manifestPath));
        ObjectNode corrupt = original.deepCopy(); ((ObjectNode) corrupt.path("chunks").get(0)).put("file", "../../outside"); Files.write(manifestPath, Captures.bytes(corrupt));
        assertThatThrownBy(() -> store.get(id)).isInstanceOf(ToolException.class);
        Files.write(manifestPath, Captures.bytes(original)); JsonNode descriptor = original.path("chunks").get(0);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) { gzip.write(new byte[2 * 1024 * 1024]); }
        Files.write(store.chunked.root.resolve(id).resolve(descriptor.path("file").asText()), bytes.toByteArray());
        original.put("compressedBytes",original.path("compressedBytes").asLong()-descriptor.path("compressedBytes").asLong()+bytes.size());
        ((ObjectNode)descriptor).put("compressedBytes",bytes.size());Files.write(manifestPath,Captures.bytes(original));
        assertThatThrownBy(() -> store.chunked.detail(id, SEGMENT, "default", "instance-1", "overview")).isInstanceOf(ToolException.class);
    }
    @Test void rejectsZipTraversalDuplicatesMissingEntriesAndSymlinkedChunks()throws Exception {
        populate(2,0);JsonNode saved=json(request("POST","/api/snapshots/capture",Json.obj("traceId",TRACE,"name","Archive")),201);
        String id=saved.path("id").asText();ViewerSnapshotStore store=new ViewerSnapshotStore(config.snapshotDir,ViewerServer.viewerContext(config).path("namespace").asText());
        Path directory=store.chunked.root.resolve(id);byte[] manifest=Files.readAllBytes(directory.resolve("manifest.json"));
        for(String malicious:new String[]{"../outside","000000.json.gz","manifest.json"}) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes)) {
                zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(manifest);zip.closeEntry();
                if(!"manifest.json".equals(malicious)){zip.putNextEntry(new ZipEntry(malicious));zip.write(new byte[0]);zip.closeEntry();}
            }
            assertThatThrownBy(()->store.chunked.importArchive(new ByteArrayInputStream(bytes.toByteArray()))).isInstanceOf(ToolException.class);
        }
        assertThat(store.chunked.list().path("count").asInt()).isEqualTo(1);
        Path first=directory.resolve("000000.json.gz"),outside=temp.resolve("outside.gz");Files.move(first,outside);Files.createSymbolicLink(first,outside);
        assertThatThrownBy(()->store.chunked.detail(id,SEGMENT,"default","instance-1","overview")).isInstanceOf(ToolException.class);
    }
    @Test void pinsConfiguredSourceOnSaveAndNeverRefreshesItFromChangedCheckout()throws Exception {
        populate(3,0);Path source=temp.resolve("src/main/java/sample/Service.java");Files.createDirectories(source.getParent());
        String prefix=String.join("",Collections.nCopies(11,"\n"));Files.write(source,(prefix+"return originalValue;\n").getBytes(StandardCharsets.UTF_8));
        JsonNode saved=json(request("POST","/api/snapshots/capture",Json.obj("traceId",TRACE,"name","Pinned source")),201);
        Files.write(source,(prefix+"return changedValue;\n").getBytes(StandardCharsets.UTF_8));agent.stop(0);agent=null;
        JsonNode page=json(request("GET","/api/snapshots/"+saved.path("id").asText()+"/segments/"+SEGMENT+"/events?after=1&through=3&limit=1",null),200);
        assertThat(page.path("events").get(0).path("sourceLine").asText()).isEqualTo("return originalValue;");
        assertThat(page.toString()).doesNotContain("changedValue");
    }
    @Test void omittedOversizedSourceIsExplicitWithoutDroppingRecordedEvents()throws Exception {
        populate(3,0);Path source=temp.resolve("src/main/java/sample/Service.java");Files.createDirectories(source.getParent());
        Files.write(source,(String.join("",Collections.nCopies(11,"\n"))+String.join("",Collections.nCopies(5000,"x"))).getBytes(StandardCharsets.UTF_8));
        JsonNode saved=json(request("POST","/api/snapshots/capture",Json.obj("traceId",TRACE,"name","Long source")),201);
        assertThat(saved.path("partial").asBoolean()).isTrue();assertThat(saved.path("warnings").toString()).contains("source text");
        JsonNode page=json(request("GET","/api/snapshots/"+saved.path("id").asText()+"/segments/"+SEGMENT+"/events?through=3",null),200);
        assertThat(page.path("events").size()).isEqualTo(3);assertThat(page.path("sourcePartial").asBoolean()).isTrue();
        assertThat(page.path("events").get(1).has("sourceLine")).isFalse();
    }
    @Test void validatesCaptureManifestBeforePublicationAndKeepsStoreUsable()throws Exception {
        populate(3,0);
        JsonNode failure=json(request("POST","/api/snapshots/capture",Json.obj("traceId",TRACE,"name","Invalid metadata",
                "request",Json.obj("traceId",TRACE,"services",Json.arr(Json.obj("name","default"))))),400);
        assertThat(failure.path("error").asText()).contains("request services");
        assertThat(json(request("GET","/api/snapshots",null),200).path("count").asInt()).isZero();
        JsonNode saved=json(request("POST","/api/snapshots/capture",Json.obj("traceId",TRACE,"name","Browser summary",
                "request",Json.obj("traceId",TRACE,"method","GET","path","/orders","services",Json.arr("default"),"durationMs",12.5,
                        "hasErrors",false,"outcome","success"))),201);
        assertThat(saved.path("request").path("services")).isEqualTo(Json.arr("default"));
        assertThat(json(request("GET","/api/snapshots",null),200).path("count").asInt()).isEqualTo(1);
        json(request("POST","/api/snapshots/capture",Json.obj("traceId",TRACE,"name","Older grouped metadata",
                "request",Json.obj("traceId",TRACE,"services",Json.arr("default"),"root",overview,"segments",Json.arr(overview)))),201);
    }
}
