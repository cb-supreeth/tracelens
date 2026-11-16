package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

/** Preview-first local installation. Never builds, starts, stops or contacts an application. */
public final class SetupInstaller {
    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;
    private static final int MAX_SETENV = 1024 * 1024, MAX_COMBINED_JAR = 128 * 1024 * 1024;
    // Application archives are referenced by the launcher, never copied or read into memory.
    private static final long MAX_EMS_LAUNCH_JAR = 1024L * 1024 * 1024;
    private static final String HEADER = "#!/bin/sh\nset -eu\n";
    private static final String STATE_FILE = "setup-state.json";
    private final Path sourceJar;

    public SetupInstaller(Path sourceJar) {
        if (sourceJar == null) throw new IllegalArgumentException("A combined TraceLens JAR is required");
        this.sourceJar = sourceJar.toAbsolutePath();
    }

    public ObjectNode defaults() {
        Path home = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
        ObjectNode result = Json.obj("installDir", home.resolve(".tracelens/setup").toString(), "viewerPort", 7781,
                "captureJdbcQueries", false, "captureJdbcParameters", false, "captureMode", "detailed");
        for (SetupServiceCatalog.Entry service : SetupServiceCatalog.services()) result.set(service.key, Json.obj("enabled", service.enabledByDefault,
                "sourceRoot", home.resolve("work/" + service.checkout).toString(),
                "debugPort", service.debugPort, "httpIncludePaths", template(service.name).path("httpIncludePaths"),
                "capture", SetupCaptureSettings.current(template(service.name)), "installed", false, "configured", false,
                "launcherAvailable", false, "launchMode", "manual"));
        result.put("configured", false);
        return result;
    }

    public ObjectNode plan(JsonNode request) { return prepare(request).document.deepCopy(); }

    public ObjectNode load(JsonNode input) {
        if (input == null || !input.isObject()) throw new IllegalArgumentException("Setup load request must be an object");
        keys(input, "installDir");
        ObjectNode result = defaults();
        Path install = safePath(text(input, result, "installDir"), "installDir");
        rejectBroad(install, "installDir");
        Prepared prepared = new Prepared(install);
        try {
            loadExisting(prepared);
            result.put("installDir", install.toString()).put("configured", prepared.existingViewer != null);
            if (prepared.existingViewer != null) result.put("viewerPort", prepared.existingViewer.path("port").asInt());
            for (String key : SetupServiceCatalog.keys()) {
                ObjectNode service = (ObjectNode) result.get(key);
                JsonNode entry = prepared.existingViewer == null ? null : serviceEntry((ArrayNode) prepared.existingViewer.path("services"), name(key));
                boolean installed = entry != null;
                service.put("installed", installed).put("configured", installed);
                if (prepared.existingViewer != null) service.put("enabled", installed);
                service.set("capture", SetupCaptureSettings.current(template(name(key))));
                service.put("launcherAvailable", false).put("launchMode", "manual");
                if (!installed) continue;
                service.put("enabled", true).put("sourceRoot", entry.path("sourceRoot").asText())
                        .put("debugPort", java.net.URI.create(entry.path("url").asText()).getPort());
                Path path = install.resolve(folder(key) + "/tracelens.json");
                service.put("configPath", path.toString());
                try {
                    ObjectNode config = serviceConfig(prepared, key);
                    service.set("capture", SetupCaptureSettings.current(config));
                    service.set("httpIncludePaths", config.path("httpIncludePaths").deepCopy());
                    Path launcher = localPath(install.resolve("start-" + name(key) + ".sh").toString(), "Service launcher");
                    boolean available = Files.isRegularFile(launcher, NOFOLLOW) && Files.isExecutable(launcher);
                    service.put("launcherAvailable", available).put("launchMode", available ? "available" : "manual");
                } catch (IllegalArgumentException | IOException failure) {
                    service.put("loadError", failure.getMessage());
                }
            }
            return result;
        } catch (IOException failure) { throw new IllegalArgumentException("Cannot load setup: " + failure.getMessage(), failure); }
    }

