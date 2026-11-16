package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class SetupServiceLifecycleTest {
    @TempDir Path temporary;
    Path root, install, jar, javaHome, application;
    SetupInstaller installer;
    FakeController controller;
    SetupServiceLifecycle lifecycle;

    @BeforeEach void setup() throws Exception {
        root = temporary.toRealPath(); install = root.resolve("install"); jar = root.resolve("tracelens.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", "com.chargebee.tracelens.tool.ToolMain");
        manifest.getMainAttributes().putValue("Premain-Class", "com.chargebee.tracelens.agent.TraceLensAgent");
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(jar), manifest)) { }
        javaHome = Files.createDirectories(root.resolve("jdk/bin")).getParent();
        Files.write(javaHome.resolve("bin/java"), "#!/bin/sh\nexit 99\n".getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(javaHome.resolve("bin/java"), PosixFilePermissions.fromString("rwx------"));
        application = Files.createDirectories(root.resolve("app/tomcat/bin")).getParent();
        Files.write(application.resolve("bin/catalina.sh"), "#!/bin/sh\nexit 99\n".getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(application.resolve("bin/catalina.sh"), PosixFilePermissions.fromString("rwx------"));
        Path ems = Files.createDirectory(root.resolve("ems"));
        installer = new SetupInstaller(jar);
        ObjectNode request = installer.defaults().put("installDir", install.toString());
        ((ObjectNode) request.path("cbApp")).put("sourceRoot", application.getParent().toString()).put("debugPort", freePort());
        ((ObjectNode) request.path("ems")).put("sourceRoot", ems.toString()).put("enabled", true).put("debugPort", freePort());
        installer.apply(request, installer.plan(request).path("planId").asText());
        controller = new FakeController(); lifecycle = new SetupServiceLifecycle(installer, jar, controller);
    }

    ObjectNode profile() throws IOException { return Json.obj("type", "tomcat", "javaHome", javaHome.toString(),
            "applicationPath", application.toString(), "applicationPort", freePort(), "workingDirectory", root.toString(), "arguments", Json.arr()); }
    ObjectNode request() throws IOException { return Json.obj("installDir", install.toString(), "services", Json.arr("cbApp"), "launchers", Json.obj("cbApp", profile())); }
    ObjectNode approved(ObjectNode request) { return request.deepCopy().put("planId", lifecycle.plan(request).path("planId").asText()).put("confirmed", true); }
    static int freePort() throws IOException { try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); } }

    @Test void reviewIsReadOnlyAndMissingConsentNeverStarts() throws Exception {
        ObjectNode request = request();
        JsonNode plan = lifecycle.plan(request);
        assertThat(plan.path("services").get(0).path("status").asText()).isEqualTo("ready");
        assertThat(Files.exists(install.resolve("managed-services"))).isFalse();
        assertThatThrownBy(() -> lifecycle.apply(request.put("planId", plan.path("planId").asText())))
                .hasMessageContaining("Confirm");
        assertThat(controller.starts).isZero();
    }

    @Test void confirmedStartPersistsPrivateProfileAndUsesCurrentBundle() throws Exception {
        ObjectNode result = lifecycle.apply(approved(request()));
        assertThat(result.path("services").get(0).path("status").asText()).isEqualTo("starting");
        Path directory = install.resolve("managed-services/cbApp");
        JsonNode profile = Json.parse(Files.readAllBytes(directory.resolve("launch.json")));
        assertThat(Files.getPosixFilePermissions(directory.resolve("launch.json"))).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.readAllBytes(directory.resolve("tracelens-" + profile.path("agentHash").asText() + ".jar"))).isEqualTo(Files.readAllBytes(jar));
        assertThat(controller.starts).isEqualTo(1);
        assertThat(profile.has("environment")).isFalse();
    }

    @Test void confirmationIsSingleUseAndNewDashboardCanRestartRegisteredProfile() throws Exception {
        ObjectNode approval = approved(request()); lifecycle.apply(approval);
        assertThatThrownBy(() -> lifecycle.apply(approval)).hasMessageContaining("review restart again");
        lifecycle = new SetupServiceLifecycle(installer, jar, controller);
        ObjectNode request = Json.obj("installDir", install.toString(), "services", Json.arr("cbApp"));
        JsonNode plan = lifecycle.plan(request);
        assertThat(plan.path("services").get(0).path("action").asText()).isEqualTo("restart");
        lifecycle.apply(request.put("planId", plan.path("planId").asText()).put("confirmed", true));
        assertThat(controller.restarts).isEqualTo(1);
    }

    @Test void changedConfigOrLauncherInvalidatesReviewedRestart() throws Exception {
        ObjectNode approval = approved(request());
        Path config = install.resolve("cb-app/tracelens.json");
        Files.write(config, (new String(Files.readAllBytes(config), StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> lifecycle.apply(approval)).hasMessageContaining("review restart again");
        assertThat(controller.starts).isZero();
        ObjectNode stale = approved(request());
        Files.write(application.resolve("bin/setenv.sh"), "# changed runtime\n".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> lifecycle.apply(stale)).hasMessageContaining("review restart again");
    }

    @Test void unmanagedOccupiedApplicationPortIsNeverAdopted() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            ObjectNode request = request(); ((ObjectNode) request.path("launchers").path("cbApp")).put("applicationPort", socket.getLocalPort());
            JsonNode row = lifecycle.plan(request).path("services").get(0);
            assertThat(row.path("status").asText()).isEqualTo("manual");
            assertThat(row.path("reason").asText()).contains("not managed");
            lifecycle.apply(approved(request));
            assertThat(controller.starts).isZero();
            assertThat(socket.isClosed()).isFalse();
        }
    }

    @Test void oneServiceFailureDoesNotPreventOtherStart() throws Exception {
        ObjectNode request = request(); request.set("services", Json.arr("cbApp", "ems"));
        ((ObjectNode) request.path("launchers")).set("ems", profile());
        controller.failApp = true;
        JsonNode rows = lifecycle.apply(approved(request)).path("services");
        assertThat(rows.get(0).path("status").asText()).isEqualTo("failed");
        assertThat(rows.get(1).path("status").asText()).isEqualTo("starting");
        assertThat(controller.starts).isEqualTo(2);
    }

    @Test void malformedRuntimeAndUnsupportedServicesNeverExecute() throws Exception {
        ObjectNode request = request(); ((ObjectNode) request.path("launchers").path("cbApp")).put("command", "echo unsafe");
        assertThat(lifecycle.plan(request).path("services").get(0).path("status").asText()).isEqualTo("failed");
        request.set("services", Json.arr("cb-ui-middleware"));
        assertThatThrownBy(() -> lifecycle.plan(request)).hasMessageContaining("invalid");
        assertThat(controller.starts).isZero();
    }

    @Test void staleSupervisorStateCannotBeReplacedWithAnotherProcess() throws Exception {
        Path directory = install.resolve("managed-services/cbApp"); Files.createDirectories(directory);
        Files.write(directory.resolve("control.json"), "{}".getBytes(StandardCharsets.UTF_8));
        assertThat(lifecycle.plan(request()).path("services").get(0).path("status").asText()).isEqualTo("manual");
        assertThat(controller.starts).isZero();
    }

    @Test void inheritedDuplicateAgentFailsBeforeSupervisorIsSpawned() {
        Map<String, String> env = new HashMap<String, String>(); env.put("JAVA_TOOL_OPTIONS", "-javaagent:/x/tracelens.jar=standalone");
        assertThatThrownBy(() -> SetupServiceLifecycle.validateInheritedOptions(env)).isInstanceOf(IOException.class).hasMessageContaining("Duplicate TraceLens");
    }

    @Test void unmanagedAgentConnectionIsSeparateFromOwnershipAndDoesNotWriteFiles() throws Exception {
        Path config = install.resolve("cb-app/tracelens.json");
        byte[] before = Files.readAllBytes(config);
        HttpServer agent = agent("cbApp", Json.stringify(Json.obj("applicationId", configuredApplicationId("cbApp"))));
        try {
            JsonNode row = status("cbApp").path("services").get(0);
            assertThat(row.path("status").asText()).isEqualTo("manual");
            assertThat(row.path("connectionStatus").asText()).isEqualTo("connected");
            assertThat(row.path("connectionMessage").asText()).contains("does not establish process ownership or application readiness");
            assertThat(row.has("restartRequired")).isFalse();
            assertThat(Files.readAllBytes(config)).isEqualTo(before);
            assertThat(Files.exists(install.resolve("managed-services"))).isFalse();
            assertThat(controller.starts + controller.restarts).isZero();
        } finally { agent.stop(0); }
    }

    @Test void connectionCheckPreservesManagedOwnershipAndPendingRestart() throws Exception {
        controller.states.put(install.resolve("managed-services/cbApp"), Json.obj("ownerId", "fixture-owner",
                "status", "running", "configHash", "older-config", "token", "do-not-expose", "port", 1234));
        HttpServer agent = agent("cbApp", Json.stringify(Json.obj("applicationId", configuredApplicationId("cbApp"))));
        try {
            JsonNode row = status("cbApp").path("services").get(0);
            assertThat(row.path("status").asText()).isEqualTo("running");
            assertThat(row.path("restartRequired").asBoolean()).isTrue();
            assertThat(row.path("message").asText()).contains("earlier configuration");
            assertThat(row.path("connectionStatus").asText()).isEqualTo("connected");
            assertThat(row.has("token") || row.has("port")).isFalse();
        } finally { agent.stop(0); }
    }

    @Test void mismatchedAgentIdentityDoesNotExposeItsResponse() throws Exception {
        HttpServer agent = agent("cbApp", "{\"applicationId\":\"different-private-id\",\"payload\":\"not-for-dashboard\"}");
        try {
            JsonNode row = status("cbApp").path("services").get(0);
            assertThat(row.path("connectionStatus").asText()).isEqualTo("mismatch");
            assertThat(row.toString()).doesNotContain("different-private-id", "not-for-dashboard");
            assertThat(row.path("status").asText()).isEqualTo("manual");
        } finally { agent.stop(0); }
    }

    @Test void unavailableServiceDoesNotHideAnotherConnectedService() throws Exception {
        HttpServer agent = agent("ems", Json.stringify(Json.obj("applicationId", configuredApplicationId("ems"))));
        try {
            JsonNode rows = status("cbApp", "ems").path("services");
            assertThat(rows.get(0).path("connectionStatus").asText()).isEqualTo("unavailable");
            assertThat(rows.get(1).path("connectionStatus").asText()).isEqualTo("connected");
        } finally { agent.stop(0); }
    }

    @Test void unreadableNonPrimaryConfigDoesNotPreventAnotherConnectionCheck() throws Exception {
        HttpServer agent = agent("cbApp", Json.stringify(Json.obj("applicationId", configuredApplicationId("cbApp"))));
        Path missing = install.resolve("ems/tracelens.json");
        Files.move(missing, root.resolve("ems-config-before.json"));
        Files.createDirectory(missing);
        try {
            JsonNode rows = status("ems", "cbApp").path("services");
            assertThat(rows.get(0).path("connectionStatus").asText()).isEqualTo("unknown");
            assertThat(rows.get(1).path("connectionStatus").asText()).isEqualTo("connected");
            assertThat(Files.isDirectory(missing)).isTrue();
        } finally { agent.stop(0); }
    }

    @Test void unconfiguredOrInvalidIdentityIsUnknownAndDoesNotProbe() throws Exception {
        assertThat(status("plans").path("services").get(0).path("connectionStatus").asText()).isEqualTo("unknown");
        Path config = install.resolve("ems/tracelens.json");
        ObjectNode saved = (ObjectNode) Json.parse(Files.readAllBytes(config)); saved.remove("applicationId");
        Files.write(config, Json.stringify(saved).getBytes(StandardCharsets.UTF_8));
        AtomicInteger requests = new AtomicInteger();
        HttpServer agent = HttpServer.create(new InetSocketAddress("127.0.0.1", configuredPort("ems")), 0);
        agent.createContext("/", exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close(); });
        agent.start();
        try {
            assertThat(status("ems").path("services").get(0).path("connectionStatus").asText()).isEqualTo("unknown");
            assertThat(requests.get()).isZero();
            ObjectNode input = Json.obj("installDir", install.toString(), "services", Json.arr("ems"), "url", "http://example.invalid/");
            assertThatThrownBy(() -> lifecycle.status(input)).hasMessageContaining("Unsupported field");
            assertThat(requests.get()).isZero();
        } finally { agent.stop(0); }
    }

    @Test void summaryRedirectsAreNeverFollowed() throws Exception {
        AtomicInteger redirected = new AtomicInteger();
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/", exchange -> { redirected.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        target.start();
        HttpServer agent = HttpServer.create(new InetSocketAddress("127.0.0.1", configuredPort("cbApp")), 0);
        agent.createContext("/tracelens/summary", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + target.getAddress().getPort() + "/unexpected");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        agent.start();
        try {
            assertThat(status("cbApp").path("services").get(0).path("connectionStatus").asText()).isEqualTo("unavailable");
            assertThat(redirected.get()).isZero();
        } finally { agent.stop(0); target.stop(0); }
    }

    @Test void oversizedMalformedAndMissingIdentityResponsesRemainBoundedAndUnavailable() throws Exception {
        AtomicReference<String> response = new AtomicReference<String>();
        HttpServer agent = HttpServer.create(new InetSocketAddress("127.0.0.1", configuredPort("cbApp")), 0);
        agent.createContext("/tracelens/summary", exchange -> {
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            try { exchange.sendResponseHeaders(200, bytes.length > 65536 ? 0 : bytes.length); exchange.getResponseBody().write(bytes); }
            catch (IOException closedByBudget) { }
            finally { exchange.close(); }
        });
        agent.start();
        try {
            for (String value : new String[]{"{invalid", "[]", "{}", "{\"applicationId\":42}",
                    Json.stringify(Json.obj("applicationId", configuredApplicationId("cbApp"), "large", new String(new char[65536]).replace('\0', 'x')))}) {
                response.set(value);
                JsonNode row = status("cbApp").path("services").get(0);
                assertThat(row.path("connectionStatus").asText()).isEqualTo("unavailable");
                assertThat(row.toString().length()).isLessThan(1024);
            }
        } finally { agent.stop(0); }
    }

    @Test void slowAgentResponseTimesOutWithoutChangingOwnership() throws Exception {
        HttpServer agent = HttpServer.create(new InetSocketAddress("127.0.0.1", configuredPort("cbApp")), 0);
        agent.createContext("/tracelens/summary", exchange -> {
            try { Thread.sleep(1000); exchange.sendResponseHeaders(200, -1); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (IOException timedOut) { }
            finally { exchange.close(); }
        });
        agent.start();
        try {
            long started = System.nanoTime();
            JsonNode row = status("cbApp").path("services").get(0);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(2500);
            assertThat(row.path("connectionStatus").asText()).isEqualTo("unavailable");
            assertThat(row.path("status").asText()).isEqualTo("manual");
        } finally { agent.stop(0); }
    }

    private JsonNode status(String... services) {
        return lifecycle.status(Json.obj("installDir", install.toString(), "services", Json.arr((Object[]) services)));
    }
    private int configuredPort(String key) {
        return installer.load(Json.obj("installDir", install.toString())).path(key).path("debugPort").asInt();
    }
    private String configuredApplicationId(String key) throws IOException {
        return Json.parse(Files.readAllBytes(install.resolve(SetupServiceLifecycle.FOLDERS.get(key) + "/tracelens.json"))).path("applicationId").asText();
    }
    private HttpServer agent(String key, String response) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", configuredPort(key)), 0);
        server.createContext("/tracelens/summary", exchange -> {
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start(); return server;
    }

    static class FakeController implements SetupServiceLifecycle.Controller {
        int starts, restarts; boolean failApp;
        final Map<Path, ObjectNode> states = new HashMap<Path, ObjectNode>();
        public ObjectNode status(Path directory) { return states.get(directory); }
        public void start(Path directory, Path jar) throws IOException {
            starts++; if (failApp && directory.endsWith("cbApp")) throw new IOException("simulated startup failure");
            states.put(directory, Json.obj("ownerId", "stable-owner-" + directory.getFileName(), "status", "running"));
        }
        public void restart(Path directory, String profileHash) { restarts++; }
    }
}
