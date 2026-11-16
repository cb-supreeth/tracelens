package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class SnapshotConcurrencyTest {
    @TempDir Path temp;
    private static final String TRACE = "0123456789abcdef0123456789abcdef";
    private static final String SEGMENT = "11111111-1111-1111-1111-111111111111";
    private ObjectNode identity() {
        return Json.obj("traceId", TRACE, "segmentId", SEGMENT, "serviceInstanceId", "test-instance",
                "applicationId", "default", "service", "default",
                "segmentKey", Json.stringify(Json.arr("default", "test-instance", SEGMENT)));
    }
    private SegmentDetails details(CountDownLatch entered, CountDownLatch release) {
        ToolConfig config = ToolConfig.fromArgs(new String[]{"--url", "http://127.0.0.1:1", "serve"});
        DebugClient client = new DebugClient("http://127.0.0.1:1") {
            @Override public ObjectNode overview(String id) {
                if (entered != null) { entered.countDown(); await(release); }
                ObjectNode row = identity(); row.setAll(Json.obj("through", 1, "events", Json.arr(), "eventCount", 1)); return row;
            }
            @Override public ObjectNode requestData(String id) { return identity(); }
            @Override public ObjectNode events(String id, long after, long through, int limit, int bytes, String kind) {
                ObjectNode row = identity(); row.setAll(Json.obj("through", 1, "nextAfter", 1, "hasMore", false,
                        "partial", false, "warnings", Json.arr(), "events", Json.arr(Json.obj("seq", 1, "eventIndex", 0,
                                "type", "LINE", "className", "sample.Service", "line", 1, "thread", "worker")))); return row;
            }
        };
        return new SegmentDetails(config, null, client);
    }
    ObjectNode save(ChunkedSnapshotStore store, CountDownLatch entered, CountDownLatch release) {
        return store.capture(TRACE, "Concurrency fixture", Json.obj("traceId", TRACE, "services", Json.arr("default")),
                Json.obj("traceId", TRACE, "segments", Json.arr(identity()), "partial", false, "warnings", Json.arr()), details(entered, release));
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Fixture did not release"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
    @Test void slowCaptureDoesNotBlockSavedReadsOrPublicationOfAnotherCapture() throws Exception {
        ChunkedSnapshotStore store = new ChunkedSnapshotStore(temp.toRealPath());
        String id = save(store, null, null).path("id").asText();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            Future<ObjectNode> slow = pool.submit(() -> save(store, entered, release)); await(entered);
            ObjectNode read = pool.submit(() -> store.get(id)).get(2, TimeUnit.SECONDS);
            assertThat(read.path("id").asText()).isEqualTo(id);
            assertThat(pool.submit(() -> save(store, null, null)).get(2, TimeUnit.SECONDS).path("id").asText()).isNotEqualTo(id);
            assertThat(store.list().path("count").asInt()).isEqualTo(2);
            release.countDown(); slow.get(5, TimeUnit.SECONDS);
            assertThat(store.list().path("count").asInt()).isEqualTo(3);
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void slowDownloadPinsOnlyItsSnapshotAndDeleteWaitsForIt() throws Exception {
        ChunkedSnapshotStore store = new ChunkedSnapshotStore(temp.toRealPath());
        String id = save(store, null, null).path("id").asText();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        OutputStream sink = new OutputStream() {
            @Override public void write(int value) { entered.countDown(); await(release); }
            @Override public void write(byte[] data, int off, int len) { write(0); }
        };
        try {
            Future<?> download = pool.submit(() -> store.download(id, sink)); await(entered);
            assertThat(pool.submit(() -> store.list()).get(2, TimeUnit.SECONDS).path("count").asInt()).isEqualTo(1);
            Future<?> deletion = pool.submit(() -> store.delete(id));
            assertThatThrownBy(() -> deletion.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); download.get(5, TimeUnit.SECONDS); deletion.get(5, TimeUnit.SECONDS);
            assertThat(store.list().path("count").asInt()).isZero();
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void stalledImportDoesNotBlockSavedReadsAndFailedImportReleasesReservation() throws Exception {
        ChunkedSnapshotStore store = new ChunkedSnapshotStore(temp.toRealPath());
        String id = save(store, null, null).path("id").asText();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        InputStream input = new InputStream() {
            @Override public int read() { entered.countDown(); await(release); return -1; }
        };
        try {
            Future<?> importing = pool.submit(() -> store.importArchive(input)); await(entered);
            assertThat(pool.submit(() -> store.get(id)).get(2, TimeUnit.SECONDS).path("id").asText()).isEqualTo(id);
            release.countDown(); assertThatThrownBy(() -> importing.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
            assertThat(store.list().path("warnings")).isEmpty();
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void payloadBytesDoNotConsumeAnalysisBudgetOrHideLaterServices() throws Exception {
        ChunkedSnapshotStore store = new ChunkedSnapshotStore(temp.toRealPath());
        String payload = String.join("", java.util.Collections.nCopies(32768, "x"));
        com.fasterxml.jackson.databind.node.ArrayNode rows = Json.arr();
        for (int i = 0; i < 3; i++) rows.add(identity().put("segmentId", "00000000-0000-0000-0000-00000000000" + i)
                .put("segmentKey", Json.stringify(Json.arr("default", "test-instance", "00000000-0000-0000-0000-00000000000" + i))));
        DebugClient client = new DebugClient("http://127.0.0.1:1") {
            ObjectNode row(String id) { for (com.fasterxml.jackson.databind.JsonNode item : rows) if (id.equals(item.path("segmentId").asText())) return ((ObjectNode) item).deepCopy(); throw new AssertionError(id); }
            @Override public ObjectNode overview(String id) { ObjectNode out = row(id); out.setAll(Json.obj("through", 80, "events", Json.arr(), "eventCount", 80)); return out; }
            @Override public ObjectNode requestData(String id) { return row(id); }
            @Override public ObjectNode events(String id, long after, long through, int limit, int bytes, String kind) {
                com.fasterxml.jackson.databind.node.ArrayNode events = Json.arr(); long next = Math.min(80, after + 3);
                for (long index = after; index < next; index++) events.add(Json.obj("seq", index + 1, "eventIndex", index,
                        "type", index % 2 == 0 ? "METHOD_ENTER" : "METHOD_EXIT", "className", "sample.Service", "methodName", "run",
                        "thread", "worker", "nanoTime", index + 1, "attributes", Json.obj("sql", payload)));
                ObjectNode out = row(id); out.setAll(Json.obj("through", 80, "nextAfter", next, "hasMore", next < 80,
                        "partial", false, "warnings", Json.arr(), "events", events)); return out;
            }
        };
        SegmentDetails details = new SegmentDetails(ToolConfig.fromArgs(new String[]{"--url", "http://127.0.0.1:1", "serve"}), null, client);
        ObjectNode saved = store.capture(TRACE, "Fair analysis", Json.obj("traceId", TRACE, "services", Json.arr("default")),
                Json.obj("traceId", TRACE, "segments", rows, "partial", false, "warnings", Json.arr()), details);
        assertThat(saved.path("rawBytes").asLong()).isGreaterThan(7 * 1024 * 1024);
        ObjectNode analysis = store.projection(saved.path("id").asText());
        assertThat(analysis.path("segments")).hasSize(3);
        for (com.fasterxml.jackson.databind.JsonNode segment : analysis.path("segments")) {
            assertThat(segment.path("events")).hasSize(80);
            assertThat(segment.path("analysisCoverage").path("complete").asBoolean()).isTrue();
            assertThat(segment.path("events").get(0).path("attributes").has("sql")).isFalse();
        }
        ObjectNode raw = store.events(saved.path("id").asText(), rows.get(2).path("segmentId").asText(), "default", "test-instance", 0, 80, 1, 262144, "all");
        assertThat(raw.path("events").get(0).path("attributes").path("sql").asText()).isEqualTo(payload);
    }
}
