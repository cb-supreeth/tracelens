package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SetupManagedProcessTest {
    @TempDir Path temp;
    SetupManagedProcess supervisor;
    Process externalSupervisor;
    Path root, directory, gate, launches, arguments;
    ObjectNode profile;
    int debugPort, applicationPort;

    @AfterEach void cleanup() throws Exception {
        if (supervisor != null) supervisor.close();
        if (externalSupervisor != null) {
            externalSupervisor.destroy();
            assertThat(externalSupervisor.waitFor(10, TimeUnit.SECONDS)).isTrue();
        }
        if (debugPort > 0) awaitPortClosed(debugPort);
        if (applicationPort > 0) awaitPortClosed(applicationPort);
    }

    @Test void startsAndRestartsOnlyItsChildAndPollsThePrefixedAgentRoute() throws Exception {
        fixture(currentJava());
        start();
        JsonNode first = awaitStatus("running");
        JsonNode firstAgent = summary();
        assertThat(first.path("profileHash").asText()).isEqualTo(SetupServiceLifecycle.digest(profile));
        assertThat(first.path("configHash").asText()).isEqualTo(profile.path("configHash").asText());
        assertThat(request("/summary", null, null, null, debugPort)).isEqualTo(404);
        restart(SetupServiceLifecycle.digest(profile));
        JsonNode second = awaitStatus("running");
        assertThat(second.path("ownerId")).isEqualTo(first.path("ownerId"));
        assertThat(summary().path("instance")).isNotEqualTo(firstAgent.path("instance"));
        assertThat(Files.readAllLines(launches)).filteredOn(line -> line.startsWith("start:")).hasSize(2);
        assertThat(Files.readAllLines(launches)).filteredOn(line -> line.startsWith("stop:")).hasSize(1);
    }

    @Test void occupiedApplicationPortIsNotStoppedOrAdopted() throws Exception {
        fixture(currentJava());
        HttpServer unmanaged = HttpServer.create(new InetSocketAddress("127.0.0.1", applicationPort), 0);
        unmanaged.createContext("/", exchange -> {
            byte[] body = "unmanaged".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        unmanaged.start();
        try {
            start();
            assertThat(awaitStatus("failed").path("message").asText()).contains("occupied");
            assertThat(request("/", null, null, null, applicationPort)).isEqualTo(200);
            assertThat(Files.exists(launches)).isFalse();
        } finally { unmanaged.stop(0); }
    }

    @Test void changedConfigIsRejectedBeforeStoppingTheHealthyChild() throws Exception {
        fixture(currentJava()); start(); awaitStatus("running");
        String instance = summary().path("instance").asText();
        Files.write(Paths.get(profile.path("configPath").asText()), "{\"changed\":true}".getBytes(StandardCharsets.UTF_8));
        restart(SetupServiceLifecycle.digest(profile));
        awaitStatus("failed");
        assertThat(summary().path("instance").asText()).isEqualTo(instance);
        assertThat(Files.readAllLines(launches)).noneMatch(line -> line.startsWith("stop:"));
    }

    @Test void staleProfileHashCannotStopTheHealthyChild() throws Exception {
        fixture(currentJava()); start(); awaitStatus("running");
        String instance = summary().path("instance").asText();
        restart("not-the-reviewed-profile");
        awaitStatus("failed");
        assertThat(summary().path("instance").asText()).isEqualTo(instance);
        assertThat(Files.readAllLines(launches)).noneMatch(line -> line.startsWith("stop:"));
    }

    @Test void privateControlRequiresTokenAndRejectsBrowserOrigins() throws Exception {
        fixture(currentJava()); start(); awaitStatus("running");
        JsonNode control = Json.parse(SetupServiceLifecycle.read(directory.resolve("control.json")));
        int port = control.path("port").asInt();
        assertThat(request("/status", null, null, null, port)).isEqualTo(403);
        assertThat(request("/restart", "wrong", null, "{}", port)).isEqualTo(403);
        assertThat(request("/restart", control.path("token").asText(), "https://example.invalid", "{}", port)).isEqualTo(403);
        assertThat(request("/status?anything=1", control.path("token").asText(), null, null, port)).isEqualTo(400);
        assertThat(Files.readAllLines(launches)).hasSize(1);
        assertThat(Files.getPosixFilePermissions(directory.resolve("control.json")))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test void rejectsConcurrentRestartWhileStartingWithoutLaunchingASecondChild() throws Exception {
        fixture(currentJava()); Files.delete(gate); start(); awaitFile(launches);
        JsonNode control = Json.parse(SetupServiceLifecycle.read(directory.resolve("control.json")));
        assertThat(request("/restart", control.path("token").asText(), null,
                Json.stringify(Json.obj("profileHash", SetupServiceLifecycle.digest(profile))), control.path("port").asInt())).isEqualTo(409);
        Files.write(gate, new byte[0]);
        awaitStatus("running");
        assertThat(Files.readAllLines(launches)).hasSize(1);
    }

    @Test void closeDuringStartupStopsTheOwnedProcessAndReleasesTheLock() throws Exception {
        fixture(currentJava()); Files.delete(gate); start(); awaitFile(launches);
        supervisor.close(); supervisor = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && Files.readAllLines(launches).size() < 2) Thread.sleep(20);
        assertThat(Files.readAllLines(launches)).filteredOn(line -> line.startsWith("stop:")).hasSize(1);
        try (SetupManagedProcess replacement = new SetupManagedProcess(directory)) {
            assertThat(replacement).isNotNull();
        }
    }

    @Test void upgradedAgentJarIsUsedOnRestartWithoutReplacingSupervisorOwnership() throws Exception {
        fixture(currentJava()); start(); JsonNode first = awaitStatus("running");
        String oldAgent = profile.path("agentHash").asText();
        Path stagedAgent = root.resolve("new-agent.jar"); Files.write(stagedAgent, "new-agent".getBytes(StandardCharsets.UTF_8));
        String nextAgent = SetupServiceLifecycle.hashFile(stagedAgent);
        Files.move(stagedAgent, directory.resolve("tracelens-" + nextAgent + ".jar"));
        profile.put("agentHash", nextAgent); saveProfile();
        restart(SetupServiceLifecycle.digest(profile));
        JsonNode second = awaitStatus("running");
        assertThat(second.path("ownerId")).isEqualTo(first.path("ownerId"));
        assertThat(Files.readAllLines(arguments)).contains("-javaagent:" + directory.resolve("tracelens-" + nextAgent + ".jar") + "=standalone")
                .doesNotContain("-javaagent:" + directory.resolve("tracelens-" + oldAgent + ".jar") + "=standalone");
        assertThat(Files.exists(directory.resolve("tracelens-" + oldAgent + ".jar"))).isTrue();
    }

    @Test void supervisorAndDisposableFixtureCanRunOnJava8() throws Exception {
        Path java8 = Paths.get(System.getProperty("tracelens.test.java8Home",
                "/Library/Java/JavaVirtualMachines/amazon-corretto-8.jdk/Contents/Home"), "bin/java");
        assumeTrue(Files.isExecutable(java8), "Set tracelens.test.java8Home to enable the real Java 8 smoke");
        fixture(java8.toRealPath());
        Set<String> entries = new LinkedHashSet<String>();
        for (Class<?> type : Arrays.asList(SetupManagedProcess.class, JsonNode.class,
                com.fasterxml.jackson.core.JsonFactory.class, com.fasterxml.jackson.annotation.JsonProperty.class))
            entries.add(Paths.get(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        externalSupervisor = new ProcessBuilder(java8.toString(), "-cp", String.join(java.io.File.pathSeparator, entries),
                SetupManagedProcess.class.getName(), directory.toString())
                .redirectErrorStream(true).redirectOutput(root.resolve("java8-supervisor.log").toFile()).start();
        awaitFile(directory.resolve("control.json"));
        awaitStatus("running");
        assertThat(summary().path("javaVersion").asText()).startsWith("1.8.");
        restart(SetupServiceLifecycle.digest(profile));
        awaitStatus("running");
        assertThat(Files.readAllLines(launches)).filteredOn(line -> line.startsWith("start:")).hasSize(2);
    }

    private void start() throws IOException { supervisor = new SetupManagedProcess(directory); supervisor.start(); }
    private void restart(String hash) throws IOException { SetupServiceLifecycle.control(directory, "restart", Json.obj("profileHash", hash)); }
    private void saveProfile() throws IOException {
        SetupServiceLifecycle.replacePrivate(directory.resolve("launch.json"), Json.stringify(profile).getBytes(StandardCharsets.UTF_8));
    }
    private JsonNode awaitStatus(String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        JsonNode state = Json.obj();
        while (System.nanoTime() < deadline) {
            state = SetupServiceLifecycle.control(directory, "status", null);
            if (expected.equals(state.path("status").asText())) return state;
            if ("failed".equals(state.path("status").asText()) && !"failed".equals(expected)) break;
            Thread.sleep(30);
        }
        fail("Expected " + expected + ", observed " + state); return state;
    }
    private void awaitFile(Path file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(file) && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(Files.exists(file)).as(file.toString()).isTrue();
    }
    private static void awaitPortClosed(int port) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (SetupServiceLifecycle.portOpen(port) && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(SetupServiceLifecycle.portOpen(port)).as("disposable process port " + port).isFalse();
    }
    private JsonNode summary() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + debugPort + "/tracelens/summary").openConnection();
        connection.setConnectTimeout(1000); connection.setReadTimeout(1000);
        try (java.io.InputStream input = connection.getInputStream()) { return Json.parse(SetupServiceLifecycle.bounded(input)); }
        finally { connection.disconnect(); }
    }
    private static int request(String path, String token, String origin, String body, int port) throws Exception {
        java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://127.0.0.1:" + port + path)).timeout(java.time.Duration.ofSeconds(2));
        if (token != null) request.header("X-TraceLens-Owner", token);
        if (origin != null) request.header("Origin", origin);
        if (body != null) request.POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));
        return java.net.http.HttpClient.newHttpClient().send(request.build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
    }
    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); }
    }
    private static Path currentJava() throws IOException { return Paths.get(System.getProperty("java.home"), "bin/java").toRealPath(); }
    private static String quote(Path path) { return "'" + path.toString().replace("'", "'\"'\"'") + "'"; }

    private void fixture(Path executableJava) throws Exception {
        root = temp.toRealPath(); directory = root.resolve("managed"); SetupServiceLifecycle.privateDirectory(directory);
        debugPort = availablePort(); do { applicationPort = availablePort(); } while (applicationPort == debugPort);
        gate = root.resolve("start-gate"); Files.write(gate, new byte[0]);
        launches = root.resolve("launches.log"); arguments = root.resolve("arguments.log");
        Path fixtureSource = root.resolve("ManagedFixture.java");
        Files.write(fixtureSource, FIXTURE_SOURCE.getBytes(StandardCharsets.UTF_8));
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-d", root.toString(), fixtureSource.toString())).isZero();
        Path javaHome = Files.createDirectories(root.resolve("fixture-java/bin")).getParent();
        Path fakeJava = javaHome.resolve("bin/java");
        String script = "#!/bin/sh\nprintf '%s\\n' \"$@\" > " + quote(arguments) + "\nexec " + quote(executableJava)
                + " -cp " + quote(root) + " ManagedFixture " + debugPort + " " + applicationPort + " " + quote(launches) + " " + quote(gate) + "\n";
        Files.write(fakeJava, script.getBytes(StandardCharsets.UTF_8)); Files.setPosixFilePermissions(fakeJava, PosixFilePermissions.fromString("rwx------"));
        Path application = root.resolve("application.jar"), agent = root.resolve("agent.jar"), config = root.resolve("tracelens.json");
        Files.write(application, "fixture-application".getBytes(StandardCharsets.UTF_8));
        Files.write(agent, "fixture-agent".getBytes(StandardCharsets.UTF_8)); Files.write(config, "{}".getBytes(StandardCharsets.UTF_8));
        String agentHash = SetupServiceLifecycle.hashFile(agent);
        Files.move(agent, directory.resolve("tracelens-" + agentHash + ".jar"));
        profile = Json.obj("runtime", Json.obj("type", "jar", "javaHome", javaHome.toString(), "applicationPath", application.toString(),
                "workingDirectory", root.toString(), "applicationPort", applicationPort, "arguments", Json.arr()),
                "applicationId", "managed-fixture", "debugPort", debugPort, "configPath", config.toString(),
                "configHash", SetupServiceLifecycle.hashFile(config), "javaHash", SetupServiceLifecycle.hashFile(fakeJava),
                "applicationHash", SetupServiceLifecycle.hashFile(application), "agentHash", agentHash);
        saveProfile();
    }

    private static final String FIXTURE_SOURCE = """
            import java.nio.file.*;
            import java.nio.charset.StandardCharsets;
            import java.net.*;
            import java.util.*;
            import java.util.concurrent.*;
            import com.sun.net.httpserver.*;
            public class ManagedFixture {
                public static void main(String[] args) throws Exception {
                    final Path launches = Paths.get(args[2]);
                    final String instance = UUID.randomUUID().toString();
                    Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                        public void run() { try { append(launches, "stop:" + instance); } catch (Exception ignored) { } }
                    }));
                    append(launches, "start:" + instance);
                    while (!Files.exists(Paths.get(args[3]))) Thread.sleep(10);
                    final ServerSocket application = new ServerSocket(Integer.parseInt(args[1]), 1, InetAddress.getByName("127.0.0.1"));
                    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), 0);
                    server.createContext("/tracelens/summary", new HttpHandler() {
                        public void handle(HttpExchange exchange) throws java.io.IOException {
                            byte[] body = ("{\\"applicationId\\":\\"managed-fixture\\",\\"instance\\":\\"" + instance
                                    + "\\",\\"javaVersion\\":\\"" + System.getProperty("java.version") + "\\"}").getBytes(StandardCharsets.UTF_8);
                            exchange.sendResponseHeaders(200, body.length);
                            exchange.getResponseBody().write(body); exchange.close();
                        }
                    });
                    server.start(); new CountDownLatch(1).await();
                }
                static void append(Path target, String line) throws Exception {
                    Files.write(target, Arrays.asList(line), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
            }
            """;
}
