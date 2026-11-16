package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Keeps the actual Process reference: no port-to-PID lookup, process adoption, or force kill. */
public final class SetupManagedProcess implements AutoCloseable {
    private final Path directory;
    private final HttpServer server;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final String ownerId = UUID.randomUUID().toString(), token;
    private volatile Process child;
    private volatile JsonNode activeProfile;
    private volatile ObjectNode state;
    private volatile boolean busy, closed;
    private final CountDownLatch ended = new CountDownLatch(1);

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Internal managed runtime requires its private directory");
        final SetupManagedProcess supervisor = new SetupManagedProcess(SetupServiceLifecycle.path(args[0]));
        Runtime.getRuntime().addShutdownHook(new Thread(supervisor::close, "tracelens-managed-stop"));
        supervisor.start(); supervisor.ended.await();
    }

    SetupManagedProcess(Path directory) throws IOException {
        this.directory = directory;
        SetupServiceLifecycle.privateDirectory(directory);
        Path lockPath = directory.resolve("owner.lock");
        if (!Files.exists(lockPath)) SetupServiceLifecycle.writePrivate(lockPath, new byte[0]);
        SetupServiceLifecycle.path(lockPath.toString());
        lockChannel = FileChannel.open(lockPath, StandardOpenOption.WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        FileLock acquired;
        try { acquired = lockChannel.tryLock(); }
        catch (java.nio.channels.OverlappingFileLockException conflict) { lockChannel.close(); throw new IOException("Already managed"); }
        if (acquired == null) { lockChannel.close(); throw new IOException("Already managed"); }
        lock = acquired;
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        server.createContext("/", this::handle);
        state = Json.obj("ownerId", ownerId, "status", "starting", "message", "Starting managed runtime");
    }

    void start() throws IOException {
        SetupServiceLifecycle.replacePrivate(directory.resolve("control.json"), Json.stringify(Json.obj(
                "ownerId", ownerId, "port", server.getAddress().getPort(), "token", token)).getBytes(StandardCharsets.UTF_8));
        server.start(); schedule(false, SetupServiceLifecycle.digest(Json.parse(SetupServiceLifecycle.read(directory.resolve("launch.json")))));
    }

    private synchronized boolean schedule(boolean restart, String expectedHash) {
        if (busy || closed) return false;
        busy = true; update(restart ? "restarting" : "starting", restart ? "Stopping the owned process gracefully" : "Starting the reviewed runtime");
        worker.submit(() -> {
            try {
                ObjectNode profile = (ObjectNode) Json.parse(SetupServiceLifecycle.read(directory.resolve("launch.json")));
                if (!SetupServiceLifecycle.digest(profile).equals(expectedHash)) throw new IOException("Reviewed launch profile changed");
                // Validate all launch inputs before stopping the current healthy process.
                ProcessBuilder builder = builder(profile);
                if (restart && child != null && child.isAlive()) {
                    child.destroy();
                    if (!child.waitFor(20, TimeUnit.SECONDS)) {
                        update("failed", "Service did not stop within 20 seconds. It was not force-killed and no second process was started."); return;
                    }
                }
                if (closed) return;
                if (SetupServiceLifecycle.portOpen(profile.path("debugPort").asInt())
                        || SetupServiceLifecycle.portOpen(profile.path("runtime").path("applicationPort").asInt())) {
                    update("failed", "An application or agent port is occupied. No unrelated process was stopped or adopted."); return;
                }
                // Recheck reviewed inputs after graceful shutdown; it may have taken seconds.
                builder = builder(profile);
                synchronized (this) {
                    if (closed) return;
                    child = builder.start();
                    activeProfile = profile;
                }
                update("starting", "Process started; waiting for its TraceLens connection. This does not verify application readiness.");
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
                while (!closed && System.nanoTime() < deadline) {
                    if (!child.isAlive()) { update("failed", "Application process exited. Inspect application.log; configuration is still saved."); return; }
                    if (agentReady(profile)) {
                        ObjectNode next = state.deepCopy(); next.put("status", "running").put("message", "Managed process is alive and TraceLens is connected. Verify the application's normal health separately.");
                        next.put("profileHash", expectedHash).put("configHash", profile.path("configHash").asText());
                        state = next; return;
                    }
                    Thread.sleep(500);
                }
                if (!closed) update("failed", "Process is still running but TraceLens was not reachable after three minutes. Inspect application.log; no force stop was attempted.");
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                System.err.println("TraceLens managed runtime failed (" + failure.getClass().getSimpleName() + "). Check reviewed runtime files, inherited JVM options and application.log.");
                update("failed", "Runtime could not start or restart. Verify the registered Java/Tomcat/JAR paths and inherited environment; inspect application.log.");
            } finally { busy = false; }
        });
        return true;
    }

    private ProcessBuilder builder(JsonNode profile) throws IOException {
        JsonNode runtime = profile.path("runtime");
        Path java = SetupServiceLifecycle.path(runtime.path("javaHome").asText()).resolve("bin/java");
        Path application = SetupServiceLifecycle.path(runtime.path("applicationPath").asText());
        Path cwd = SetupServiceLifecycle.path(runtime.path("workingDirectory").asText());
        Path config = SetupServiceLifecycle.path(profile.path("configPath").asText());
        Path jar = directory.resolve("tracelens-" + profile.path("agentHash").asText() + ".jar");
        boolean tomcat = "tomcat".equals(runtime.path("type").asText());
        Path executable = tomcat ? application.resolve("bin/catalina.sh") : application;
        Path setenv = application.resolve("bin/setenv.sh");
        if (!SetupServiceLifecycle.hashFile(java).equals(profile.path("javaHash").asText())
                || !SetupServiceLifecycle.hashFile(executable).equals(profile.path("applicationHash").asText())
                || !SetupServiceLifecycle.hashFile(jar).equals(profile.path("agentHash").asText())) throw new IOException("Runtime changed");
        if (tomcat && !(Files.exists(setenv) ? SetupServiceLifecycle.hashFile(setenv) : "").equals(profile.path("setenvHash").asText()))
            throw new IOException("Tomcat startup settings changed after review");
        if (!SetupServiceLifecycle.hashFile(config).equals(profile.path("configHash").asText())) throw new IOException("Reviewed configuration changed");
        if (!jar.toString().matches("/[A-Za-z0-9_./-]+") || !config.toString().matches("/[A-Za-z0-9_./-]+")) throw new IOException("Unsafe JVM option path");
        List<String> agent = Arrays.asList("-javaagent:" + jar + "=standalone", "-Dtracelens.configPath=" + config,
                "-Dtracelens.port=" + profile.path("debugPort").asInt());
        List<String> command = new ArrayList<String>();
        if (tomcat) { command.add(executable.toString()); command.add("run"); }
        else {
            command.add(java.toString()); command.addAll(agent); command.add("-jar"); command.add(application.toString());
            for (JsonNode arg : runtime.path("arguments")) command.add(arg.asText());
        }
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile());
        Map<String, String> env = builder.environment();
        SetupServiceLifecycle.validateInheritedOptions(env);
        env.put("JAVA_HOME", java.getParent().getParent().toString()); env.put("JRE_HOME", java.getParent().getParent().toString());
        if (tomcat) {
            env.put("CATALINA_HOME", application.toString()); env.put("CATALINA_BASE", application.toString());
            // The reviewed agent is supplied here; the existing opt-in setenv hook must not attach another one.
            env.put("TRACELENS", "0"); env.put("CATALINA_OPTS", env.getOrDefault("CATALINA_OPTS", "") + " " + String.join(" ", agent));
        }
        Path log = directory.resolve("application.log");
        if (!Files.exists(log)) SetupServiceLifecycle.writePrivate(log, new byte[0]); else SetupServiceLifecycle.path(log.toString());
        builder.redirectInput(new java.io.File("/dev/null")); builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        return builder;
    }

    private static boolean agentReady(JsonNode profile) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("http://127.0.0.1:" + profile.path("debugPort").asInt() + "/tracelens/summary").openConnection();
            connection.setConnectTimeout(200); connection.setReadTimeout(300); connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() != 200) return false;
            try (java.io.InputStream in = connection.getInputStream()) {
                return profile.path("applicationId").asText().equals(Json.parse(SetupServiceLifecycle.bounded(in)).path("applicationId").asText());
            }
        } catch (IOException | IllegalArgumentException unavailable) { return false; }
        finally { if (connection != null) connection.disconnect(); }
    }

    private void update(String status, String message) { state = Json.obj("ownerId", ownerId, "status", status, "message", message); }
    private void handle(HttpExchange exchange) throws IOException {
        try {
            String supplied = exchange.getRequestHeaders().getFirst("X-TraceLens-Owner");
            if (exchange.getRequestHeaders().getFirst("Origin") != null || supplied == null
                    || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))
                    || !("127.0.0.1:" + server.getAddress().getPort()).equals(exchange.getRequestHeaders().getFirst("Host"))) {
                reply(exchange, 403, Json.obj("error", "Private local supervisor")); return;
            }
            if (exchange.getRequestURI().getRawQuery() != null) { reply(exchange, 400, Json.obj("error", "No query accepted")); return; }
            if ("/status".equals(exchange.getRequestURI().getRawPath()) && "GET".equals(exchange.getRequestMethod())) {
                ObjectNode result = state.deepCopy();
                if ("running".equals(result.path("status").asText()) && (child == null || !child.isAlive()))
                    result.put("status", "failed").put("message", "Managed process exited. Configuration remains saved; inspect application.log.");
                else if ("running".equals(result.path("status").asText()) && (activeProfile == null || !agentReady(activeProfile)))
                    result.put("status", "failed").put("message", "Managed process is alive, but its TraceLens connection is unavailable. No automatic restart was attempted.");
                reply(exchange, 200, result);
            } else if ("/restart".equals(exchange.getRequestURI().getRawPath()) && "POST".equals(exchange.getRequestMethod())) {
                JsonNode body = Json.parse(SetupServiceLifecycle.bounded(exchange.getRequestBody()));
                if (schedule(true, body.path("profileHash").asText())) reply(exchange, 200, state);
                else reply(exchange, 409, Json.obj("error", "An operation is already in progress"));
            } else reply(exchange, 404, Json.obj("error", "Not found"));
        } catch (IllegalArgumentException invalid) { reply(exchange, 400, Json.obj("error", "Invalid private control request")); }
        finally { exchange.close(); }
    }
    private static void reply(HttpExchange exchange, int code, JsonNode body) throws IOException {
        byte[] bytes = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, bytes.length); exchange.getResponseBody().write(bytes);
    }
    public synchronized void close() {
        closed = true; server.stop(0); worker.shutdownNow();
        Process process = child; if (process != null && process.isAlive()) process.destroy();
        try { lock.release(); lockChannel.close(); } catch (IOException ignored) { }
        ended.countDown();
    }
}
