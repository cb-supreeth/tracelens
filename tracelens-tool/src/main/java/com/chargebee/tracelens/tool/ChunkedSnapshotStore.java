package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.zip.*;

/** Owner-only immutable compressed chunks; the small manifest is published last by directory rename. */
final class ChunkedSnapshotStore extends CaptureStore {
    static final long MAX_RAW = 128L * 1024 * 1024, MAX_COMPRESSED = 64L * 1024 * 1024;
    static final long MAX_TOTAL_RAW = 512L * 1024 * 1024, MAX_TOTAL_COMPRESSED = 256L * 1024 * 1024;
    static final int MAX_MANIFEST = 1024 * 1024, MAX_CHUNKS = 4096, MAX_COUNT = 20;
    private static final String MODE = "rw-------";
    ChunkedSnapshotStore(Path namespaceRoot) { super(namespaceRoot.resolve("chunked-v2")); }
    private static void validId(String id) {
        if (Captures.match(id, Captures.UUID_RE) == null) throw new ToolException(404, "Snapshot ID must be a canonical UUID.");
    }
    private CaptureStore directory(String name) throws IOException {
        CaptureStore result = new CaptureStore(root.resolve(name)); result.ensureDirectory(false); return result;
    }
    private List<String> directories() throws IOException {
        List<String> result = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path path : entries) {
                String name = path.getFileName().toString();
                if (name.matches(Captures.UUID_RE) || pendingName(name)) {
                    directory(name); result.add(name);
                    if (result.size() > MAX_COUNT) throw failure("Chunked snapshot count exceeds quota; review storage manually.");
                }
            }
        }
        Collections.sort(result); return result;
    }
    private byte[] readFile(CaptureStore dir, String name, int maximum) throws IOException {
        if (!name.equals("manifest.json") && !name.matches("[0-9]{6}\\.json\\.gz")) throw failure("Invalid saved chunk path.");
        Path path = dir.root.resolve(name); Map<String, Object> before = dir.privateFile(path, false, false);
        if (((Number) before.get("size")).longValue() > maximum) throw failure("Saved chunk exceeds its byte budget.");
        byte[] raw;
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            dir.sameFile(path, before); raw = DebugClient.readBounded(input, maximum, System.nanoTime() + 5_000_000_000L);
        }
        dir.sameFile(path, before); return raw;
    }
    private ObjectNode manifest(String id) throws IOException {
        validId(id); ObjectNode doc = strictJson(readFile(directory(id), "manifest.json", MAX_MANIFEST));
        validateManifest(doc, id); return doc;
    }
    private void validateManifest(ObjectNode doc, String id) {
        validId(id);
        if (doc.path("schemaVersion").asInt() != 2 || !id.equals(Json.text(doc, "id")) || !"viewer".equals(Json.text(doc, "storage"))
                || !doc.path("chunks").isArray() || doc.path("chunks").size() > MAX_CHUNKS || !doc.path("segments").isArray()
                || doc.path("segments").size() > 64 || !doc.path("journey").isObject()) throw failure("Invalid saved chunk manifest.");
        if (!ViewerSnapshotStore.cleanName(doc.get("name")).equals(Json.text(doc,"name"))) throw failure("Invalid saved name.");
        SegmentDetails.id(Json.text(doc,"traceId"));
        Set<String> identities = new HashSet<>();
        for(JsonNode segment:doc.path("segments")) {
            String key=ViewerSnapshotStore.identity(segment);
            if(!identities.add(key))throw failure("Duplicate saved segment identity.");
            SegmentDetails.identity(segment,Json.text(segment,"segmentId"),Json.text(segment,"serviceInstanceId"),Json.text(doc,"traceId"));
        }
        Set<String> names = new HashSet<>(); long raw = 0, compressed = 0;
        for (JsonNode chunk : doc.path("chunks")) {
            String name = Json.text(chunk, "file", "");
            if (!name.matches("[0-9]{6}\\.json\\.gz") || !names.add(name)
                    || !Json.text(chunk, "sha256", "").matches("[0-9a-f]{64}")
                    || !identities.contains(Json.text(chunk,"segmentKey"))
                    || !Arrays.asList("overview","request-data","events").contains(Json.text(chunk,"kind"))) throw failure("Invalid saved chunk identity.");
            long rawBytes = SegmentDetails.number(chunk.get("rawBytes"), "raw size");
            long compressedBytes = SegmentDetails.number(chunk.get("compressedBytes"), "compressed size");
            if (rawBytes > SegmentDetails.MAX_PAGE_BYTES || compressedBytes > SegmentDetails.MAX_PAGE_BYTES + 8192)
                throw failure("Saved chunk size exceeds the limit.");
            raw += rawBytes; compressed += compressedBytes;
        }
        if (raw > MAX_RAW || compressed > MAX_COMPRESSED || raw != doc.path("rawBytes").asLong(-1)
                || compressed != doc.path("compressedBytes").asLong(-1)) throw failure("Saved chunk quota does not match manifest.");
        ObjectNode envelope=doc.deepCopy();envelope.put("schemaVersion",1);
        for(JsonNode segment:envelope.path("segments"))((ObjectNode)segment).set("events",Json.arr());
        ViewerSnapshotStore.validateSnapshot(envelope);
    }
    private ObjectNode chunk(String id, JsonNode descriptor) throws IOException {
        byte[] compressed = readFile(directory(id), Json.text(descriptor, "file"), SegmentDetails.MAX_PAGE_BYTES + 8192);
        return decodeChunk(compressed,descriptor);
    }
    private ObjectNode decodeChunk(byte[] compressed,JsonNode descriptor)throws IOException {
        if (compressed.length != descriptor.path("compressedBytes").asInt()) throw failure("Saved compressed chunk size changed.");
        byte[] raw;
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            raw = DebugClient.readBounded(input, descriptor.path("rawBytes").asInt(), System.nanoTime() + 5_000_000_000L);
        }
        if (raw.length != descriptor.path("rawBytes").asInt() || !Json.sha256(new String(raw, java.nio.charset.StandardCharsets.UTF_8)).equals(Json.text(descriptor, "sha256")))
            throw failure("Saved chunk integrity failed; no live data was substituted.");
        return strictJson(raw);
    }
    private void write(CaptureStore dir, String name, byte[] bytes) throws IOException {
        Path path = dir.root.resolve(name);
        try (FileChannel out = FileChannel.open(path, EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(MODE)))) {
            ByteBuffer data = ByteBuffer.wrap(bytes); while (data.hasRemaining()) out.write(data); out.force(true);
        }
        dir.privateFile(path, false, false);
    }
    private void addChunk(CaptureStore dir, ObjectNode doc, JsonNode segment, String kind, ObjectNode data) throws IOException {
        ArrayNode chunks = (ArrayNode) doc.get("chunks");
        if (chunks.size() >= MAX_CHUNKS) throw new ToolException(413, "Snapshot chunk limit reached.");
        byte[] raw = Captures.bytes(data);
        if (raw.length > SegmentDetails.MAX_PAGE_BYTES) throw new ToolException(413, "Snapshot chunk is too large.");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) { gzip.write(raw); }
        byte[] compressed = output.toByteArray();
        long rawTotal = doc.path("rawBytes").asLong() + raw.length, compressedTotal = doc.path("compressedBytes").asLong() + compressed.length;
        if (rawTotal > MAX_RAW || compressedTotal > MAX_COMPRESSED) throw new ToolException(413, "Snapshot compressed or decompressed quota reached.");
        String name = String.format(Locale.ROOT, "%06d.json.gz", chunks.size());
        write(dir, name, compressed);
        ObjectNode descriptor = Json.obj("file", name, "kind", kind, "segmentKey", segment.path("segmentKey"),
                "rawBytes", raw.length, "compressedBytes", compressed.length, "sha256", Json.sha256(new String(raw, java.nio.charset.StandardCharsets.UTF_8)));
        if ("events".equals(kind)) descriptor.setAll(Captures.select(data, "after", "through", "nextAfter", "hasMore"));
        chunks.add(descriptor); doc.put("rawBytes", rawTotal); doc.put("compressedBytes", compressedTotal);
    }
    private long[] usage() throws IOException {
        long count = 0, raw = 0, compressed = 0;
        for (String name : directories()) {
            count++;
            if (!pendingName(name)) { ObjectNode doc = manifest(name); raw += doc.path("rawBytes").asLong(); compressed += doc.path("compressedBytes").asLong(); }
            else {
                // Interrupted writes are charged pessimistically; they are not silently deleted or reused.
                raw += MAX_RAW; compressed += MAX_COMPRESSED;
            }
        }
        return new long[]{count, raw, compressed};
    }
    private CaptureStore reserve(String pending) {
        return locked(true, () -> {
            long[] used = usage();
            if (used[0] >= MAX_COUNT || used[1] + MAX_RAW > MAX_TOTAL_RAW || used[2] + MAX_COMPRESSED > MAX_TOTAL_COMPRESSED)
                throw failure("Insufficient capacity to reserve a chunked capture; wait for active saves or review storage.");
            Files.createDirectory(root.resolve(pending), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            return directory(pending);
        });
    }
    private ObjectNode publishStaged(CaptureStore dir, ObjectNode doc) {
        return locked(false, true, () -> {
            long[] used = usage();
            // The private pending directory already reserves a full capture's capacity.
            if (used[1] - MAX_RAW + doc.path("rawBytes").asLong() > MAX_TOTAL_RAW
                    || used[2] - MAX_COMPRESSED + doc.path("compressedBytes").asLong() > MAX_TOTAL_COMPRESSED)
                throw failure("Chunked snapshot store quota reached; nothing was saved.");
            dir.ensureDirectory(false);
            Files.move(dir.root, root.resolve(Json.text(doc, "id")), StandardCopyOption.ATOMIC_MOVE);
            syncDirectory(); return publicManifest(doc);
        });
    }
    private void discardStaged(CaptureStore dir, Collection<String> files) {
        locked(false, true, () -> {
            if (!Files.exists(dir.root, LinkOption.NOFOLLOW_LINKS)) return null;
            dir.ensureDirectory(false);
            for (String file : files) Files.deleteIfExists(dir.root.resolve(file));
            Files.deleteIfExists(dir.root.resolve("manifest.json")); Files.delete(dir.root); return null;
        });
    }
    ObjectNode capture(String traceId, String name, ObjectNode request, ObjectNode journey, SegmentDetails details) {
        SegmentDetails.id(traceId); String cleaned = ViewerSnapshotStore.cleanName(Json.node(name));
            String id = UUID.randomUUID().toString(), pending = ".pending-" + id;
            CaptureStore dir = reserve(pending); boolean published = false;
            ObjectNode doc = Json.obj("schemaVersion", 2, "storage", "viewer", "id", id, "name", cleaned, "traceId", traceId,
                    "capturedAt", System.currentTimeMillis(), "savedAt", System.currentTimeMillis(), "request", request,
                    "journey", journey, "segmentSummaries", journey.path("segments"), "segments", Json.arr(), "chunks", Json.arr(),
                    "partial", journey.path("partial").asBoolean(), "warnings", journey.path("warnings").isArray() ? journey.path("warnings").deepCopy() : Json.arr(),
                    "rawBytes", 0, "compressedBytes", 0);
            long deadline = System.nanoTime() + 60_000_000_000L;
            try {
                for (JsonNode summary : journey.path("segments")) {
                    if (doc.path("segments").size() >= 64 || System.nanoTime() > deadline) { partial(doc, "Segment or capture-time limit reached; remaining details were not saved."); break; }
                    ObjectNode segment = Json.copy(summary); segment.put("capturedThrough", 0); segment.put("savedEventCount", 0); segment.put("detailComplete", false);
                    ((ArrayNode) doc.get("segments")).add(segment);
                    String segmentId = Json.text(summary, "segmentId"), service = Json.text(summary, "service"), instance = Json.text(summary, "serviceInstanceId");
                    try {
                        ObjectNode overview = details.overview(segmentId, service, instance);
                        SegmentDetails.identity(overview, segmentId, instance, traceId);
                        long through = SegmentDetails.number(overview.get("through"), "high-water");
                        segment.setAll(Captures.select(overview, "through", "captureMode", "detailsAvailable", "codeDetailAvailable", "detailTruncated", "dropReasons", "omittedDetailCallbacks"));
                        if (overview.has("recordingManifest")) segment.set("recordingManifest", overview.get("recordingManifest"));
                        if(overview.path("detailTruncated").asBoolean()||overview.path("omittedDetailCallbacks").asLong()>0)
                            partial(doc,"Capture contains omitted detail callbacks; inspect the saved capture mode and drop reasons for "+segmentId+".");
                        addChunk(dir, doc, segment, "overview", overview);
                        try { addChunk(dir, doc, segment, "request-data", details.requestData(segmentId, service, instance, traceId)); }
                        catch (ToolException error) { if (error.status == 413) throw error; partial(doc, "Request data unavailable for " + segmentId + "."); }
                        long after = 0; boolean more = true;
                        while (more) {
                            if (System.nanoTime() > deadline) throw new ToolException(408, "Capture time limit reached.");
                            ObjectNode page = details.events(segmentId, service, instance, traceId, after, through, SegmentDetails.PAGE_EVENTS, SegmentDetails.PAGE_BYTES/2, "all");
                            page=details.enrichForCapture(page,service,SegmentDetails.PAGE_BYTES);
                            if(page.path("sourcePartial").asBoolean()){segment.put("sourcePartial",true);partial(doc,"Some source text was omitted from saved pages for "+segmentId+"; recorded events remain available.");}
                            page.put("after", after); addChunk(dir, doc, segment, "events", page);
                            after = page.path("nextAfter").asLong(); more = page.path("hasMore").asBoolean();
                            segment.put("capturedThrough", after); segment.put("savedEventCount", segment.path("savedEventCount").asLong() + page.path("events").size());
                            if (page.path("partial").asBoolean()) { segment.put("partial", true); partial(doc, "Agent reported missing or oversized event evidence for " + segmentId + "."); }
                        }
                        segment.put("detailComplete", !segment.path("partial").asBoolean());
                    } catch (ToolException error) {
                        if (error.status == 413) throw error;
                        segment.put("partial", true); partial(doc, "Detail capture incomplete for " + segmentId + ": " + error.getMessage());
                    }
                }
                validateManifest(doc,id);
                byte[] manifest = Captures.bytes(doc); if (manifest.length > MAX_MANIFEST) throw new ToolException(413, "Snapshot manifest limit reached; nothing was saved.");
                write(dir, "manifest.json", manifest); dir.syncDirectory();
                ObjectNode result = publishStaged(dir, doc); published = true; return result;
            } catch (IOException | SecurityException error) {
                throw failure("Private capture staging failed; nothing was published.");
            } finally {
                if (!published) {
                    // Only remove files created by this transaction, never caller-supplied paths.
                    List<String> files = new ArrayList<>();
                    for (JsonNode chunk : doc.path("chunks")) files.add(Json.text(chunk, "file"));
                    discardStaged(dir, files);
                }
            }
    }
    private static void partial(ObjectNode doc, String message) {
        doc.put("partial", true); ArrayNode warnings = (ArrayNode) doc.get("warnings"); if (warnings.size() < 256) warnings.add(message);
    }
    private static ObjectNode publicManifest(ObjectNode doc) {
        ObjectNode result = doc.deepCopy(); result.remove("chunks"); result.put("chunked", true); return result;
    }
    private static ObjectNode summary(ObjectNode doc) {
        ObjectNode result = Captures.select(doc, "id", "name", "traceId", "capturedAt", "savedAt", "partial", "warnings", "storage", "schemaVersion", "request", "rawBytes", "compressedBytes");
        result.put("segmentCount", doc.path("segments").size()); result.put("sizeBytes", doc.path("compressedBytes").asLong()); result.put("chunked", true); return result;
    }
    @Override public ObjectNode list() {
        ArrayNode rows = Json.arr(), warnings = Json.arr(); long[] totals = {0, 0};
        try { locked(false, () -> { for (String id : directories()) {
            if (pendingName(id)) { warnings.add("Active or interrupted chunked capture reserves capacity; review only after active saves finish."); continue; }
            ObjectNode doc = manifest(id); rows.add(summary(doc)); totals[0] += doc.path("compressedBytes").asLong(); totals[1] += doc.path("rawBytes").asLong();
        } return null; }); } catch (Missing ignored) { }
        return Json.obj("snapshots", rows, "count", rows.size(), "totalBytes", totals[0], "totalRawBytes", totals[1], "warnings", warnings,
                "limits", Json.obj("maxCount", MAX_COUNT, "maxRawBytes", MAX_RAW, "maxCompressedBytes", MAX_COMPRESSED,
                        "maxTotalRawBytes", MAX_TOTAL_RAW, "maxTotalCompressedBytes", MAX_TOTAL_COMPRESSED));
    }
    @Override public ObjectNode get(String id) {
        validId(id); try { return locked(false, () -> publicManifest(manifest(id))); }
        catch (Missing absent) { throw new ToolException(404, "Saved chunked snapshot not found; no live data was substituted."); }
    }
    private JsonNode segment(ObjectNode doc, String id, String service, String instance) {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode row : doc.path("segments")) if (id.equals(Json.text(row, "segmentId"))
                && (service == null || service.equals(Json.text(row, "service"))) && (instance == null || instance.equals(Json.text(row, "serviceInstanceId")))) found.add(row);
        if (found.size() != 1) throw new ToolException(404, "Saved segment is absent or ambiguous; no live data was substituted.");
        return found.get(0);
    }
    ObjectNode detail(String snapshotId, String segmentId, String service, String instance, String kind) {
        validId(snapshotId); try { return new CaptureStore(root.resolve(snapshotId)).locked(false, true, () -> {
            ObjectNode doc = manifest(snapshotId); JsonNode segment = segment(doc, segmentId, service, instance);
            for (JsonNode descriptor : doc.path("chunks")) if (kind.equals(Json.text(descriptor, "kind")) && segment.path("segmentKey").equals(descriptor.path("segmentKey"))) {
                ObjectNode data = chunk(snapshotId, descriptor); SegmentDetails.identity(data, segmentId, Json.text(segment, "serviceInstanceId"), Json.text(doc, "traceId")); return data;
            }
            throw new ToolException(404, "This detail was not saved; no live data was substituted.");
        }); } catch (Missing absent) { throw new ToolException(404, "Saved detail unavailable; no live data was substituted."); }
    }
    ObjectNode events(String snapshotId, String segmentId, String service, String instance, long after, long through, int limit, int maxBytes, String kind) {
        validId(snapshotId); SegmentDetails.validateParameters(after, through, limit, maxBytes, kind);
        try { return new CaptureStore(root.resolve(snapshotId)).locked(false, true, () -> {
            ObjectNode doc = manifest(snapshotId); JsonNode segment = segment(doc, segmentId, service, instance);
            if (through != segment.path("through").asLong(-1)) throw new ToolException(409, "Saved high-water is immutable.");
            ArrayNode events = Json.arr(), warnings = Json.arr(); long next = after, captured = segment.path("capturedThrough").asLong();
            ObjectNode result = Captures.select(segment, "traceId", "segmentId", "serviceInstanceId", "service", "segmentKey", "captureMode", "detailsAvailable");
            result.setAll(Json.obj("after", after, "through", through, "events", events, "nextAfter", after, "hasMore", false,
                    "partial", !segment.path("detailComplete").asBoolean(), "warnings", warnings, "saved", true, "kind", kind));
            int bytes = Captures.bytes(result).length + 512; boolean full = false;
            for (JsonNode descriptor : doc.path("chunks")) {
                if (!"events".equals(Json.text(descriptor, "kind")) || !segment.path("segmentKey").equals(descriptor.path("segmentKey"))
                        || descriptor.path("nextAfter").asLong() <= after) continue;
                ObjectNode page = chunk(snapshotId, descriptor);
                SegmentDetails.validatePage(page, segmentId, Json.text(segment, "serviceInstanceId"), Json.text(doc, "traceId"),
                        descriptor.path("after").asLong(), through, SegmentDetails.PAGE_EVENTS, "all");
                for (JsonNode event : page.path("events")) {
                    long seq = event.path("seq").asLong(); if (seq <= after) continue;
                    if (!matches(event, kind)) { next = seq; continue; }
                    int size = Captures.bytes(event).length + 1;
                    if (events.size() >= limit || bytes + size > maxBytes && events.size() > 0) { full = true; break; }
                    next = seq;
                    if (bytes + size > maxBytes) { result.put("partial", true); if(warnings.size()==0)warnings.add("Oversized saved event omitted from this page."); continue; }
                    events.add(event); bytes += size;
                }
                if (full) break;
                next = Math.max(next, page.path("nextAfter").asLong());
                if (page.path("partial").asBoolean()) result.put("partial", true);
            }
            result.put("nextAfter", Math.min(through, next)); result.put("hasMore", next < captured);
            if (!segment.path("detailComplete").asBoolean()) warnings.add("Saved evidence is partial; unavailable events were not replaced with live data.");
            if(segment.path("sourcePartial").asBoolean()){result.put("sourcePartial",true);warnings.add("Some source text was omitted when saved; recorded events are unchanged.");}
            if(Captures.bytes(result).length>maxBytes)throw new ToolException(413,"Saved page envelope exceeds requested byte budget.");
            return result;
        }); } catch (Missing absent) { throw new ToolException(404, "Saved detail unavailable; no live data was substituted."); }
    }
    private static boolean matches(JsonNode event, String kind) {
        String type = Json.text(event, "type"); return "all".equals(kind) || "methods".equals(kind) && !"LINE".equals(type)
                || "boundaries".equals(kind) && Arrays.asList("EXTERNAL_CALL", "DB_ROUTE", "EXCEPTION").contains(type);
    }
    ObjectNode projection(String id) {
        validId(id); try { return new CaptureStore(root.resolve(id)).locked(false, true, () -> {
            ObjectNode doc = manifest(id), result = publicManifest(doc); result.put("schemaVersion", 1);
            ArrayNode exports = Json.arr(); result.set("segments", exports);
            int perSegmentBytes = 2 * 1024 * 1024 / Math.max(1, doc.path("segments").size());
            long perSegmentReadBytes = 32L * 1024 * 1024 / Math.max(1, doc.path("segments").size());
            long deadline = System.nanoTime() + 10_000_000_000L;
            for (JsonNode segment : doc.path("segments")) {
                ObjectNode exported = Json.copy(segment); ArrayNode events = Json.arr(); exported.set("events", events); exports.add(exported);
                boolean omitted = false; int remaining = perSegmentBytes; long readBytes = 0;
                for (JsonNode descriptor : doc.path("chunks")) {
                    if (!segment.path("segmentKey").equals(descriptor.path("segmentKey")) || !"events".equals(Json.text(descriptor, "kind"))) continue;
                    if (remaining <= 0 || readBytes + descriptor.path("rawBytes").asLong() > perSegmentReadBytes
                            || System.nanoTime() > deadline) { omitted = true; break; }
                    ObjectNode page = chunk(id, descriptor);
                    readBytes += descriptor.path("rawBytes").asLong();
                    for (JsonNode event : page.path("events")) {
                        ObjectNode projected = analysisEvent(event);
                        int size = Captures.bytes(projected).length + 1;
                        if (size > remaining) { omitted = true; remaining = 0; break; }
                        events.add(projected); remaining -= size;
                    }
                }
                exported.set("analysisCoverage", Json.obj("savedEvents", segment.path("savedEventCount"),
                        "retainedEvents", events.size(), "rawBytesRead", readBytes, "complete", !omitted && segment.path("detailComplete").asBoolean()));
                if (omitted || !segment.path("detailComplete").asBoolean()) {
                    exported.put("truncated", true); partial(result, "Bounded offline analysis contains a per-segment saved-event prefix for " + Json.text(segment, "segmentId") + "; remaining raw evidence is still saved.");
                }
            }
            if (exports.size() == 0) throw new ToolException(409, "Saved capture has no projected segment details.");
            return result;
        }); } catch (Missing absent) { throw new ToolException(404, "Saved snapshot not found; no live data was substituted."); }
    }
    private static ObjectNode analysisEvent(JsonNode event) {
        ObjectNode result = Json.obj();
        for (String key : Arrays.asList("seq", "eventIndex", "type", "thread", "threadId", "className", "methodName",
                "methodDescriptor", "line", "nanoTime", "timestamp", "fieldsTruncated", "detailGapBefore"))
            if (event.has(key)) result.set(key, event.get(key));
        ObjectNode attributes = result.putObject("attributes");
        for (String key : Arrays.asList("boundaryKind", "system", "direction", "destination", "operation", "status",
                "evidenceKind", "errorType", "exceptionType", "route", "detail", "durationMs", "batchSize", "queryLimit",
                "sqlSource", "sqlTruncated"))
            if (event.path("attributes").has(key)) attributes.set(key, event.path("attributes").get(key));
        return result;
    }
    ObjectNode rename(String id, String name) {
        validId(id); String cleaned = ViewerSnapshotStore.cleanName(Json.node(name));
        try { return new CaptureStore(root.resolve(id)).locked(false, true, () -> locked(false, true, () -> {
            ObjectNode doc = manifest(id); doc.put("name", cleaned); CaptureStore dir = directory(id);
            byte[] bytes = Captures.bytes(doc); if (bytes.length > MAX_MANIFEST) throw failure("Manifest limit exceeded.");
            String temporary = ".rename-" + UUID.randomUUID();
            try { write(dir, temporary, bytes); Files.move(dir.root.resolve(temporary), dir.root.resolve("manifest.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); dir.syncDirectory(); }
            finally { Files.deleteIfExists(dir.root.resolve(temporary)); }
            return summary(doc);
        })); } catch (Missing absent) { throw new ToolException(404, "Saved snapshot not found."); }
    }
    ObjectNode delete(String id) {
        validId(id); try { return new CaptureStore(root.resolve(id)).locked(false, true, () -> locked(false, true, () -> {
            ObjectNode doc = manifest(id); CaptureStore dir = directory(id);
            for (JsonNode descriptor : doc.path("chunks")) dir.privateFile(dir.root.resolve(Json.text(descriptor, "file")), false, false);
            // Remove the published path before unlinking its lock, so another JVM
            // cannot create a second lock inode in a half-deleted snapshot.
            Path retired = root.resolve(".pending-" + UUID.randomUUID());
            Files.move(dir.root, retired, StandardCopyOption.ATOMIC_MOVE); syncDirectory();
            for (JsonNode descriptor : doc.path("chunks")) Files.delete(retired.resolve(Json.text(descriptor, "file")));
            Files.delete(retired.resolve("manifest.json")); Files.delete(retired.resolve(".lock")); Files.delete(retired); syncDirectory(); return Json.obj("deleted", true, "id", id);
        })); } catch (Missing absent) { throw new ToolException(404, "Saved snapshot not found."); }
    }
    void download(String id, OutputStream output) {
        validId(id); new CaptureStore(root.resolve(id)).locked(false, true, () -> {
            ObjectNode doc = manifest(id); CaptureStore dir = directory(id);
            ZipOutputStream zip = new ZipOutputStream(output); zip.putNextEntry(new ZipEntry("manifest.json")); zip.write(Captures.bytes(doc)); zip.closeEntry();
            for (JsonNode descriptor : doc.path("chunks")) {
                chunk(id,descriptor);
                byte[] bytes = readFile(dir, Json.text(descriptor, "file"), SegmentDetails.MAX_PAGE_BYTES + 8192);
                if (bytes.length != descriptor.path("compressedBytes").asInt()) throw failure("Saved compressed chunk size changed.");
                zip.putNextEntry(new ZipEntry(Json.text(descriptor, "file"))); zip.write(bytes); zip.closeEntry();
            }
            zip.finish(); zip.flush(); return null;
        });
    }
    ObjectNode importArchive(InputStream source) {
            String id=UUID.randomUUID().toString(),pending=".pending-"+id;
            CaptureStore dir=reserve(pending);List<String>written=new ArrayList<>();boolean published=false;
            long deadline=System.nanoTime()+60_000_000_000L;
            try(ZipInputStream zip=new ZipInputStream(source)) {
                ZipEntry first=zip.getNextEntry();
                if(first==null||!"manifest.json".equals(first.getName())||first.isDirectory())throw failure("Archive must begin with a TraceLens manifest.");
                ObjectNode doc=strictJson(DebugClient.readBounded(zip,MAX_MANIFEST,System.nanoTime()+5_000_000_000L));
                validateManifest(doc,Json.text(doc,"id"));
                Map<String,JsonNode> expected=new LinkedHashMap<>();for(JsonNode descriptor:doc.path("chunks"))expected.put(Json.text(descriptor,"file"),descriptor);
                Set<String>seen=new HashSet<>();Map<String,Long>cursors=new HashMap<>();ZipEntry entry;
                while((entry=zip.getNextEntry())!=null) {
                    if(System.nanoTime()>deadline)throw new ToolException(408,"Archive import time limit reached.");
                    String name=entry.getName();JsonNode descriptor=expected.get(name);
                    if(entry.isDirectory()||descriptor==null||!seen.add(name))throw failure("Unexpected, duplicated or unsafe archive entry.");
                    byte[] bytes=DebugClient.readBounded(zip,descriptor.path("compressedBytes").asInt(),Math.min(deadline,System.nanoTime()+5_000_000_000L));
                    ObjectNode data=decodeChunk(bytes,descriptor);JsonNode segment=null;
                    for(JsonNode candidate:doc.path("segments"))if(candidate.path("segmentKey").equals(descriptor.path("segmentKey")))segment=candidate;
                    if(segment==null)throw failure("Archive chunk has no matching segment.");
                    SegmentDetails.identity(data,Json.text(segment,"segmentId"),Json.text(segment,"serviceInstanceId"),Json.text(doc,"traceId"));
                    if("events".equals(Json.text(descriptor,"kind"))) {
                        long after=descriptor.path("after").asLong(-1),through=SegmentDetails.number(segment.get("through"),"high-water");
                        String key=Json.text(segment,"segmentKey");if(after!=cursors.getOrDefault(key,0L))throw failure("Archive event pages are missing or reordered.");
                        SegmentDetails.validatePage(data,Json.text(segment,"segmentId"),Json.text(segment,"serviceInstanceId"),Json.text(doc,"traceId"),after,through,SegmentDetails.PAGE_EVENTS,"all");
                        if(data.path("nextAfter").asLong()!=descriptor.path("nextAfter").asLong())throw failure("Archive cursor does not match its manifest.");
                        cursors.put(key,data.path("nextAfter").asLong());
                    }
                    write(dir,name,bytes);written.add(name);
                }
                if(seen.size()!=expected.size())throw failure("Archive is missing saved chunks.");
                for(JsonNode segment:doc.path("segments"))if(cursors.getOrDefault(Json.text(segment,"segmentKey"),0L)!=segment.path("capturedThrough").asLong())
                    throw failure("Archive captured high-water does not match its event pages.");
                doc.put("sourceSnapshotId",Json.text(doc,"id"));doc.put("id",id);doc.put("savedAt",System.currentTimeMillis());
                byte[] manifest=Captures.bytes(doc);if(manifest.length>MAX_MANIFEST)throw failure("Imported manifest exceeds its size limit.");
                write(dir,"manifest.json",manifest);written.add("manifest.json");dir.syncDirectory();
                ObjectNode result=publishStaged(dir,doc);published=true;return result;
            } catch(IOException | SecurityException error) {
                throw failure("Private archive staging failed; nothing was published.");
            } finally {
                if(!published)discardStaged(dir,written);
            }
    }
}
