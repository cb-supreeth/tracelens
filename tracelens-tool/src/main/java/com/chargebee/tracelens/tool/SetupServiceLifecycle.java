package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarFile;

/** Separate review/confirmation boundary for local, explicitly registered runtimes. */
final class SetupServiceLifecycle {
    static final Map<String, String> FOLDERS = SetupServiceCatalog.folders();
    private final SetupInstaller installer;
    private final Path sourceJar;
    private final Controller controller;
    private final Map<String, String> reviewedPlans = new LinkedHashMap<String, String>();

    interface Controller {
        ObjectNode status(Path directory) throws IOException;
        void start(Path directory, Path agentJar) throws IOException;
        void restart(Path directory, String profileHash) throws IOException;
    }

    SetupServiceLifecycle(SetupInstaller installer, Path sourceJar) {
        this(installer, sourceJar, new LocalController());
    }
    SetupServiceLifecycle(SetupInstaller installer, Path sourceJar, Controller controller) {
        this.installer = installer; this.sourceJar = sourceJar; this.controller = controller;
    }

    ObjectNode plan(JsonNode input) {
        Review review = review(input);
        ObjectNode result = Json.obj("services", review.rows,
                "warning", "Selected services will be temporarily unavailable. Restart discards in-memory traces; saved captures remain. No application is rebuilt.");
        String id = UUID.randomUUID().toString();
        if (reviewedPlans.size() >= 32) reviewedPlans.remove(reviewedPlans.keySet().iterator().next());
        reviewedPlans.put(id, fingerprint(input, review));
        result.put("planId", id);
        return result;
    }