    public ObjectNode apply(JsonNode request, String expectedPlanId) {
        Prepared prepared = prepare(request);
        if (expectedPlanId == null || !expectedPlanId.equals(prepared.document.path("planId").asText()))
            throw new IllegalArgumentException("Setup plan changed; preview it again before installing");
        if (prepared.ready.isEmpty()) throw new IllegalArgumentException("No service has a ready installation or configuration change");
        try {
            if (prepared.existingViewer == null) freshDirectory(prepared.install);
            verifyOriginals(prepared);
            privateDirectory(prepared.install);
            if (prepared.existingViewer == null) Files.setPosixFilePermissions(prepared.install, PosixFilePermissions.fromString("rwx------"));
            Path installedJar = prepared.install.resolve("tracelens.jar");
            if (prepared.files.containsKey(installedJar)) writeNew(installedJar, prepared.files.get(installedJar), false);
            if (prepared.viewerUpgrade != null && prepared.files.containsKey(prepared.viewerUpgrade)) {
                privateDirectory(prepared.viewerUpgrade.getParent()); writeNew(prepared.viewerUpgrade, prepared.files.get(prepared.viewerUpgrade), false);
            }
            ObjectNode result = prepared.document.deepCopy();
            ArrayNode outcomes = (ArrayNode) result.path("services"), warnings = (ArrayNode) result.path("warnings"), applied = Json.arr();
            Path backup = null;
            for (Path path : prepared.files.keySet()) if (prepared.originals.containsKey(path)) {
                if (backup == null) { backup = prepared.install.resolve("setup-backups/" + UUID.randomUUID()); privateDirectory(backup); }
                Path saved = backup.resolve(prepared.install.relativize(path).toString() + ".before");
                privateDirectory(saved.getParent()); writeNew(saved, prepared.originals.get(path), false);
                if (path.equals(prepared.primary)) writeNew(backup.resolve("viewer-config.before.json"), prepared.originals.get(path), false);
                if (path.equals(prepared.install.resolve(STATE_FILE))) writeNew(backup.resolve("setup-state.before.json"), prepared.originals.get(path), false);
            }
            if (backup != null) result.put("backupDir", backup.toString());
            if (prepared.viewerUpgrade != null && prepared.files.containsKey(prepared.viewerUpgrade)) applied.add(prepared.viewerUpgrade.toString());
            Set<String> successful = new LinkedHashSet<String>();
            Map<Path, byte[]> written = new LinkedHashMap<Path, byte[]>();
            for (String key : prepared.ready) {
                try {
                    for (Map.Entry<Path, byte[]> file : prepared.files.entrySet()) if (belongsTo(file.getKey(), prepared.install, key)) {
                        privateDirectory(file.getKey().getParent());
                        byte[] bytes = file.getValue();
                        if (prepared.updates.contains(key)) {
                            if (file.getKey().equals(prepared.primary) && !prepared.added.isEmpty()) {
                                ObjectNode ownChanges = (ObjectNode) Json.parse(bytes);
                                ownChanges.set("viewer", prepared.primaryConfig.path("viewer").deepCopy()); bytes = jsonBytes(ownChanges);
                            }
                            replaceChecked(file.getKey(), prepared.originals.get(file.getKey()), bytes);
                        } else writeNew(file.getKey(), bytes, file.getKey().toString().endsWith(".sh"));
                        written.put(file.getKey(), bytes);
                        applied.add(file.getKey().toString());
                    }
                    if ("cbApp".equals(key) && prepared.setenv != null) { installSetenv(prepared); applied.add(prepared.setenv.toString()); }
                    successful.add(key);
                    outcome(outcomes, key).put("status", prepared.updates.contains(key) ? "updated" : "installed")
                            .put("restartRequired", prepared.updates.contains(key));
                } catch (IOException | IllegalArgumentException | java.nio.channels.OverlappingFileLockException failure) {
                    outcome(outcomes, key).put("status", "failed").put("error", "Files could not be installed; preserve partial output and review filesystem permissions before retrying.");
                    warnings.add(name(key) + " could not be completed. Its partial files were retained for review; other selected services continue.");
                }
            }
            if (successful.isEmpty()) throw new IOException("No service installation completed; partial files were preserved for review");
            Set<String> successfulAdds = new LinkedHashSet<String>(prepared.added); successfulAdds.retainAll(successful);
            if (successfulAdds.isEmpty()) {
                result.set("files", applied); result.put("partial", successful.size() != prepared.ready.size() || hasFailed(outcomes));
                result.set("commands", successfulCommands(result.path("commands"), prepared, successful));
                result.put("installed", true).put("restartRequired", true);
                return result;
            }
            // Publish the shared registry only after independent service writes complete.
            Path plannedPrimary = Paths.get(prepared.document.path("viewerConfig").asText());
            Path primary = prepared.primary != null ? prepared.primary : prepared.install.resolve(folder(successful.iterator().next()) + "/tracelens.json");
            ObjectNode config = (ObjectNode) Json.parse(prepared.files.get(plannedPrimary));
            ObjectNode viewer = (ObjectNode) config.path("viewer"); ArrayNode registry = Json.arr();
            for (JsonNode entry : viewer.path("services")) {
                boolean failedNew = false;
                for (String key : prepared.added) if (name(key).equals(entry.path("name").asText()) && !successful.contains(key)) failedNew = true;
                if (!failedNew) registry.add(entry);
            }
            viewer.set("services", registry);
            for (String key : successful) {
                Path path = prepared.install.resolve(folder(key) + "/tracelens.json");
                if (path.equals(prepared.primary)) continue;
                ObjectNode serviceConfig = (ObjectNode) Json.parse(written.get(path)); serviceConfig.set("viewer", viewer.deepCopy());
                replaceChecked(path, written.get(path), jsonBytes(serviceConfig));
            }
            if (prepared.primary != null) {
                for (String script : Arrays.asList("start-viewer.sh", "check-setup.sh")) if (prepared.files.containsKey(prepared.install.resolve(script))) {
                    Path path = prepared.install.resolve(script);
                    replaceChecked(path, prepared.originals.get(path), prepared.files.get(path)); applied.add(path.toString());
                }
                byte[] primaryBefore = written.containsKey(primary) ? written.get(primary) : prepared.originals.get(primary);
                config = (ObjectNode) Json.parse(primaryBefore); config.set("viewer", viewer);
                replaceChecked(primary, primaryBefore, jsonBytes(config)); applied.add(primary.toString());
            } else {
                for (String script : Arrays.asList("start-viewer.sh", "check-setup.sh")) {
                    Path path = prepared.install.resolve(script);
                    String value = new String(prepared.files.get(path), StandardCharsets.UTF_8)
                            .replace(quote(plannedPrimary.toString()), quote(primary.toString()));
                    writeNew(path, utf8(value), true); applied.add(path.toString());
                }
                applied.add(installedJar.toString());
            }
            Path state = prepared.install.resolve(STATE_FILE);
            byte[] stateBytes = jsonBytes(Json.obj("schemaVersion", 1, "kind", "tracelens-local-setup",
                    "installDir", prepared.install.toString(), "viewerConfig", primary.toString()));
            if (prepared.originals.containsKey(state)) replaceChecked(state, prepared.originals.get(state), stateBytes);
            else writeNew(state, stateBytes, false);
            applied.add(state.toString()); result.set("files", applied); result.put("viewerConfig", primary.toString());
            result.set("commands", successfulCommands(result.path("commands"), prepared, successful));
            result.put("partial", successful.size() != prepared.ready.size() || hasFailed(outcomes));
            result.put("installed", true).put("restartRequired", !prepared.updates.isEmpty());
            return result;
        } catch (IOException exc) {
            // Do not remove partial output: the private backup may be needed for recovery.
            throw new IllegalStateException("Setup stopped; no application was started. Preserve the private install directory and any setenv backup: "
                    + exc.getMessage(), exc);
        }
    }

