package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.PrintStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Java 8 entrypoint shared by the viewer, command line and stdio MCP server. */
public final class ToolMain {
    public static final String VERSION = "0.1.1-SNAPSHOT";
    static final String SHARED_STORE_WARNING = "Storage note: do not run Python and Java writers against the same capture/snapshot directories; "
            + "stop Python writers or choose separate --capture-dir/--snapshot-dir.";
    private ToolMain() {}

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) System.exit(status);
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (hasSwitch(args, "--version")) { out.println("tracelens " + VERSION); return 0; }
            if (args.length == 0 || hasSwitch(args, "--help") || hasSwitch(args, "-h")) {
                out.println(help()); return 0;
            }
            if ("setup".equals(args[0])) return setup(args, err);
            ToolConfig config = ToolConfig.fromArgs(args);
            Arguments parsed = new Arguments(config.remaining);
            return dispatch(config, parsed, out, err);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return 0;
        } catch (Exception error) {
            err.println("error: " + error.getMessage()); return 1;
        }
    }

    private static int setup(String[] args, PrintStream err) throws Exception {
        int port = 7782;
        boolean browser = true, portSeen = false;
        for (int i = 1; i < args.length; i++) {
            if ("--no-browser".equals(args[i])) browser = false;
            else if ("--port".equals(args[i]) && !portSeen && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]); portSeen = true;
            } else throw new IllegalArgumentException("Use setup [--port 7782] [--no-browser]");
        }
        java.nio.file.Path jar = java.nio.file.Paths.get(ToolMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!java.nio.file.Files.isRegularFile(jar)) throw new IllegalArgumentException("Run setup from the combined tracelens.jar, not a class directory");
        final SetupServer server = new SetupServer(port, jar);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "tracelens-setup-shutdown"));
        server.start();
        err.println("TraceLens configuration dashboard: " + server.url());
        err.println("Local setup session expires in " + SetupServer.SESSION_MINUTES
                + " minutes. Review changes in your browser; Ctrl+C closes setup.");
        if (browser) try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE))
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(server.url()));
        } catch (Exception ignored) { /* The printed local URL is the portable fallback. */ }
        server.await();
        return 0;
    }

    private static boolean hasSwitch(String[] args, String flag) {
        for (String arg : args) {
            // A label/query after the option terminator is data, even if it is "--help".
            if ("--".equals(arg)) return false;
            if (flag.equals(arg)) return true;
        }
        return false;
    }

    private static int dispatch(ToolConfig config, Arguments args, PrintStream out, PrintStream err) throws Exception {
        String cmd = args.position(0, "command");
        if (!COMMANDS.contains(cmd)) throw new IllegalArgumentException("unknown command: " + cmd + " (use --help)");
        validateOptions(cmd, args);
        if (config.federated && Arrays.asList("reset", "enable", "disable", "metadata").contains(cmd))
            throw new IllegalArgumentException("this command requires single-service --url mode; federation is read-only");
        if ("serve".equals(cmd)) {
            final ViewerServer server = new ViewerServer(config);
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() { public void run() { server.close(); } }, "tracelens-viewer-shutdown"));
            server.start();
            err.println("TraceLens viewer: http://127.0.0.1:" + server.port() + "/");
            err.println(SHARED_STORE_WARNING);
            server.await();
            return 0;
        }
        if ("mcp".equals(cmd)) return new McpServer(config).serve(System.in, out, err);
        Federation discovery = new Federation(config.services);
        DebugClient client = new DebugClient(config.upstream);
        String service = args.option("service"), instance = args.option("instance");
        if ("capture".equals(cmd)) {
            CaptureStore store = new CaptureStore(config.captureDir);
            String sub = args.position(1, "capture command");
            if ("list".equals(sub)) print(out, store.list());
            else if ("compare".equals(sub)) print(out, Workflow.compareSavedCaptures(store, args.position(2, "before"), args.position(3, "after")));
            else if ("search".equals(sub)) print(out, CaptureTools.searchSavedEvidence(null, store, args.position(2, "query"),
                    args.option("category"), args.integer("limit", 50)));
            else if ("save".equals(sub)) {
                err.println(SHARED_STORE_WARNING);
                JsonNode result = Workflow.saveRequestCapture(discovery, store, args.position(2, "name"), args.option("revision"), args.requestFilters());
                print(out, result); return result.path("saved").asBoolean() ? 0 : 2;
            } else throw new IllegalArgumentException("unknown capture command: " + sub);
        } else if ("doctor".equals(cmd)) {
            JsonNode result = Doctor.runDoctor(discovery); print(out, result); return result.path("ready").asBoolean() ? 0 : 1;
        } else if ("investigate".equals(cmd)) {
            JsonNode result = Investigation.investigateRequest(discovery, args.requestFilters()); print(out, result);
            return "selected".equals(result.path("status").asText()) ? 0 : 2;
        } else if ("diagnose".equals(cmd)) {
            ObjectNode filters = args.filters("service", "method", "path", "user", "site");
            if (!filters.hasNonNull("path")) throw new IllegalArgumentException("diagnose requires --path");
            print(out, GuidedInvestigation.diagnoseRequest(discovery, filters));
        } else if ("summary".equals(cmd)) print(out, config.federated ? discovery.summary() : client.summary());
        else if ("list".equals(cmd)) printTraces(out, err, config.federated ? discovery.traces(Json.obj()) : client.traces());
        else if ("export".equals(cmd) || "render".equals(cmd)) {
            String id = args.position(1, "segment id");
            JsonNode trace = config.federated ? discovery.export(id, service, instance) : checkedExport(client, id, service, instance);
            if ("export".equals(cmd)) print(out, trace);
            else {
                String root = renderSourceRoot(config, discovery, trace);
                out.println(Renderer.render(trace, root, !args.flag("no-source") && root != null, !args.flag("full")));
            }
        } else if ("find".equals(cmd)) {
            String label = args.position(1, "lens id");
            printTraces(out, err, config.federated ? discovery.findByLensId(label) : client.findByLensId(label));
        } else if ("find-distributed".equals(cmd)) {
            ObjectNode filters = args.filters("service", "method", "path", "user");
            filters.put("since_minutes", args.number("since-minutes", 30));
            filters.put("limit", args.integer("limit", 100));
            print(out, discovery.traces(filters));
        } else if ("get-distributed".equals(cmd)) print(out, discovery.getDistributedTrace(args.position(1, "trace id")));
        else if ("tail".equals(cmd)) {
            double interval = args.number("interval", 2);
            if (!Double.isFinite(interval) || interval <= 0 || interval > 3600) throw new IllegalArgumentException("interval must be > 0 and <= 3600 seconds");
            out.println("watching for traces (Ctrl-C to stop)...");
            Set<String> seen = new HashSet<String>();
            while (!Thread.currentThread().isInterrupted()) {
                JsonNode document = config.federated ? discovery.traces(Json.obj()) : client.traces();
                for (JsonNode trace : Json.rows(document, "traces")) {
                    String id = Json.text(trace, "traceId");
                    String identity = Json.text(trace, "segmentKey", Json.stringify(Json.arr(trace.path("service"), trace.path("serviceInstanceId"), trace.path("segmentId"), trace.path("traceId"))));
                    if (id != null && seen.add(identity)) out.println(Json.text(trace, "entryChannel", "?") + " " + id.substring(0, Math.min(id.length(), 8))
                            + "… service=" + Json.text(trace, "service", "-") + " instance=" + Json.text(trace, "serviceInstanceId", "-")
                            + " lens=" + Json.text(trace, "lensId", "?") + " user=" + Json.text(trace, "user", "?")
                            + " events=" + trace.path("eventCount").asInt() + " status=" + Json.text(trace, "status", "?"));
                }
                Thread.sleep(Math.max(1, (long) (interval * 1000)));
            }
        } else if ("reset".equals(cmd)) print(out, client.request("POST", "/reset" + (args.positions.size() > 1 ? "/" + encode(args.positions.get(1)) : "")));
        else if ("enable".equals(cmd) || "disable".equals(cmd)) print(out, client.request("POST", "/" + cmd));
        else if ("metadata".equals(cmd)) print(out, client.request("GET", "/metadata"));
        return 0;
    }

    static JsonNode checkedExport(DebugClient client, String id, String service, String instance) {
        if (service != null && !"default".equals(service)) throw new IllegalArgumentException("single-service mode accepts only service='default'");
        JsonNode trace = client.export(id);
        if (instance != null && !instance.equals(Json.text(trace, "serviceInstanceId")))
            throw new ToolException(409, "service instance changed; refresh the trace list");
        return trace;
    }

    static String renderSourceRoot(ToolConfig config, Federation discovery, JsonNode trace) {
        return config.federated ? discovery.sourceRoot(Json.text(trace, "service"))
                : config.sourceRoot == null ? Source.defaultRoot().toString() : config.sourceRoot;
    }

    private static void print(PrintStream out, JsonNode value) throws Exception { out.println(Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value)); }
    private static String encode(String value) throws Exception { return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20"); }
    private static void printTraces(PrintStream out, PrintStream err, JsonNode doc) {
        for (JsonNode error : Json.rows(doc, "errors")) err.println("PARTIAL: " + Json.stringify(error));
        List<JsonNode> traces = Json.rows(doc, "traces");
        if (traces.isEmpty()) { out.println("no traces retained"); return; }
        out.printf("%-38s %-28s %-24s %-5s %-10s %7s %8s SERVICE / INSTANCE%n", "TRACE ID", "LENS ID", "USER", "CH", "STATUS", "EVENTS", "SIZE");
        for (JsonNode t : traces) out.printf(Locale.ROOT, "%-38s %-28.28s %-24.24s %-5s %-10s %7d %8s %s / %s%n",
                Json.text(t, "traceId", "?"), Json.text(t, "lensId", "null"), Json.text(t, "user", "null"), Json.text(t, "entryChannel", "?"),
                Json.text(t, "status", "?"), t.path("eventCount").asInt(), human(t.path("approxSizeBytes").asDouble()),
                Json.text(t, "service", "-"), Json.text(t, "serviceInstanceId", "-"));
    }
    private static String human(double n) {
        return n < 1024 ? (long) n + "B" : n < 1024 * 1024 ? String.format(Locale.ROOT, "%.0fKB", n / 1024) : String.format(Locale.ROOT, "%.1fMB", n / 1048576);
    }

    private static final Set<String> COMMANDS = new HashSet<String>(Arrays.asList("summary", "list", "doctor", "investigate", "diagnose", "capture",
            "export", "render", "find", "find-distributed", "get-distributed", "tail", "serve", "mcp", "reset", "enable", "disable", "metadata"));
    private static void validateOptions(String cmd, Arguments args) {
        Set<String> allowed = new HashSet<String>(Arrays.asList("service", "instance"));
        if ("investigate".equals(cmd) || ("capture".equals(cmd) && args.positions.size() > 1 && "save".equals(args.positions.get(1))))
            allowed.addAll(Arrays.asList("trace-id", "method", "path", "user", "label", "since-minutes", "outcome", "selection"));
        if ("capture".equals(cmd) && args.positions.size() > 1 && "save".equals(args.positions.get(1))) allowed.add("revision");
        if ("diagnose".equals(cmd)) allowed.addAll(Arrays.asList("path", "method", "user", "site"));
        if ("find-distributed".equals(cmd)) allowed.addAll(Arrays.asList("method", "path", "user", "since-minutes", "limit"));
        if ("render".equals(cmd)) allowed.addAll(Arrays.asList("full", "no-source"));
        if ("tail".equals(cmd)) allowed.add("interval");
        if ("capture".equals(cmd) && args.positions.size() > 1 && "search".equals(args.positions.get(1))) allowed.addAll(Arrays.asList("category", "limit"));
        for (String option : args.options.keySet()) if (!allowed.contains(option)) throw new IllegalArgumentException("unrecognized option for " + cmd + ": --" + option);
        int expected = Arrays.asList("export", "render", "find", "get-distributed").contains(cmd) ? 2 : "reset".equals(cmd) ? 2 : 1;
        if ("capture".equals(cmd) && args.positions.size() > 1) expected = "compare".equals(args.positions.get(1)) ? 4 : "list".equals(args.positions.get(1)) ? 2 : 3;
        if (args.positions.size() > expected) throw new IllegalArgumentException("unexpected argument: " + args.positions.get(expected));
    }

    static final class Arguments {
        final List<String> positions = new ArrayList<String>();
        final Map<String, String> options = new LinkedHashMap<String, String>();
        Arguments(List<String> args) {
            boolean positionalOnly = false;
            for (int i = 0; i < args.size(); i++) {
                String value = args.get(i);
                if ("--".equals(value)) { positionalOnly = true; continue; }
                if (!positionalOnly && value.startsWith("--")) {
                    String key = value.substring(2);
                    int equals = key.indexOf('=');
                    String optionValue;
                    if (equals >= 0) {
                        optionValue = key.substring(equals + 1); key = key.substring(0, equals);
                        if ("no-source".equals(key) || "full".equals(key))
                            throw new IllegalArgumentException("--" + key + " does not accept a value");
                    }
                    else if ("no-source".equals(key) || "full".equals(key)) optionValue = "true";
                    else {
                        if (++i >= args.size() || args.get(i).startsWith("--")) throw new IllegalArgumentException("missing value for --" + key);
                        optionValue = args.get(i);
                    }
                    if (options.put(key, optionValue) != null) throw new IllegalArgumentException("duplicate option --" + key);
                } else positions.add(value);
            }
        }
        String position(int index, String label) { if (index >= positions.size()) throw new IllegalArgumentException("missing " + label); return positions.get(index); }
        String option(String key) { return options.get(key); }
        boolean flag(String key) { return "true".equals(option(key)); }
        int integer(String key, int fallback) { try { return options.containsKey(key) ? Integer.parseInt(option(key)) : fallback; } catch (NumberFormatException e) { throw new IllegalArgumentException("--" + key + " must be an integer"); } }
        double number(String key, double fallback) { try { double value = options.containsKey(key) ? Double.parseDouble(option(key)) : fallback; if (!Double.isFinite(value)) throw new NumberFormatException(); return value; } catch (NumberFormatException e) { throw new IllegalArgumentException("--" + key + " must be a finite number"); } }
        ObjectNode filters(String... keys) { ObjectNode result = Json.obj(); for (String key : keys) if (options.containsKey(key)) result.put(key.replace('-', '_'), option(key)); return result; }
        ObjectNode requestFilters() {
            ObjectNode result = filters("service", "method", "path", "user", "label", "trace-id", "outcome", "selection");
            result.put("since_minutes", number("since-minutes", 10));
            if (!result.has("outcome")) result.put("outcome", "all");
            if (!result.has("selection")) result.put("selection", "unique");
            return result;
        }
    }

    private static String help() {
        return "TraceLens " + VERSION + " — Java 8+ viewer, CLI and MCP (one JAR)\n"
                + "Usage: java -jar tracelens.jar [connection options] COMMAND [options]\n\n"
                + "  setup [--port 7782] [--no-browser]    Local guided installation (review before apply)\n"
                + "  serve [--port 7781]                   Local web viewer, bound to 127.0.0.1\n"
                + "  mcp [--viewer-url ORIGIN]             Newline-delimited JSON-RPC on stdio\n"
                + "  summary | list | metadata            Agent status, retained traces, config\n"
                + "  doctor                               Read-only setup checks\n"
                + "  investigate --path PATH              Find a request (also --label/--trace-id)\n"
                + "  diagnose --path /exact/backend/path  Read-only capture-eligibility diagnosis\n"
                + "  export SEGMENT_ID                    Non-evicting JSON evidence export\n"
                + "  render SEGMENT_ID [--no-source] [--full]  Source-aware event timeline\n"
                + "  find LABEL                           Find retained segments by label\n"
                + "  find-distributed [--method GET] [--path PATH] [--user USER]\n"
                + "  get-distributed TRACE_ID             Joined observed service segments\n"
                + "  capture save NAME [--path PATH] [--revision LABEL]\n"
                + "  capture list | capture compare BEFORE AFTER\n"
                + "  capture search QUERY [--category class|method|exception|destination]\n"
                + "  tail [--interval 2]                  Watch for new traces until Ctrl-C\n"
                + "  reset [SEGMENT_ID] | enable | disable  Explicit single-service mutations\n\n"
                + "Connection: --url URL (default http://127.0.0.1:7778), or --services FILE\n"
                + "           --base-url and --services-file are equivalent aliases.\n"
                + "Settings: --config tracelens.json (optional viewer section), --source-root ABS_DIR,\n"
                + "          --capture-dir DIR, --snapshot-dir DIR, --service NAME, --instance ID\n"
                + "Single-service render/MCP source default: TRACELENS_SOURCE_ROOT or ~/work/cb-ems.\n"
                + "Federated sources must be configured per service; --no-source disables rendering.\n"
                + "Request filters: --method, --path, --user, --label, --since-minutes (default 10),\n"
                + "                 --outcome all|error|success|skipped|active|unknown,\n"
                + "                 --selection unique|latest (unique never guesses).\n"
                + "Distributed search defaults: --since-minutes 30 --limit 100 (max 10000).\n"
                + "Diagnose exact filters: --method METHOD --user USER --site SITE.\n"
                + "Capture search: --limit 50 (1–100), --category class|method|exception|destination.\n"
                + "Federation is read-only. Captures are explicit minimized local evidence.\n"
                + "Agent use remains: java -javaagent:tracelens.jar=standalone\n"
                + "                       -Dtracelens.configPath=/absolute/tracelens.json ...";
    }
}