    ObjectNode apply(JsonNode input) {
        if (!input.path("confirmed").isBoolean() || !input.path("confirmed").asBoolean())
            throw new IllegalArgumentException("Confirm the service restart warning first");
        ObjectNode request = Json.copy(input); request.remove(Arrays.asList("confirmed", "planId"));
        String expected = reviewedPlans.remove(input.path("planId").asText());
        Review review = review(request);
        if (expected == null || !expected.equals(fingerprint(request, review)))
            throw new IllegalArgumentException("Service state or files changed; review restart again");
        ArrayNode outcomes = Json.arr();
        for (JsonNode row : review.rows) {
            ObjectNode outcome = Json.copy(row); outcomes.add(outcome);
            if (!"ready".equals(row.path("status").asText())) continue;
            String key = row.path("key").asText();
            Path directory = review.install.resolve("managed-services/" + key);
            try {
                ObjectNode profile = (ObjectNode) review.profiles.path(key);
                privateDirectory(directory);
                Path agent = directory.resolve("tracelens-" + profile.path("agentHash").asText() + ".jar");
                if (!Files.exists(agent)) writePrivate(agent, Files.readAllBytes(sourceJar));
                if (!hashFile(agent).equals(profile.path("agentHash").asText())) throw new IOException("Agent changed");
                Path saved = directory.resolve("launch.json");
                if (Files.exists(saved)) writePrivate(directory.resolve("launch-before-" + UUID.randomUUID() + ".json"), read(saved));
                replacePrivate(saved, Json.stringify(profile).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if ("restart".equals(row.path("action").asText())) controller.restart(directory, digest(profile));
                else controller.start(directory, agent);
                outcome.put("status", "starting").put("message", "Start requested. Checking the managed process and TraceLens connection; application readiness must be verified separately.");
            } catch (Exception failure) {
                outcome.put("status", "failed").put("message", "Could not start this service. Configuration remains saved; inspect its private managed-services log. Other selected services continue.");
            }
        }
        return Json.obj("services", outcomes);
    }

    private static String fingerprint(JsonNode input, Review review) {
        return digest(Json.obj("input", input, "profiles", review.profiles, "files", review.fingerprints, "services", review.rows));
    }

    ObjectNode status(JsonNode input) {
        keys(input, "installDir", "services");
        Path install = install(input);
        ObjectNode loaded;
        try { loaded = installer.load(Json.obj("installDir", install.toString())); }
        catch (IllegalArgumentException unavailable) { loaded = Json.obj(); }
        ArrayNode rows = Json.arr();
        for (String key : selected(input)) {
            ObjectNode row = Json.obj("key", key); rows.add(row);
            try {
                ObjectNode state = controller.status(install.resolve("managed-services/" + key));
                if (state == null) row.put("status", "manual").put("message", "No active TraceLens-managed runtime. Existing IDE/custom processes are not controlled.");
                else {
                    row.setAll(state); row.remove(Arrays.asList("port", "token"));
                    if ("running".equals(row.path("status").asText()) && !row.path("configHash").asText().isEmpty()) {
                        Path config = install.resolve(FOLDERS.get(key) + "/tracelens.json");
                        boolean pending = !hashFile(config).equals(row.path("configHash").asText());
                        row.put("restartRequired", pending);
                        if (pending) row.put("message", "Managed process is running with earlier configuration. Saved changes require a confirmed restart.");
                    }
                }
            } catch (IOException failure) {
                row.put("status", "failed").put("message", "Cannot verify the local supervisor. No unrelated process was stopped.");
            }
            connectionStatus(row, install, key, loaded.path(key));
        }
        return Json.obj("services", rows);
    }

    private static void connectionStatus(ObjectNode row, Path install, String key, JsonNode service) {
        row.put("connectionStatus", "unknown").put("connectionMessage", "Agent connection cannot be checked until this service has a valid saved configuration.");
        if (!service.path("configured").asBoolean() || service.hasNonNull("loadError")) return;
        JsonNode port = service.path("debugPort"), applicationId;
        if (!port.isIntegralNumber() || port.asLong() < 1 || port.asLong() > 65535) return;
        try { applicationId = Json.parse(read(install.resolve(FOLDERS.get(key) + "/tracelens.json"))).path("applicationId"); }
        catch (IOException | IllegalArgumentException invalid) { return; }
        if (!applicationId.isTextual() || applicationId.asText().trim().isEmpty()) return;
        row.put("connectionStatus", "unavailable").put("connectionMessage", "No usable TraceLens agent summary was returned from the configured local port. Application readiness was not checked.");
        HttpURLConnection connection = null;
        try {
            // Neither the request nor the registry can select a remote host, proxy or redirect target.
            connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port.asInt() + "/tracelens/summary")
                    .openConnection(java.net.Proxy.NO_PROXY);
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(300); connection.setReadTimeout(300);
            connection.setRequestProperty("Accept", "application/json");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(600);
            if (connection.getResponseCode() != 200 || connection.getContentLengthLong() > 65536) return;
            JsonNode summary;
            try (java.io.InputStream input = connection.getInputStream()) { summary = Json.parse(DebugClient.readBounded(input, 65536, deadline)); }
            JsonNode observed = summary.path("applicationId");
            if (!summary.isObject() || !observed.isTextual() || observed.asText().trim().isEmpty()) return;
            if (!applicationId.asText().equals(observed.asText())) {
                row.put("connectionStatus", "mismatch").put("connectionMessage", "The local agent reports a different application identity. Check this service's configured agent port.");
            } else {
                row.put("connectionStatus", "connected").put("connectionMessage", "TraceLens responded with the expected application identity. This does not establish process ownership or application readiness.");
            }
        } catch (IOException | IllegalArgumentException | ToolException unavailable) {
            // Keep connection failure independent of supervisor ownership and restart status.
        } finally { if (connection != null) connection.disconnect(); }
    }

