package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Operator-owned connection settings, never accepted from HTTP/MCP arguments. */
public final class ToolConfig {
    public List<Service> services;
    public String upstream = "http://127.0.0.1:7778";
    public String sourceRoot;
    public String viewerUrl;
    /** Stable, operator-owned identity when a managed installation adds services. */
    public String viewerNamespace;
    public Path snapshotDir = Paths.get(System.getProperty("user.home"), ".tracelens", "viewer-snapshots");
    public Path captureDir = Paths.get(System.getProperty("user.home"), ".tracelens", "captures");
    public int port = 7781;
    public boolean federated;
    /** Operator opt-in: lets the MCP get_segment_sql tool return captured SQL and bindings to the model. */
    public boolean mcpExposeSql;
    private boolean captureConfigService;
    public final List<String> remaining = new ArrayList<>();

    public static ToolConfig fromArgs(String[] args) {
        ToolConfig config = new ToolConfig();
        Set<String> flags = new HashSet<>(Arrays.asList("--url", "--base-url", "--services", "--services-file",
                "--source-root", "--capture-dir", "--snapshot-dir", "--config", "--port", "--viewer-url"));
        Map<String, String> options = new LinkedHashMap<>();
        boolean exposeSql = false;
        for (int i = 0; i < args.length; i++) {
            if ("--mcp-expose-sql".equals(args[i])) { exposeSql = true; continue; }
            if ("--".equals(args[i])) {
                config.remaining.addAll(Arrays.asList(args).subList(i, args.length));
                break;
            }
            String value = args[i], flag = value.contains("=") ? value.substring(0, value.indexOf('=')) : value;
            if (!flags.contains(flag)) { config.remaining.add(value); continue; }
            String argument;
            if (value.contains("=")) argument = value.substring(value.indexOf('=') + 1);
            else {
                if (++i == args.length || args[i].startsWith("--"))
                    throw new IllegalArgumentException(flag + " needs a value");
                argument = args[i];
            }
            if ("--base-url".equals(flag)) flag = "--url";
            if ("--services-file".equals(flag)) flag = "--services";
            if (options.put(flag, argument) != null) throw new IllegalArgumentException(flag + " supplied more than once");
        }
        if (options.containsKey("--config")) config.load(Paths.get(options.get("--config")));
        if (exposeSql) config.mcpExposeSql = true;
        if (options.containsKey("--services") && options.containsKey("--url"))
            throw new IllegalArgumentException("Choose --services or --url, not both");
        if (options.containsKey("--services")) {
            config.services = Service.load(Paths.get(options.get("--services")));
            config.federated = true;
        }
        if (options.containsKey("--url")) {
            config.upstream = options.get("--url");
            config.services = null;
            config.federated = false;
        }
        if (options.containsKey("--source-root")) config.sourceRoot = options.get("--source-root");
        if (options.containsKey("--capture-dir")) config.captureDir = Paths.get(options.get("--capture-dir"));
        if (options.containsKey("--snapshot-dir")) config.snapshotDir = Paths.get(options.get("--snapshot-dir"));
        if (options.containsKey("--port")) config.port = parsePort(options.get("--port"));
        if (options.containsKey("--viewer-url")) config.viewerUrl = options.get("--viewer-url");
        new DebugClient(config.upstream); // Validate even when no command contacts it.
        if (config.sourceRoot != null && !Paths.get(config.sourceRoot).isAbsolute())
            throw new IllegalArgumentException("sourceRoot must be an absolute local path");
        if (config.captureConfigService && !options.containsKey("--services") && !options.containsKey("--url")) {
            Service previous = config.services.get(0);
            config.services = new ArrayList<>(java.util.Collections.singletonList(
                    new Service(previous.name, config.upstream, config.sourceRoot, previous.dependencyAliases)));
        }
        if (config.services == null) {
            config.services = new ArrayList<>();
            config.services.add(new Service("default", config.upstream, config.sourceRoot));
        }
        return config;
    }
    private void load(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > 65536)
                throw new IllegalArgumentException("Configuration must be a JSON file of at most 64 KiB");
            JsonNode doc = Json.parse(Files.readAllBytes(path));
            if (!doc.isObject()) throw new IllegalArgumentException("Configuration must be an object");
            JsonNode viewer = doc.path("viewer");
            if (!viewer.isMissingNode() && !viewer.isObject())
                throw new IllegalArgumentException("viewer must be an object");
            if (viewer.isObject()) {
                Set<String> allowed = new HashSet<>(Arrays.asList("upstream", "sourceRoot", "port", "services",
                        "snapshotDir", "captureDir", "viewerUrl", "namespace", "mcpExposeSql"));
                viewer.fieldNames().forEachRemaining(key -> {
                    if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown viewer setting: " + key);
                });
                upstream = configText(viewer, "upstream", upstream);
                sourceRoot = configText(viewer, "sourceRoot", sourceRoot);
                viewerUrl = configText(viewer, "viewerUrl", viewerUrl);
                if (viewer.has("namespace")) {
                    viewerNamespace = configText(viewer, "namespace", null);
                    if (!viewerNamespace.matches("[0-9a-f]{64}"))
                        throw new IllegalArgumentException("viewer.namespace must contain 64 lowercase hexadecimal characters");
                }
                if (viewer.has("port")) {
                    if (!viewer.path("port").isIntegralNumber()) throw new IllegalArgumentException("viewer.port must be an integer");
                    port = parsePort(viewer.path("port").asText());
                }
                if (viewer.has("mcpExposeSql")) {
                    if (!viewer.path("mcpExposeSql").isBoolean()) throw new IllegalArgumentException("viewer.mcpExposeSql must be true or false");
                    mcpExposeSql = viewer.path("mcpExposeSql").booleanValue();
                }
                if (viewer.has("snapshotDir")) snapshotDir = absolute(viewer, "snapshotDir");
                if (viewer.has("captureDir")) captureDir = absolute(viewer, "captureDir");
                if (viewer.has("services")) {
                    // Same validation path without writing a second registry file.
                    services = parseServices(viewer.get("services"));
                    federated = true;
                }
            }
            if (services == null) {
                services = new ArrayList<>();
                services.add(new Service(Json.text(doc, "applicationId", "default"), upstream, sourceRoot));
                // Names from the capture config are preserved in request and source views.
                federated = true;
                captureConfigService = true;
            }
        } catch (IOException exc) { throw new IllegalArgumentException("Could not read local TraceLens configuration"); }
    }
    private static List<Service> parseServices(JsonNode rows) {
        if (!rows.isArray() || rows.size() < 1 || rows.size() > 32)
            throw new IllegalArgumentException("viewer.services requires 1–32 services");
        List<Service> result = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!row.isObject()) throw new IllegalArgumentException("Each service must be an object");
            if (row.has("sourceRoot") && !row.path("sourceRoot").isNull() && !row.path("sourceRoot").isTextual())
                throw new IllegalArgumentException("Service sourceRoot must be a path or null");
            result.add(new Service(Json.text(row, "name"), Json.text(row, "url"), Json.text(row, "sourceRoot"),
                    Dependencies.validateAliases(row.has("dependencyAliases") ? row.get("dependencyAliases") : Json.obj())));
        }
        new Federation(result); // Duplicate identities/endpoints must fail before serving.
        return result;
    }
    private static String configText(JsonNode doc, String key, String fallback) {
        if (!doc.has(key)) return fallback;
        String value = Json.text(doc, key);
        if (value == null || value.length() > 4096 || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid viewer." + key);
        return value;
    }
    private static Path absolute(JsonNode doc, String key) {
        Path value = Paths.get(configText(doc, key, null));
        if (!value.isAbsolute()) throw new IllegalArgumentException("viewer." + key + " must be absolute");
        return value;
    }
    private static int parsePort(String value) {
        try {
            int result = Integer.parseInt(value);
            if (result < 0 || result > 65535) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException exc) { throw new IllegalArgumentException("Port must be an integer from 0 to 65535"); }
    }
}