    private static boolean belongsTo(Path path, Path install, String key) {
        return path.startsWith(install.resolve(folder(key))) || path.equals(install.resolve("start-" + name(key) + ".sh"));
    }
    private static ObjectNode outcome(ArrayNode outcomes, String key) {
        for (JsonNode row : outcomes) if (key.equals(row.path("key").asText())) return (ObjectNode) row;
        throw new IllegalStateException("Missing service outcome");
    }
    private static boolean hasFailed(ArrayNode outcomes) {
        for (JsonNode row : outcomes) if ("failed".equals(row.path("status").asText())) return true;
        return false;
    }
    private static ArrayNode successfulCommands(JsonNode commands, Prepared prepared, Set<String> successful) {
        ArrayNode result = Json.arr();
        for (JsonNode command : commands) {
            boolean failedLauncher = false;
            for (String key : prepared.ready) if (!successful.contains(key)
                    && command.path("command").asText().equals(quote(prepared.install.resolve("start-" + name(key) + ".sh").toString()))) failedLauncher = true;
            if (!failedLauncher) result.add(command);
        }
        return result;
    }
    private static void verifyOriginals(Prepared prepared) throws IOException {
        for (Map.Entry<Path, byte[]> item : prepared.originals.entrySet()) {
            localPath(item.getKey().toString(), "Existing setup file"); regular(item.getKey(), MAX_COMBINED_JAR, "Existing setup file");
            if (!Arrays.equals(item.getValue(), Files.readAllBytes(item.getKey()))) throw new IOException("Existing setup changed; review a new plan");
        }
    }
    private static void replaceChecked(Path path, byte[] before, byte[] after) throws IOException {
        localPath(path.toString(), "Reviewed setup update");
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE, NOFOLLOW);
             FileLock lock = channel.tryLock()) {
            if (lock == null || channel.size() != before.length) throw new IOException("Reviewed setup file changed or is in use");
            ByteBuffer current = ByteBuffer.allocate(before.length);
            while (current.hasRemaining() && channel.read(current) != -1) { /* bounded compare */ }
            if (!Arrays.equals(before, current.array())) throw new IOException("Reviewed setup file changed; no update applied");
            channel.position(0); ByteBuffer replacement = ByteBuffer.wrap(after);
            while (replacement.hasRemaining()) channel.write(replacement);
            channel.truncate(after.length); channel.force(true);
        }
    }

    private Prepared prepare(JsonNode input) {
        try {
            if (input == null || !input.isObject()) throw new IllegalArgumentException("Setup request must be an object");
            Set<String> allowed = new LinkedHashSet<String>(Arrays.asList("installDir", "viewerPort", "captureJdbcQueries", "captureJdbcParameters", "captureMode", "configured"));
            allowed.addAll(SetupServiceCatalog.keys()); keys(input, allowed.toArray(new String[0]));
            ObjectNode fallback = defaults(), request = Json.obj();
            Path install = safePath(text(input, fallback, "installDir"), "installDir");
            rejectBroad(install, "installDir");
            Prepared prepared = new Prepared(install);
            loadExisting(prepared);
            Path jar = safePath(sourceJar.toString(), "sourceJar");
            regular(jar, MAX_COMBINED_JAR, "Combined JAR");
            validateJar(jar);
            byte[] jarBytes = Files.readAllBytes(jar);
            int viewerPort = port(input, fallback, "viewerPort");
            boolean queries = bool(input, fallback, "captureJdbcQueries"), parameters = bool(input, fallback, "captureJdbcParameters");
            String captureMode = text(input, fallback, "captureMode");
            request.put("installDir", install.toString()).put("viewerPort", viewerPort)
                    .put("captureJdbcQueries", queries).put("captureJdbcParameters", parameters).put("captureMode", captureMode);
            if (prepared.existingViewer != null) viewerPort = prepared.existingViewer.path("port").asInt();
            request.put("viewerPort", viewerPort);
            Set<Integer> ports = new HashSet<Integer>(); ports.add(viewerPort);
            ArrayNode registry = Json.arr(), warnings = Json.arr(), commands = Json.arr();
            if (prepared.existingViewer != null) {
                registry = ((ArrayNode) prepared.existingViewer.path("services")).deepCopy();
                for (JsonNode entry : registry) {
                    java.net.URI endpoint = java.net.URI.create(entry.path("url").asText());
                    if (endpoint.getPort() > 0) ports.add(endpoint.getPort());
                }
                warnings.add("Existing agent JAR, application launchers, viewer port and storage paths are preserved. Only reviewed capture fields are updated. Adding services may separately update generated viewer launchers to this bundle's compatible viewer; no running process is changed.");
            }
            ArrayNode outcomes = Json.arr();
            for (SetupServiceCatalog.Entry definition : SetupServiceCatalog.services()) {
                String key = definition.key, name = definition.name;
                JsonNode serviceInput = input.has(key) ? input.get(key) : fallback.get(key);
                JsonNode base = fallback.get(key);
                try {
                if (!serviceInput.isObject()) throw new IllegalArgumentException(key + " must be an object");
                keys(serviceInput, "enabled", "sourceRoot", "debugPort", "httpIncludePaths", "launchJar", "javaHome", "capture",
                        "installed", "configured", "launcherAvailable", "launchMode", "configPath", "loadError");
                boolean enabled = bool(serviceInput, base, "enabled");
                request.set(key, serviceInput.deepCopy());
                if (!enabled) { request.set(key, Json.obj("enabled", false)); continue; }
                if (hasService(registry, name)) {
                    ArrayNode changes = Json.arr();
                    if (serviceInput.has("capture")) {
                        ObjectNode config = serviceConfig(prepared, key);
                        ObjectNode updated = SetupCaptureSettings.merge(config, serviceInput.get("capture"), changes);
                        if (serviceInput.has("httpIncludePaths") && !serviceInput.get("httpIncludePaths").equals(config.path("httpIncludePaths"))) {
                            ArrayNode paths = prefixes(serviceInput.get("httpIncludePaths"));
                            changes.add(Json.obj("field", "httpIncludePaths", "before", config.get("httpIncludePaths"), "after", paths));
                            updated.set("httpIncludePaths", paths);
                        }
                        if (changes.size() > 0) {
                            prepared.files.put(install.resolve(folder(key) + "/tracelens.json"), jsonBytes(updated));
                            prepared.ready.add(key); prepared.updates.add(key);
                            captureWarnings(updated, name, warnings);
                            warnings.add(name + ": configuration changes require a deliberate restart. Existing launchers and agent JAR paths are preserved; a manual launcher needs the current combined agent bundle for newly supported modes such as methods.");
                        }
                        request.set(key, Json.obj("enabled", true, "capture", serviceInput.get("capture"), "httpIncludePaths", updated.path("httpIncludePaths")));
                    } else request.set(key, Json.obj("enabled", true));
                    outcomes.add(Json.obj("key", key, "name", name, "status", changes.size() == 0 ? "existing" : "ready",
                            "operation", "update", "configChanges", changes, "restartRequired", changes.size() > 0));
                    continue;
                }
                Path root = localPath(text(serviceInput, base, "sourceRoot"), key + ".sourceRoot");
                int debugPort = port(serviceInput, base, "debugPort");
                ArrayNode paths = prefixes(serviceInput.has("httpIncludePaths") ? serviceInput.get("httpIncludePaths") : base.get("httpIncludePaths"));
                ObjectNode service = Json.obj("enabled", enabled, "sourceRoot", root.toString(), "debugPort", debugPort, "httpIncludePaths", paths);
                ObjectNode config = template(name);
                config.set("httpIncludePaths", paths);
                config.put("capturePayloads", false);
                ArrayNode changes = Json.arr();
                if (serviceInput.has("capture")) {
                    config = SetupCaptureSettings.merge(config, serviceInput.get("capture"), changes);
                    service.set("capture", serviceInput.get("capture").deepCopy());
                } else {
                    if (!"overview".equals(captureMode) && !"methods".equals(captureMode) && !"detailed".equals(captureMode))
                        throw new IllegalArgumentException("Capture mode must be overview, methods or detailed");
                    if (parameters && !queries) throw new IllegalArgumentException("SQL parameters require SQL query capture");
                    config.put("captureJdbcQueries", queries).put("captureJdbcParameters", parameters).put("captureMode", captureMode);
                }
                for (String optional : Arrays.asList("launchJar", "javaHome")) if (serviceInput.hasNonNull(optional)) {
                    String value = Json.text(serviceInput, optional);
                    if (value != null && value.isEmpty()) continue;
                    service.put(optional, safePath(value, key + "." + optional).toString());
                }
                request.set(key, service);
                rejectBroad(root, key + ".sourceRoot");
                if (!Files.isDirectory(root, NOFOLLOW)) throw new IllegalArgumentException(key + ".sourceRoot must be an existing directory");
                if (root.startsWith(install) || install.equals(root)) throw new IllegalArgumentException("Install directory cannot contain a source checkout");
                if (registry.size() >= 32) throw new IllegalArgumentException("Viewer registry supports at most 32 services; existing entries were preserved");
                if (ports.contains(debugPort)) throw new IllegalArgumentException("Viewer and enabled service debug ports must be distinct");
                ensureNewServiceOutputs(prepared, definition, service);
                if ("cbApp".equals(key)) prepareCbApp(prepared, root);
                else if ("ems".equals(key) || "plans".equals(key)) validateExecutableLauncher(service, "ems".equals(key) ? "EMS" : "Plans");
                else if (service.hasNonNull("launchJar") || service.hasNonNull("javaHome"))
                    throw new IllegalArgumentException("Use generated VM options in this service's existing servlet/Tomcat launcher; executable-JAR launchers are offered only for EMS and Plans");
                ports.add(debugPort);
                registry.add(Json.obj("name", name, "url", "http://127.0.0.1:" + debugPort, "sourceRoot", root.toString()));
                prepared.ready.add(key);
                prepared.added.add(key); prepared.newConfigs.put(key, config);
                captureWarnings(config, name, warnings);
                outcomes.add(Json.obj("key", key, "name", name, "status", "ready", "operation", "add", "configChanges", changes));
                } catch (IllegalArgumentException | IOException failure) {
                    if ("cbApp".equals(key)) { prepared.setenv = null; prepared.before = null; }
                    outcomes.add(Json.obj("key", key, "name", name, "status", "failed", "error", failure.getMessage()));
                    warnings.add(name + " was not configured: " + failure.getMessage());
                }
            }
            if (registry.size() == 0) throw new IllegalArgumentException(outcomes.size() == 0 ? "Enable at least one service" : "No selected service is ready. " + warnings.get(0).asText());
            Path viewerConfig = prepared.primary != null ? prepared.primary : install.resolve(folder(prepared.ready.iterator().next()) + "/tracelens.json");
            if (prepared.primary != null && !prepared.added.isEmpty()) prepareViewerUpgrade(prepared, jarBytes, warnings);
            Path newAgentJar = prepared.viewerUpgrade != null ? prepared.viewerUpgrade : install.resolve("tracelens.jar");
            ObjectNode viewer = prepared.existingViewer != null ? prepared.existingViewer.deepCopy() : Json.obj("port", viewerPort, "viewerUrl", "http://127.0.0.1:" + viewerPort,
                    "services", registry, "captureDir", install.resolve("captures").toString(),
                    "snapshotDir", install.resolve("viewer-snapshots").toString());
            viewer.set("services", registry);
            if (prepared.existingViewer == null) prepared.files.put(install.resolve("tracelens.jar"), jarBytes);
            for (String key : SetupServiceCatalog.keys()) {
                JsonNode service = request.get(key);
                if (!prepared.added.contains(key)) continue;
                String folder = folder(key);
                ObjectNode config = prepared.newConfigs.get(key);
                config.set("viewer", viewer.deepCopy());
                prepared.files.put(install.resolve(folder + "/tracelens.json"), utf8(Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(config) + "\n"));
                if ("cbApp".equals(key)) {
                    prepared.append = utf8(setenvBlock(install, newAgentJar, service.path("debugPort").asInt()));
                    if (prepared.before != null) prepared.files.put(install.resolve("cb-app/setenv.sh.before-tracelens"), prepared.before);
                    prepared.files.put(install.resolve("start-cb-app.sh"), utf8(cbAppLauncher(Paths.get(service.path("sourceRoot").asText()))));
                    commands.add(command("Start cb-app explicitly with Java 8 and tracing", install.resolve("start-cb-app.sh")));
                } else {
                    String options = agentOptions(install, newAgentJar, folder, service.path("debugPort").asInt());
                    prepared.files.put(install.resolve(folder + "/vm-options.txt"), utf8(options.replace(" ", "\n") + "\n"));
                    if (service.hasNonNull("launchJar")) {
                        String java = service.path("javaHome").asText() + "/bin/java";
                        String script = HEADER + javaCheck(java, "21", name(key)) + "cd " + quote(service.path("sourceRoot").asText()) + "\n"
                                + "exec " + quote(java) + " " + options + " -jar " + quote(service.path("launchJar").asText()) + " \"$@\"\n";
                        prepared.files.put(install.resolve("start-" + name(key) + ".sh"), utf8(script));
                        commands.add(command("Start " + name(key) + " explicitly with your existing environment", install.resolve("start-" + name(key) + ".sh")));
                    } else warnings.add(name(key) + ": add " + folder + "/vm-options.txt to your existing application VM options, then restart that service deliberately. Setup does not edit its launcher or start it.");
                }
            }
            String java = localPath(Paths.get(System.getProperty("java.home"), "bin/java").toString(), "Viewer Java").toString();
            String invocation = quote(java) + " -jar " + quote(install.resolve("tracelens.jar").toString()) + " --config " + quote(viewerConfig.toString());
            if (prepared.existingViewer == null) {
                prepared.files.put(install.resolve("start-viewer.sh"), utf8(HEADER + "exec " + invocation + " serve \"$@\"\n"));
                prepared.files.put(install.resolve("check-setup.sh"), utf8(HEADER + "exec " + invocation + " doctor \"$@\"\n"));
            } else if (!prepared.added.isEmpty()) {
                if (prepared.viewerUpgrade != null) {
                    String upgraded = quote(prepared.viewerJava) + " -jar " + quote(prepared.viewerUpgrade.toString()) + " --config " + quote(viewerConfig.toString());
                    prepared.files.put(install.resolve("start-viewer.sh"), utf8(HEADER + "exec " + upgraded + " serve \"$@\"\n"));
                    prepared.files.put(install.resolve("check-setup.sh"), utf8(HEADER + "exec " + upgraded + " doctor \"$@\"\n"));
                }
                ObjectNode updated = prepared.files.containsKey(viewerConfig) ? (ObjectNode) Json.parse(prepared.files.get(viewerConfig)) : prepared.primaryConfig.deepCopy();
                updated.set("viewer", viewer);
                prepared.files.put(viewerConfig, jsonBytes(updated));
            }
            if (!prepared.added.isEmpty()) prepared.files.put(install.resolve(STATE_FILE), jsonBytes(Json.obj("schemaVersion", 1,
                    "kind", "tracelens-local-setup", "installDir", install.toString(), "viewerConfig", viewerConfig.toString())));
            commands.add(command("Start the local viewer", install.resolve("start-viewer.sh")));
            commands.add(command("Check service connectivity after starting applications", install.resolve("check-setup.sh")));
            warnings.add("No application is built, started, stopped or restarted by setup. Existing application environment and credentials remain your responsibility.");
            warnings.add("Adding services to the viewer does not automatically link their requests. Configure both services' API capture paths and each caller's httpPropagationAllowedDestinations with the exact application host:port it calls; setup does not discover or widen propagation destinations.");
            warnings.add("Capture and snapshot data may be sensitive; the install directory and generated files are owner-only.");
            if (queries) warnings.add("SQL query capture is enabled and can expose literal values. Share captures only after reviewing them.");
            if (parameters) warnings.add("SQL parameter capture is enabled and can expose bound values and credentials.");
            ArrayNode files = Json.arr();
            for (Path file : prepared.files.keySet()) files.add(Json.obj("path", file.toString(), "action", prepared.originals.containsKey(file) ? "update" : "create", "description", description(file)));
            if (prepared.setenv != null) files.add(Json.obj("path", prepared.setenv.toString(), "action", prepared.before == null ? "create" : "append",
                    "description", "Opt-in TRACELENS=1 startup hook; existing bytes preserved and backed up"));
            ObjectNode fingerprint = Json.obj("request", request, "sourceJar", jar.toString(), "sourceJarSha256", hash(jarBytes),
                    "sourceJarModified", Files.getLastModifiedTime(jar, NOFOLLOW).toMillis(),
                    "setenvExists", prepared.before != null, "setenvSha256", prepared.before == null ? null : hash(prepared.before));
            ArrayNode outputs = Json.arr();
            for (Map.Entry<Path, byte[]> file : prepared.files.entrySet()) outputs.add(Json.arr(file.getKey().toString(), hash(file.getValue())));
            fingerprint.set("outputs", outputs);
            ArrayNode originals = Json.arr();
            for (Map.Entry<Path, byte[]> original : prepared.originals.entrySet()) originals.add(Json.arr(original.getKey().toString(), hash(original.getValue())));
            fingerprint.set("existingFiles", originals);
            if (prepared.append != null) fingerprint.put("append", hash(prepared.append));
            prepared.document = Json.obj("planId", Json.sha256(Json.canonical(fingerprint)), "request", request,
                    "existingInstallation", prepared.existingViewer != null,
                    "installDir", install.toString(), "viewerConfig", viewerConfig.toString(), "viewerUrl", "http://127.0.0.1:" + viewerPort,
                    "files", files, "warnings", warnings, "commands", commands, "services", outcomes);
            return prepared;
        } catch (IOException exc) { throw new IllegalArgumentException("Cannot prepare setup: " + exc.getMessage(), exc); }
    }

    private static void prepareCbApp(Prepared prepared, Path root) throws IOException {
        Path catalina = localPath(root.resolve("tomcat/bin/catalina.sh").toString(), "cb-app catalina.sh");
        regular(catalina, MAX_SETENV, "cb-app tomcat/bin/catalina.sh");
        if (!Files.isExecutable(catalina)) throw new IllegalArgumentException("cb-app tomcat/bin/catalina.sh must be executable");
        prepared.setenv = localPath(root.resolve("tomcat/bin/setenv.sh").toString(), "cb-app setenv.sh");
        if (Files.exists(prepared.setenv, NOFOLLOW)) {
            regular(prepared.setenv, MAX_SETENV, "cb-app setenv.sh");
            prepared.before = Files.readAllBytes(prepared.setenv);
            String existing = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(prepared.before)).toString();
            if (existing.toLowerCase(Locale.ROOT).contains("tracelens"))
                throw new IllegalArgumentException("cb-app setenv.sh already contains TraceLens references; review and remove the old hook manually before setup");
            if (!Files.isWritable(prepared.setenv)) throw new IllegalArgumentException("cb-app setenv.sh must be writable");
        }
        if (!Files.isWritable(prepared.setenv.getParent())) throw new IllegalArgumentException("cb-app tomcat/bin must be writable");
    }

    private static String folder(String key) { return SetupServiceCatalog.get(key).folder; }
    private static String name(String key) { return SetupServiceCatalog.get(key).name; }
    private static boolean hasService(ArrayNode registry, String name) {
        return serviceEntry(registry, name) != null;
    }
    private static JsonNode serviceEntry(ArrayNode registry, String name) {
        for (JsonNode item : registry) if (name.equals(item.path("name").asText())) return item;
        return null;
    }
    private static ObjectNode serviceConfig(Prepared prepared, String key) throws IOException {
        Path path = prepared.install.resolve(folder(key) + "/tracelens.json");
        JsonNode config = Json.parse(prepared.originals.containsKey(path) ? prepared.originals.get(path) : remember(prepared, path, MAX_SETENV));
        if (!config.isObject()) throw new IllegalArgumentException(name(key) + " capture config must be a JSON object");
        return (ObjectNode) config;
    }
    private static void captureWarnings(JsonNode config, String name, ArrayNode warnings) {
        if (config.path("captureJdbcQueries").asBoolean() || config.path("captureClickHouseQueries").asBoolean())
            warnings.add(name + ": SQL text may expose literal values; review captures before sharing.");
        if (config.path("captureJdbcParameters").asBoolean() || config.path("captureClickHouseParameters").asBoolean())
            warnings.add(name + ": SQL bindings may expose sensitive values and credentials.");
        if (config.path("captureHttpQueryParameters").asBoolean() || config.path("captureHttpRequestBodies").asBoolean())
            warnings.add(name + ": HTTP evidence may contain sensitive data; redaction is best-effort, not a guarantee.");
        if (config.has("redactHttpPayloads") && !config.path("redactHttpPayloads").asBoolean())
            warnings.add(name + ": HTTP payload redaction is disabled.");
    }
    private static byte[] jsonBytes(JsonNode value) throws IOException {
        return utf8(Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
    }
    private static byte[] remember(Prepared prepared, Path path, int maximum) throws IOException {
        localPath(path.toString(), "Existing setup file"); regular(path, maximum, "Existing setup file");
        byte[] value = Files.readAllBytes(path); prepared.originals.put(path, value); return value;
    }

    private static void prepareViewerUpgrade(Prepared prepared, byte[] sourceBytes, ArrayNode warnings) throws IOException {
        if (Arrays.equals(sourceBytes, prepared.originals.get(prepared.install.resolve("tracelens.jar")))) return;
        Path start = prepared.install.resolve("start-viewer.sh"), check = prepared.install.resolve("check-setup.sh");
        String script = new String(prepared.originals.get(start), StandardCharsets.UTF_8);
        Matcher generated = Pattern.compile(Pattern.quote(HEADER + "exec ") + "'([^'\\r\\n]+)' -jar '([^'\\r\\n]+)' --config "
                + Pattern.quote(quote(prepared.primary.toString())) + " serve \"\\$@\"\\n").matcher(script);
        if (!generated.matches() || !new String(prepared.originals.get(check), StandardCharsets.UTF_8)
                .equals(script.substring(0, script.length() - "serve \"$@\"\n".length()) + "doctor \"$@\"\n"))
            throw new IllegalArgumentException("Existing viewer launchers were customized. Preserve them and update their JAR explicitly, or restore the generated viewer/check launchers before adding services");
        prepared.viewerJava = localPath(generated.group(1), "Existing viewer Java").toString();
        Path previousJar = safePath(generated.group(2), "Existing viewer JAR");
        if (!previousJar.equals(prepared.install.resolve("tracelens.jar")) && !(previousJar.getParent().equals(prepared.install.resolve("viewer"))
                && previousJar.getFileName().toString().matches("tracelens-[0-9a-f]{64}\\.jar")))
            throw new IllegalArgumentException("Existing viewer launcher references an unmanaged JAR; review it manually without changing application agents");
        prepared.viewerUpgrade = prepared.install.resolve("viewer/tracelens-" + hash(sourceBytes) + ".jar");
        localPath(prepared.viewerUpgrade.toString(), "Versioned viewer JAR");
        if (Files.exists(prepared.viewerUpgrade, NOFOLLOW)) {
            if (!Arrays.equals(sourceBytes, remember(prepared, prepared.viewerUpgrade, MAX_COMBINED_JAR)))
                throw new IllegalArgumentException("Versioned viewer JAR already exists with different content; it will not be overwritten");
        } else prepared.files.put(prepared.viewerUpgrade, sourceBytes);
        warnings.add("The generated viewer/check launchers and newly added services will use a private versioned copy of this bundle's combined JAR. Existing agent JARs and application launchers are not overwritten. Restart the viewer deliberately; start each new service with its generated options when ready.");
    }

    /** Only recognize our bounded local layout; never adopt an arbitrary nonempty directory. */
    private static void loadExisting(Prepared prepared) throws IOException {
        Path install = prepared.install;
        if (!Files.exists(install, NOFOLLOW)) return;
        if (!Files.isDirectory(install, NOFOLLOW)) throw new IllegalArgumentException("Install destination must be a directory");
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(install)) { if (!entries.iterator().hasNext()) return; }
        Path state = install.resolve(STATE_FILE), viewerScript = install.resolve("start-viewer.sh"), checkScript = install.resolve("check-setup.sh");
        String configPath;
        if (Files.exists(state, NOFOLLOW)) {
            JsonNode saved = Json.parse(remember(prepared, state, MAX_SETENV));
            if (!saved.isObject() || saved.path("schemaVersion").asInt() != 1 || !"tracelens-local-setup".equals(saved.path("kind").asText())
                    || !install.toString().equals(saved.path("installDir").asText()))
                throw new IllegalArgumentException("Unrecognized setup-state.json; existing files were not changed");
            configPath = saved.path("viewerConfig").asText();
            // Capture launcher edits for preview consistency, but preserve their exact bytes.
            remember(prepared, viewerScript, MAX_SETENV); remember(prepared, checkScript, MAX_SETENV);
        } else {
            String script = new String(remember(prepared, viewerScript, MAX_SETENV), StandardCharsets.UTF_8);
            String prefix = HEADER + "exec ";
            Pattern generated = Pattern.compile(Pattern.quote(prefix) + "'([^'\\r\\n]+)' -jar "
                    + Pattern.quote(quote(install.resolve("tracelens.jar").toString())) + " --config '([^'\\r\\n]+)' serve \"\\$@\"\\n");
            Matcher match = generated.matcher(script);
            if (!match.matches()) throw new IllegalArgumentException("Install directory is not a recognized guided setup; use a fresh directory or review the existing integration manually");
            localPath(match.group(1), "Existing viewer Java"); configPath = match.group(2);
            String check = new String(remember(prepared, checkScript, MAX_SETENV), StandardCharsets.UTF_8);
            if (!check.equals(script.substring(0, script.length() - "serve \"$@\"\n".length()) + "doctor \"$@\"\n"))
                throw new IllegalArgumentException("Legacy guided setup launchers do not agree; existing files were not changed");
        }
        Path primary = safePath(configPath, "Existing primary viewer config");
        boolean known = false;
        for (SetupServiceCatalog.Entry service : SetupServiceCatalog.services()) known |= primary.equals(install.resolve(service.folder + "/tracelens.json"));
        if (!known) throw new IllegalArgumentException("Existing primary viewer config must be in the guided setup layout");
        JsonNode config = Json.parse(remember(prepared, primary, MAX_SETENV));
        if (!config.isObject() || !config.path("viewer").isObject() || !config.path("viewer").path("services").isArray())
            throw new IllegalArgumentException("Existing primary config requires a viewer service registry");
        ToolConfig existingConfig = ToolConfig.fromArgs(new String[]{"--config", primary.toString()});
        Path installedJar = install.resolve("tracelens.jar");
        remember(prepared, installedJar, MAX_COMBINED_JAR); validateJar(installedJar);
        prepared.primary = primary; prepared.primaryConfig = (ObjectNode) config;
        prepared.existingViewer = ((ObjectNode) config.path("viewer")).deepCopy();
        prepared.existingViewer.put("namespace", ViewerServer.viewerContext(existingConfig).path("namespace").asText());
        port(prepared.existingViewer, Json.obj("port", 7781), "port");
    }

    private static void ensureNewServiceOutputs(Prepared prepared, SetupServiceCatalog.Entry definition, JsonNode service) throws IOException {
        Path directory = prepared.install.resolve(definition.folder);
        localPath(directory.toString(), "Service output directory");
        if (Files.exists(directory, NOFOLLOW)) {
            if (!Files.isDirectory(directory, NOFOLLOW)) throw new IllegalArgumentException("Service output path is not a directory");
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                if (entries.iterator().hasNext()) throw new IllegalArgumentException("Service output directory already contains files; preserve and review them before adding this service");
            }
        }
        Path launcher = prepared.install.resolve("start-" + definition.name + ".sh");
        localPath(launcher.toString(), "Service launcher");
        if (Files.exists(launcher, NOFOLLOW)) throw new IllegalArgumentException("Service launcher already exists; it will not be overwritten");
    }

    private static void validateExecutableLauncher(JsonNode service, String label) throws IOException {
        if (service.hasNonNull("launchJar") != service.hasNonNull("javaHome"))
            throw new IllegalArgumentException(label + " launcher requires both explicit launchJar and javaHome");
        if (!service.hasNonNull("launchJar")) return;
        Path launch = Paths.get(service.path("launchJar").asText());
        regular(launch, MAX_EMS_LAUNCH_JAR, label + " launchJar");
        if (!launch.toString().endsWith(".jar")) throw new IllegalArgumentException(label + " launchJar must be a .jar file");
        try (JarFile jar = new JarFile(launch.toFile())) {
            if (jar.getManifest() == null || jar.getManifest().getMainAttributes().getValue("Main-Class") == null)
                throw new IllegalArgumentException(label + " launchJar must be an executable JAR with Main-Class");
        }
        Path java = safePath(Paths.get(service.path("javaHome").asText(), "bin/java").toString(), label + " Java");
        if (!Files.isRegularFile(java, NOFOLLOW) || !Files.isExecutable(java)) throw new IllegalArgumentException(label + " javaHome must contain an executable bin/java");
    }

    private static void installSetenv(Prepared prepared) throws IOException {
        localPath(prepared.setenv.toString(), "setenv.sh");
        if (prepared.before == null) {
            writeNew(prepared.setenv, prepared.append, false);
            return;
        }
        try (FileChannel channel = FileChannel.open(prepared.setenv, StandardOpenOption.READ, StandardOpenOption.WRITE, NOFOLLOW);
             FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("setenv.sh is in use");
            if (channel.size() != prepared.before.length) throw new IOException("setenv.sh changed after preview; no hook appended");
            ByteBuffer current = ByteBuffer.allocate(prepared.before.length);
            while (current.hasRemaining() && channel.read(current) != -1) { /* bounded read */ }
            if (!Arrays.equals(current.array(), prepared.before)) throw new IOException("setenv.sh changed after preview; no hook appended");
            channel.position(channel.size());
            ByteBuffer suffix = ByteBuffer.wrap(prepared.append);
            while (suffix.hasRemaining()) channel.write(suffix);
            channel.force(true);
        }
    }

    private static String setenvBlock(Path install, Path agentJar, int port) {
        return "\n\n# BEGIN TRACELENS MANAGED SETUP (opt-in only)\nif [ \"${TRACELENS:-0}\" = \"1\" ]; then\n"
                + "    for tracelens_setup_opts in \"${CATALINA_OPTS:-}\" \"${JAVA_OPTS:-}\" \"${JAVA_TOOL_OPTIONS:-}\" \"${_JAVA_OPTIONS:-}\" \"${JDK_JAVA_OPTIONS:-}\"; do\n"
                + "        case \"$tracelens_setup_opts\" in\n"
                + "            *-javaagent:*tracelens*|*-Dtracelens.*) echo 'TraceLens is already configured; keep only one startup hook.' >&2; return 1 ;;\n"
                + "        esac\n    done\n    unset tracelens_setup_opts\n"
                + "    [ -r " + quote(agentJar.toString()) + " ] && [ -r " + quote(install.resolve("cb-app/tracelens.json").toString())
                + " ] || { echo 'TraceLens JAR/config is missing.' >&2; return 1; }\n"
                + "    CATALINA_OPTS=\"${CATALINA_OPTS:-} " + agentOptions(install, agentJar, "cb-app", port) + "\"\n"
                + "    export CATALINA_OPTS\nfi\n# END TRACELENS MANAGED SETUP\n";
    }

    private static String agentOptions(Path install, Path agentJar, String folder, int port) {
        return "-javaagent:" + agentJar + "=standalone -Dtracelens.configPath="
                + install.resolve(folder + "/tracelens.json") + " -Dtracelens.port=" + port;
    }

    private static String cbAppLauncher(Path root) {
        Path tomcat = root.resolve("tomcat");
        return HEADER + "tracelens_expected_tomcat=" + quote(tomcat.toString()) + "\n"
                + "for tracelens_tomcat_root in \"${CATALINA_HOME:-$tracelens_expected_tomcat}\" \"${CATALINA_BASE:-$tracelens_expected_tomcat}\"; do\n"
                + "    [ \"$tracelens_tomcat_root\" = \"$tracelens_expected_tomcat\" ] || { echo 'CATALINA_HOME/CATALINA_BASE points to another Tomcat; unset it or select this checkout explicitly.' >&2; exit 1; }\n"
                + "done\nCATALINA_HOME=$tracelens_expected_tomcat\nCATALINA_BASE=$CATALINA_HOME\nunset tracelens_tomcat_root tracelens_expected_tomcat\nexport CATALINA_HOME CATALINA_BASE\n"
                + "if [ -z \"${JAVA_HOME:-}\" ]; then\n"
                + "    [ -x /usr/libexec/java_home ] || { echo 'Set JAVA_HOME to an existing Java 8 installation.' >&2; exit 1; }\n"
                + "    JAVA_HOME=$(/usr/libexec/java_home -v 1.8)\nfi\n"
                + "[ -x \"$JAVA_HOME/bin/java\" ] || { echo 'JAVA_HOME/bin/java is not executable.' >&2; exit 1; }\n"
                + "case \"$(\"$JAVA_HOME/bin/java\" -version 2>&1)\" in\n"
                + "    *'version \"1.8.'*) ;;\n    *) echo 'cb-app requires Java 8; set JAVA_HOME explicitly.' >&2; exit 1 ;;\nesac\n"
                + "JRE_HOME=$JAVA_HOME\nexport JAVA_HOME JRE_HOME\ncd " + quote(tomcat.resolve("bin").toString()) + "\n"
                + "TRACELENS=1 exec " + quote(root.resolve("tomcat/bin/catalina.sh").toString()) + " run \"$@\"\n";
    }

    private static String javaCheck(String java, String version, String serviceName) {
        return "case \"$(" + quote(java) + " -version 2>&1)\" in\n    *'version \"" + version
                + ".'*) ;;\n    *) echo '" + serviceName + " requires the explicitly selected Java 21 runtime.' >&2; exit 1 ;;\nesac\n";
    }

    private static ObjectNode template(String name) {
        try (InputStream stream = SetupInstaller.class.getResourceAsStream("/tracelens-setup/" + name + ".json")) {
            if (stream == null) throw new IllegalStateException("Bundled setup template is missing: " + name);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = stream.read(buffer)) != -1) {
                if (bytes.size() + count > 65536) throw new IllegalStateException("Setup template is too large");
                bytes.write(buffer, 0, count);
            }
            JsonNode parsed = Json.parse(bytes.toByteArray());
            if (!parsed.isObject()) throw new IllegalStateException("Invalid bundled setup template");
            return (ObjectNode) parsed;
        } catch (IOException exc) { throw new IllegalStateException("Cannot read bundled setup template", exc); }
    }

    private static void validateJar(Path path) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            Attributes attributes = jar.getManifest() == null ? null : jar.getManifest().getMainAttributes();
            if (attributes == null || !"com.chargebee.tracelens.tool.ToolMain".equals(attributes.getValue("Main-Class"))
                    || !"com.chargebee.tracelens.agent.TraceLensAgent".equals(attributes.getValue("Premain-Class")))
                throw new IllegalArgumentException("Source must be the combined TraceLens JAR with ToolMain and TraceLensAgent manifest entries");
        }
    }

    private static Path safePath(String value, String label) {
        if (value == null || value.length() > 4096 || !value.matches("[A-Za-z0-9_./:@-]+"))
            throw new IllegalArgumentException(label + " must use safe absolute path characters (no spaces, quotes or shell syntax)");
        return localPath(value, label);
    }

    private static Path localPath(String value, String label) {
        if (value == null || value.isEmpty() || value.length() > 4096)
            throw new IllegalArgumentException(label + " must be a bounded absolute local path");
        for (int index = 0; index < value.length(); index++) if (value.charAt(index) < 32 || value.charAt(index) == 127)
            throw new IllegalArgumentException(label + " cannot contain control characters");
        Path path = Paths.get(value);
        if (!path.isAbsolute() || !path.normalize().equals(path)) throw new IllegalArgumentException(label + " must be an absolute normalized path");
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException(label + " cannot use symbolic links: " + current);
        }
        return path;
    }

    private static void rejectBroad(Path path, String label) {
        Path home = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
        if (path.getNameCount() < 3 || home.startsWith(path)) throw new IllegalArgumentException(label + " must be a dedicated directory, not a filesystem or home root");
    }

    private static void freshDirectory(Path path) throws IOException {
        if (!Files.exists(path, NOFOLLOW)) return;
        if (!Files.isDirectory(path, NOFOLLOW)) throw new IllegalArgumentException("Install destination must be a new or empty directory");
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
            if (entries.iterator().hasNext()) throw new IllegalArgumentException("Install destination is not empty; choose a fresh directory");
        }
    }

    private static void privateDirectory(Path path) throws IOException {
        safePath(path.toString(), "Output directory");
        if (Files.exists(path, NOFOLLOW)) {
            if (!Files.isDirectory(path, NOFOLLOW)) throw new IOException("Output parent is not a directory");
            return;
        }
        privateDirectory(path.getParent());
        Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    private static void writeNew(Path path, byte[] bytes, boolean executable) throws IOException {
        localPath(path.toString(), "Output file");
        Set<StandardOpenOption> options = new HashSet<StandardOpenOption>(Arrays.asList(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
        try (FileChannel channel = FileChannel.open(path, options, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString(executable ? "rwx------" : "rw-------")))) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    private static void regular(Path path, long maximum, String label) throws IOException {
        if (!Files.isRegularFile(path, NOFOLLOW) || !Files.isReadable(path) || Files.size(path) > maximum)
            throw new IllegalArgumentException(label + " must be a readable regular file within the size limit");
    }

    private static ArrayNode prefixes(JsonNode input) {
        if (input == null || !input.isArray() || input.size() > 64)
            throw new IllegalArgumentException("httpIncludePaths requires at most 64 explicit API path prefixes; [] disables HTTP capture");
        ArrayNode result = Json.arr(); Set<String> seen = new HashSet<String>();
        for (JsonNode row : input) {
            String value = row.isTextual() ? row.textValue() : null;
            if (value == null || value.length() > 512 || !value.matches("/[A-Za-z0-9_./~-]+")
                    || value.startsWith("//") || value.contains("/../") || value.endsWith("/..")
                    || value.contains("/./") || value.endsWith("/.") || !seen.add(value))
                throw new IllegalArgumentException("httpIncludePaths must contain unique local API prefixes, not URLs, queries, fragments or wildcards");
            result.add(value);
        }
        return result;
    }

    private static void keys(JsonNode input, String... allowed) {
        Set<String> accepted = new HashSet<String>(Arrays.asList(allowed));
        input.fieldNames().forEachRemaining(key -> {
            if (!accepted.contains(key)) throw new IllegalArgumentException("Unknown setup setting: " + key);
        });
    }
    private static String text(JsonNode input, JsonNode fallback, String key) {
        String result = Json.text(input.has(key) ? input : fallback, key);
        if (result == null) throw new IllegalArgumentException(key + " must be a string");
        return result;
    }
    private static boolean bool(JsonNode input, JsonNode fallback, String key) {
        JsonNode value = (input.has(key) ? input : fallback).get(key);
        if (value == null || !value.isBoolean()) throw new IllegalArgumentException(key + " must be a boolean");
        return value.booleanValue();
    }
    private static int port(JsonNode input, JsonNode fallback, String key) {
        JsonNode value = (input.has(key) ? input : fallback).get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1024 || value.intValue() > 65535)
            throw new IllegalArgumentException(key + " must be an integer from 1024 to 65535");
        return value.intValue();
    }
    private static ObjectNode command(String label, Path script) { return Json.obj("label", label, "command", quote(script.toString())); }
    private static String description(Path path) {
        String name = path.getFileName().toString();
        if (name.endsWith(".jar")) return "One private copy of the combined viewer and Java agent JAR";
        if (name.endsWith(".json")) return "Service capture configuration and shared viewer service registry";
        if (name.contains("before-tracelens")) return "Exact pre-install backup of cb-app setenv.sh";
        if (name.endsWith(".sh")) return "Explicit operator-run launcher/check; setup does not execute it";
        return "Agent VM options for your existing service launch configuration";
    }
    private static String quote(String safe) { return "'" + safe.replace("'", "'\"'\"'") + "'"; }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String hash(byte[] value) {
        try {
            StringBuilder out = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(value)) out.append(String.format(Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (Exception exc) { throw new IllegalStateException("SHA-256 unavailable", exc); }
    }
    private static final class Prepared {
        final Path install;
        final Map<Path, byte[]> files = new LinkedHashMap<Path, byte[]>();
        final Map<Path, byte[]> originals = new LinkedHashMap<Path, byte[]>();
        final Set<String> ready = new LinkedHashSet<String>();
        final Set<String> added = new LinkedHashSet<String>();
        final Set<String> updates = new LinkedHashSet<String>();
        final Map<String, ObjectNode> newConfigs = new LinkedHashMap<String, ObjectNode>();
        Path primary;
        Path viewerUpgrade;
        String viewerJava;
        ObjectNode primaryConfig, existingViewer;
        Path setenv;
        byte[] before, append;
        ObjectNode document;
        Prepared(Path install) { this.install = install; }
    }
}