    private Review review(JsonNode input) {
        keys(input, "installDir", "services", "launchers");
        Path install = install(input);
        ObjectNode loaded = installer.load(Json.obj("installDir", install.toString()));
        Review review = new Review(install);
        JsonNode launchers = input.path("launchers");
        if (!launchers.isMissingNode() && !launchers.isObject()) throw new IllegalArgumentException("launchers must be an object");
        if (launchers.isObject()) keys(launchers, FOLDERS.keySet().toArray(new String[0]));
        for (String key : selected(input)) {
            ObjectNode row = Json.obj("key", key); review.rows.add(row);
            try {
                JsonNode service = loaded.path(key);
                if (!service.path("configured").asBoolean()) throw new IllegalArgumentException("Save this service's configuration first");
                Path directory = install.resolve("managed-services/" + key);
                Path saved = directory.resolve("launch.json");
                ObjectNode owned = controller.status(directory);
                JsonNode supplied = launchers.path(key);
                JsonNode previous = Files.exists(saved, LinkOption.NOFOLLOW_LINKS) ? Json.parse(read(saved)) : Json.obj();
                JsonNode runtime = supplied.isObject() ? supplied : previous.path("runtime");
                if (!runtime.isObject()) {
                    row.put("status", "manual").put("reason", "Register a Tomcat or executable-JAR runtime below. Existing IDE/custom launches cannot be safely recreated."); continue;
                }
                ObjectNode profile = profile(runtime, service, key, install);
                review.profiles.set(key, profile);
                ObjectNode fingerprint = Json.obj("config", hashFile(Paths.get(profile.path("configPath").asText())),
                        "previous", digest(previous), "owner", owned == null ? "" : owned.path("ownerId").asText(),
                        "application", profile.path("applicationHash"));
                review.fingerprints.set(key, fingerprint);
                if (owned != null && Arrays.asList("starting", "restarting").contains(owned.path("status").asText()))
                    row.put("status", "manual").put("reason", "A managed operation is already in progress. Wait for its result before reviewing another restart.");
                else if (owned != null) row.put("status", "ready").put("action", "restart").put("reason", "Restart only the process owned by this service's private supervisor.");
                else if (Files.exists(directory.resolve("control.json"), LinkOption.NOFOLLOW_LINKS))
                    row.put("status", "manual").put("reason", "A previous supervisor cannot be verified. Inspect its private log and stop the old runtime manually before removing stale management state.");
                else if (Files.exists(directory.resolve("pending.json"), LinkOption.NOFOLLOW_LINKS))
                    row.put("status", "manual").put("reason", "A supervisor start could not be verified. Inspect supervisor.log before trying another start.");
                else if (portOpen(profile.path("debugPort").asInt()) || portOpen(profile.path("runtime").path("applicationPort").asInt()))
                    row.put("status", "manual").put("reason", "A runtime already uses the application or agent port but is not managed here. Stop it using its normal launcher, then review Start again.");
                else row.put("status", "ready").put("action", "start").put("reason", "Start using the reviewed runtime and the setup terminal's environment. Stop any existing untraced instance first.");
            } catch (Exception failure) {
                row.put("status", "failed").put("reason", failure instanceof IllegalArgumentException ? failure.getMessage() : "Cannot validate this service's private runtime files. No process will be stopped.");
            }
        }
        return review;
    }

