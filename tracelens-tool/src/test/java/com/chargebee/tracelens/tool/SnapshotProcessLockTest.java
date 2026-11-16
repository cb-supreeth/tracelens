package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class SnapshotProcessLockTest {
    @TempDir Path temp;
    private String save(ChunkedSnapshotStore store) {
        return new SnapshotConcurrencyTest().save(store, null, null).path("id").asText();
    }
    @Test void downloadPinsItsSnapshotAcrossProcessesWithoutHoldingTheStoreLock() throws Exception {
        Path root = temp.toRealPath(); ChunkedSnapshotStore store = new ChunkedSnapshotStore(root);
        String pinned = save(store), other = save(store);
        try (Child download = new Child("download", root, pinned); Child deletion = new Child("delete", root, pinned, download)) {
            deletion.await("started");
            assertThat(deletion.process.waitFor(200, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(CompletableFuture.supplyAsync(store::list).get(3, TimeUnit.SECONDS).path("count").asInt()).isEqualTo(2);
            assertThat(CompletableFuture.supplyAsync(() -> store.get(other)).get(3, TimeUnit.SECONDS).path("id").asText()).isEqualTo(other);
            download.release(); download.succeeded(); deletion.succeeded();
            assertThatThrownBy(() -> store.get(pinned)).isInstanceOf(ToolException.class)
                    .satisfies(error -> assertThat(((ToolException) error).status).isEqualTo(404));
            assertThat(store.list().path("count").asInt()).isEqualTo(1);
            assertThat(store.list().path("warnings")).isEmpty();
            assertThat(Files.exists(store.root.resolve(pinned))).isFalse();
            try (Child missing = new Child("missing", root, pinned)) { missing.succeeded(); }
            assertThat(Files.exists(store.root.resolve(pinned))).isFalse();
        }
    }
    @Test void renameWaitsForAnotherJvmDownloadAndDoesNotChangeItsPinnedManifest() throws Exception {
        Path root = temp.toRealPath(); ChunkedSnapshotStore store = new ChunkedSnapshotStore(root); String id = save(store);
        try (Child download = new Child("download", root, id); Child rename = new Child("rename", root, id, download)) {
            rename.await("started");
            assertThat(rename.process.waitFor(200, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(CompletableFuture.supplyAsync(() -> store.get(id)).get(3, TimeUnit.SECONDS).path("name").asText()).isEqualTo("Concurrency fixture");
            download.release(); download.succeeded(); rename.succeeded();
            assertThat(store.get(id).path("name").asText()).isEqualTo("Renamed in another JVM");
        }
    }
    private final class Child implements AutoCloseable {
        final Process process; final Path signals, log;
        Child(String mode, Path root, String id) throws Exception { this(mode, root, id, null); }
        Child(String mode, Path root, String id, Child pinned) throws Exception {
            if (pinned != null) pinned.await("pinned");
            signals = Files.createTempDirectory(temp, mode + "-"); log = signals.resolve("worker.log");
            Set<String> entries = new LinkedHashSet<>();
            for (Class<?> type : Arrays.asList(Worker.class, ChunkedSnapshotStore.class, JsonNode.class,
                    com.fasterxml.jackson.core.JsonFactory.class, com.fasterxml.jackson.annotation.JsonProperty.class))
                entries.add(Paths.get(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
            java.net.URL resource = Captures.class.getResource("/unicode-casefold.json");
            assertThat(resource).withFailMessage("Fixture requires the tool's Unicode case-fold resource").isNotNull();
            if ("file".equals(resource.getProtocol())) entries.add(Paths.get(resource.toURI()).getParent().toString());
            process = new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin/java").toString(),
                    "-cp", String.join(java.io.File.pathSeparator, entries), Worker.class.getName(), mode, root.toString(), id, signals.toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        }
        void await(String marker) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(signals.resolve(marker)) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(Files.exists(signals.resolve(marker))).withFailMessage("Missing %s: %s", marker, output()).isTrue();
        }
        void release() throws IOException { process.getOutputStream().write(1); process.getOutputStream().flush(); }
        void succeeded() throws Exception {
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).withFailMessage("Worker did not finish: %s", output()).isTrue();
            assertThat(process.exitValue()).withFailMessage("Worker failed: %s", output()).isZero();
            assertThat(Files.exists(signals.resolve("done"))).withFailMessage("Worker did not report completion: %s", output()).isTrue();
        }
        String output() throws IOException { return new String(Files.readAllBytes(log), StandardCharsets.UTF_8); }
        @Override public void close() throws Exception {
            if (!process.isAlive()) return;
            process.destroy();
            if (!process.waitFor(3, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS); }
        }
    }
    public static final class Worker {
        public static void main(String[] args) throws Exception {
            String mode = args[0], id = args[2]; Path signals = Paths.get(args[3]);
            ChunkedSnapshotStore store = new ChunkedSnapshotStore(Paths.get(args[1]));
            Files.createFile(signals.resolve("started"));
            if ("download".equals(mode)) {
                ByteArrayOutputStream archive = new ByteArrayOutputStream();
                store.download(id, new OutputStream() {
                    boolean pinned;
                    private void pin() throws IOException {
                        if (pinned) return; pinned = true; Files.createFile(signals.resolve("pinned"));
                        if (System.in.read() != 1) throw new IOException("Download fixture was not released");
                    }
                    @Override public void write(int value) throws IOException { pin(); archive.write(value); }
                    @Override public void write(byte[] bytes, int offset, int length) throws IOException { pin(); archive.write(bytes, offset, length); }
                });
                try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive.toByteArray()))) {
                    if (!"manifest.json".equals(zip.getNextEntry().getName())) throw new AssertionError("Missing archived manifest");
                    JsonNode manifest = Json.parse(DebugClient.readBounded(zip, ChunkedSnapshotStore.MAX_MANIFEST,
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(2)));
                    if (!id.equals(manifest.path("id").asText()) || !"Concurrency fixture".equals(manifest.path("name").asText()))
                        throw new AssertionError("Pinned manifest changed");
                    int chunks = 0; while (zip.getNextEntry() != null) { chunks++; DebugClient.readBounded(zip,
                            SegmentDetails.MAX_PAGE_BYTES + 8192, System.nanoTime() + TimeUnit.SECONDS.toNanos(2)); }
                    if (chunks != manifest.path("chunks").size()) throw new AssertionError("Archive chunks changed");
                }
            } else if ("delete".equals(mode)) store.delete(id);
            else if ("rename".equals(mode)) store.rename(id, "Renamed in another JVM");
            else if ("missing".equals(mode)) {
                try { store.projection(id); throw new AssertionError("Deleted snapshot remained readable"); }
                catch (ToolException expected) { if (expected.status != 404) throw expected; }
            } else throw new AssertionError("Unknown fixture mode");
            Files.createFile(signals.resolve("done"));
        }
    }
}