    private ObjectNode profile(JsonNode runtime, JsonNode service, String key, Path install) throws IOException {
        keys(runtime, "type", "javaHome", "applicationPath", "workingDirectory", "applicationPort", "arguments");
        String type = runtime.path("type").asText();
        if (!Arrays.asList("tomcat", "jar").contains(type)) throw new IllegalArgumentException("Choose Tomcat or executable JAR");
        Path javaHome = path(runtime.path("javaHome").asText()), application = path(runtime.path("applicationPath").asText());
        Path cwd = path(runtime.path("workingDirectory").asText());
        JsonNode appPort = runtime.path("applicationPort");
        if (!appPort.isIntegralNumber() || appPort.asLong() < 1 || appPort.asLong() > 65535 || appPort.asInt() == service.path("debugPort").asInt())
            throw new IllegalArgumentException("Enter the application's HTTP port (different from its TraceLens agent port)");
        Path java = javaHome.resolve("bin/java");
        if (!Files.isExecutable(java) || !Files.isDirectory(cwd)) throw new IllegalArgumentException("Select an installed Java home and existing working directory");
        Path executable = "tomcat".equals(type) ? application.resolve("bin/catalina.sh") : application;
        if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Application launcher does not exist");
        if ("tomcat".equals(type) && !Files.isExecutable(executable)) throw new IllegalArgumentException("Tomcat catalina.sh must be executable");
        if ("jar".equals(type)) try (JarFile jar = new JarFile(application.toFile())) {
            if (jar.getManifest() == null || jar.getManifest().getMainAttributes().getValue("Main-Class") == null)
                throw new IllegalArgumentException("Select an executable application JAR");
        }
        JsonNode args = runtime.path("arguments");
        if (!args.isMissingNode() && (!args.isArray() || args.size() > 64)) throw new IllegalArgumentException("Use at most 64 application arguments");
        for (JsonNode arg : args) if (!arg.isTextual() || arg.asText().length() > 4096 || arg.asText().matches("(?s).*[\\x00-\\x1f\\x7f].*"))
            throw new IllegalArgumentException("Arguments must be single-line strings");
        if ("tomcat".equals(type) && args.size() != 0) throw new IllegalArgumentException("Tomcat uses catalina.sh run; extra lifecycle commands are not accepted");
        ObjectNode normalized = Json.copy(runtime); if (args.isMissingNode()) normalized.set("arguments", Json.arr());
        Path config = install.resolve(FOLDERS.get(key) + "/tracelens.json");
        JsonNode configJson = Json.parse(read(config));
        Path setenv = application.resolve("bin/setenv.sh");
        return Json.obj("runtime", normalized, "key", key, "applicationId", configJson.path("applicationId"),
                "configPath", config.toString(), "debugPort", service.path("debugPort"),
                "setenvHash", "tomcat".equals(type) && Files.exists(setenv) ? hashFile(setenv) : "",
                "configHash", hashFile(config), "agentHash", hashFile(sourceJar), "applicationHash", hashFile(executable), "javaHash", hashFile(java));
    }

    private static Path install(JsonNode input) {
        Path path = path(input.path("installDir").asText());
        if (!Files.isRegularFile(path.resolve("setup-state.json"), LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Save a recognized guided installation before managing services");
        return path;
    }
    static Set<String> selected(JsonNode input) {
        JsonNode list = input.path("services");
        if (!list.isArray() || list.size() == 0 || list.size() > SetupServiceCatalog.services().size()) throw new IllegalArgumentException("Select one to " + SetupServiceCatalog.services().size() + " supported services");
        Set<String> selected = new java.util.LinkedHashSet<String>();
        for (JsonNode value : list) if (!value.isTextual() || !FOLDERS.containsKey(value.asText()) || !selected.add(value.asText()))
            throw new IllegalArgumentException("Service selection is invalid or duplicated");
        return selected;
    }
    static Path path(String value) {
        if (value == null || value.isEmpty() || value.indexOf('\0') >= 0) throw new IllegalArgumentException("Use an absolute local path");
        Path path = Paths.get(value);
        if (!path.isAbsolute() || !path.normalize().equals(path)) throw new IllegalArgumentException("Use an absolute normalized local path");
        for (Path part = path; part != null; part = part.getParent()) if (Files.isSymbolicLink(part)) throw new IllegalArgumentException("Use real paths, not symlinks");
        return path;
    }
    static void keys(JsonNode object, String... allowed) {
        if (!object.isObject()) throw new IllegalArgumentException("Expected a JSON object");
        Set<String> fields = new HashSet<String>(Arrays.asList(allowed));
        object.fieldNames().forEachRemaining(key -> { if (!fields.contains(key)) throw new IllegalArgumentException("Unsupported field: " + key); });
    }
    static byte[] read(Path file) throws IOException {
        path(file.toString());
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 1024 * 1024) throw new IOException("Invalid management file");
        return Files.readAllBytes(file);
    }
    static String digest(JsonNode value) { return Json.sha256(Json.canonical(value)); }
    static String hashFile(Path file) throws IOException {
        path(file.toString());
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing runtime file");
        try (java.io.InputStream input = Files.newInputStream(file)) {
            java.security.MessageDigest hash = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count);
            StringBuilder text = new StringBuilder(); for (byte b : hash.digest()) text.append(String.format("%02x", b & 255)); return text.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static void privateDirectory(Path directory) throws IOException {
        path(directory.toString()); Files.createDirectories(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }
    static void writePrivate(Path file, byte[] bytes) throws IOException {
        path(file.toString());
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(file, bytes, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }
    static void replacePrivate(Path file, byte[] bytes) throws IOException {
        path(file.toString()); Path staged = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID());
        writePrivate(staged, bytes);
        Files.move(staged, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    static boolean portOpen(int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid agent port");
        try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", port), 150); return true; }
        catch (IOException unavailable) { return false; }
    }
    static JsonNode control(Path directory, String action, JsonNode body) throws IOException {
        JsonNode owner = Json.parse(read(directory.resolve("control.json")));
        int port = owner.path("port").asInt(); String token = owner.path("token").asText();
        if (port < 1 || port > 65535 || !token.matches("[A-Za-z0-9_-]{43}")) throw new IOException("Invalid supervisor identity");
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/" + action).openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(500); connection.setReadTimeout(2000);
        connection.setRequestProperty("X-TraceLens-Owner", token);
        try {
            if (body != null) {
                connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(Json.stringify(body).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            }
            if (connection.getResponseCode() != 200) throw new IOException("Supervisor rejected control request");
            JsonNode result;
            try (java.io.InputStream in = connection.getInputStream()) { result = Json.parse(bounded(in)); }
            if (!owner.path("ownerId").asText().equals(result.path("ownerId").asText())) throw new IOException("Supervisor identity changed");
            return result;
        } finally { connection.disconnect(); }
    }
    static byte[] bounded(java.io.InputStream input) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
        while ((n = input.read(buffer)) != -1) { if (bytes.size() + n > 65536) throw new IOException("Control response too large"); bytes.write(buffer, 0, n); }
        return bytes.toByteArray();
    }
    private static final class Review {
        final Path install; final ArrayNode rows = Json.arr(); final ObjectNode profiles = Json.obj(), fingerprints = Json.obj();
        Review(Path install) { this.install = install; }
    }
    private static final class LocalController implements Controller {
        public ObjectNode status(Path directory) throws IOException {
            if (!Files.exists(directory.resolve("control.json"), LinkOption.NOFOLLOW_LINKS)) {
                Path pending = directory.resolve("pending.json");
                if (Files.exists(pending)) {
                    JsonNode launch = Json.parse(read(pending));
                    if (System.currentTimeMillis() - launch.path("started").asLong() < 15000)
                        return Json.obj("ownerId", launch.path("id").asText(), "status", "starting", "message", "Waiting for the private runtime supervisor");
                }
                return null;
            }
            try { return (ObjectNode) control(directory, "status", null); } catch (IOException unavailable) { return null; }
        }
        public void start(Path directory, Path agentJar) throws IOException {
            validateInheritedOptions(System.getenv());
            Path log = directory.resolve("supervisor.log");
            if (!Files.exists(log)) writePrivate(log, new byte[0]); else read(log);
            ProcessBuilder builder = new ProcessBuilder("/usr/bin/nohup", Paths.get(System.getProperty("java.home"), "bin/java").toString(),
                    "-cp", agentJar.toString(), SetupManagedProcess.class.getName(), directory.toString());
            builder.redirectInput(new java.io.File("/dev/null")); builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
            writePrivate(directory.resolve("pending.json"), Json.stringify(Json.obj("id", UUID.randomUUID().toString(), "started", System.currentTimeMillis())).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            builder.start();
        }
        public void restart(Path directory, String hash) throws IOException { control(directory, "restart", Json.obj("profileHash", hash)); }
    }

    static void validateInheritedOptions(Map<String, String> environment) throws IOException {
        for (String key : Arrays.asList("CATALINA_OPTS", "JAVA_OPTS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")) {
            String value = environment.get(key);
            if (value != null && (value.contains("-Dtracelens.") || value.matches("(?s).*-javaagent:[^ ]*tracelens.*")))
                throw new IOException("Duplicate TraceLens options in inherited environment; remove the old hook before using a managed runtime");
        }
    }
}
