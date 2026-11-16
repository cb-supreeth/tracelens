package com.chargebee.tracelens.compat;

import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Comparator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Executes the delivered shaded JAR in real Java 8, without a Python runtime or service. */
public final class Java8ToolSmoke {
    public static void main(String[] args) throws Exception {
        check(System.getProperty("java.specification.version").equals("1.8"), "this smoke must run on Java 8");
        check(args.length == 1 && Files.isRegularFile(Paths.get(args[0])), "supply the built shaded JAR path");
        // Java 8's HTTP client otherwise silently strips the Origin needed by
        // the viewer's same-origin write protection. All requests stay loopback.
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
        Path temporary = Files.createTempDirectory("tracelens-java8-tool-").toRealPath();
        try {
            Result version = run(args[0], "", "--version");
            check(version.code == 0 && version.out.startsWith("tracelens ") && version.err.isEmpty(), "--version failed");
            Result help = run(args[0], "", "--help");
            check(help.code == 0 && help.out.contains("--config") && help.out.contains("mcp") && help.out.contains("=standalone"), "--help failed");
            String input = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"extensions\":{\"io.modelcontextprotocol/ui\":{\"mimeTypes\":[\"text/html;profile=mcp-app\"]}}}}}\n"
                    + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
                    + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}\n"
                    + "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/read\",\"params\":{\"uri\":\"ui://tracelens/request-flow.html\"}}\n"
                    + "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"list_request_captures\",\"arguments\":{}}}\n"
                    + "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"ping\",\"params\":{}}\n";
            Result mcp = run(args[0], input, "--capture-dir", temporary.resolve("captures").toString(), "mcp");
            check(mcp.code == 0 && mcp.err.contains("mcp server ready"), "MCP launch failed");
            String[] lines = mcp.out.trim().split("\n");
            check(lines.length == 5, "notifications must not produce responses");
            for (int i = 0; i < lines.length; i++) {
                check(lines[i].startsWith("{\"jsonrpc\":\"2.0\",\"id\":" + (i + 1) + ","), "stdout must contain only ordered JSON-RPC responses");
                check(!lines[i].contains("\"error\":") && !lines[i].contains("\"isError\":true"), "MCP returned an error");
            }
            check(lines[0].contains("\"protocolVersion\":\"2025-06-18\"") && lines[0].contains("io.modelcontextprotocol/ui"), "protocol/UI negotiation failed");
            for (String tool : Arrays.asList("tracelens_doctor", "investigate_request", "diagnose_capture_request", "save_request_capture",
                    "list_request_captures", "compare_request_captures", "search_saved_captures", "get_segment_execution",
                    "list_error_groups", "get_error_group", "get_trace_visualization", "show_trace_visualization",
                    "find_distributed_traces", "get_distributed_trace", "find_actions", "get_action", "list_flows",
                    "get_flow", "get_flow_summary", "get_flow_mermaid", "search_flows", "flow_status"))
                check(lines[1].contains("\"name\":\"" + tool + "\""), "missing tool " + tool);
            check(lines[2].contains("connect-src 'none'") && lines[2].contains("request-graph"), "offline bundled app missing");
            check(lines[3].contains("\"captures\":[]") && !Files.exists(temporary.resolve("captures")), "offline listing wrote state");
            Result unknown = run(args[0], "", "not-a-command");
            check(unknown.code == 1 && unknown.out.isEmpty() && unknown.err.contains("unknown command"), "CLI error contract failed");
            viewer(args[0], temporary);
            System.out.println("Java 8 tooling smoke passed: shaded JAR CLI, all 22 MCP tools, bundled app, viewer HTTP/assets/source/execution and isolated snapshot lifecycle.");
        } finally {
            // Only this generated fixture tree is removed, after every exact
            // child process and localhost fixture server has been stopped.
            try (Stream<Path> paths = Files.walk(temporary)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
    }

    private static void viewer(String jar, Path temporary) throws Exception {
        Path checkout = temporary.resolve("checkout");
        Path source = checkout.resolve("src/main/java/smoke/Example.java");
        Files.createDirectories(source.getParent());
        Files.write(source, "package smoke;\nclass Example { void run() {} }\n".getBytes(StandardCharsets.UTF_8));
        final String traceId = "0123456789abcdef0123456789abcdef";
        final String segmentId = "11111111-1111-1111-1111-111111111111";
        final String events = "["
                + "{\"seq\":1,\"type\":\"METHOD_ENTER\",\"className\":\"smoke.Example\",\"methodName\":\"run\",\"thread\":\"fixture\",\"nanoTime\":1000000},"
                + "{\"seq\":2,\"type\":\"LINE\",\"className\":\"smoke.Example\",\"line\":2,\"thread\":\"fixture\",\"nanoTime\":2000000},"
                + "{\"seq\":3,\"type\":\"METHOD_EXIT\",\"className\":\"smoke.Example\",\"methodName\":\"run\",\"thread\":\"fixture\",\"nanoTime\":3000000}]";
        final String trace = "{\"traceId\":\"" + traceId + "\",\"segmentId\":\"" + segmentId
                + "\",\"service\":\"app\",\"serviceInstanceId\":\"fixture-instance\",\"entryChannel\":\"HTTP\","
                + "\"status\":\"COMPLETED\",\"httpMethod\":\"GET\",\"path\":\"/smoke\",\"httpStatus\":200,"
                + "\"eventCount\":3,\"events\":" + events + "}";
        AtomicInteger exports = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/tracelens", exchange -> {
            String target = exchange.getRequestURI().toString();
            String response;
            int status = 200;
            if ("GET".equals(exchange.getRequestMethod()) && target.equals("/tracelens/export/" + segmentId + "?evict=false")) {
                exports.incrementAndGet();
                response = trace;
            } else {
                unexpected.incrementAndGet();
                response = "{\"error\":\"unexpected synthetic-agent request\"}";
                status = 400;
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        upstream.start();
        Process process = null;
        ExecutorService readers = Executors.newFixedThreadPool(2);
        StringBuilder errors = new StringBuilder();
        try {
            List<String> command = new ArrayList<String>();
            command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
            command.addAll(Arrays.asList("-jar", jar, "--url", "http://127.0.0.1:" + upstream.getAddress().getPort(),
                    "--source-root", checkout.toString(), "--capture-dir", temporary.resolve("viewer-captures").toString(),
                    "--snapshot-dir", temporary.resolve("viewer-snapshots").toString(), "serve", "--port", "0"));
            process = new ProcessBuilder(command).start();
            process.getOutputStream().close();
            final Process child = process;
            BlockingQueue<String> ready = new LinkedBlockingQueue<String>();
            Future<String> stdout = readers.submit(() -> read(child.getInputStream()));
            Future<?> stderr = readers.submit(() -> {
                try (BufferedReader lines = new BufferedReader(new InputStreamReader(child.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null) {
                        synchronized (errors) {
                            if (errors.length() > 2 * 1024 * 1024) throw new AssertionError("unexpected viewer output size");
                            errors.append(line).append('\n');
                        }
                        if (line.startsWith("TraceLens viewer: http://127.0.0.1:")) ready.offer(line);
                    }
                } catch (Exception failure) { throw new RuntimeException(failure); }
            });
            String startup = ready.poll(15, TimeUnit.SECONDS);
            check(startup != null, "Java 8 viewer did not start: " + errors);
            String origin = startup.substring("TraceLens viewer: ".length()).replaceAll("/$", "");
            check(origin.matches("http://127\\.0\\.0\\.1:[0-9]+"), "unexpected viewer address");
            check(request(origin, "GET", "/", null, 200).contains("/static/app.js"), "bundled HTML missing");
            check(request(origin, "GET", "/static/trace-graph.js", null, 200).contains("TraceLensGraph"), "bundled graph asset missing");
            check(request(origin, "GET", "/api/source-location?className=smoke.Example&line=2", null, 200)
                    .contains("\"sourceCorrespondence\":\"unverified\""), "Java 8 source lookup failed");
            request(origin, "GET", "/api/source-location?className=smoke.Example&line=3", null, 404);
            check(request(origin, "GET", "/api/render-data/" + segmentId, null, 200)
                    .contains("\"sourceLine\":\"class Example { void run() {} }\""), "Java 8 source enrichment failed");
            check(exports.get() == 1 && unexpected.get() == 0, "export must remain non-evicting and correctly scoped");
            String execution = request(origin, "POST", "/api/segment-execution", "{\"trace\":" + trace + ",\"maxNodes\":200}", 200);
            check(execution.contains("\"kind\":\"segment-execution\"") && execution.contains("\"outcome\":\"returned\"")
                    && execution.contains("\"complete\":true") && execution.contains("\"durationMs\":2.0"), "Java 8 execution reconstruction failed");
            check(request(origin, "GET", "/api/snapshots", null, 200).contains("\"count\":0"), "isolated snapshot store is not empty");
            String input = "{\"snapshot\":{\"name\":\"Java 8 smoke\",\"traceId\":\"" + traceId + "\","
                    + "\"request\":{\"traceId\":\"" + traceId + "\",\"services\":[\"app\"]},"
                    + "\"journey\":{\"traceId\":\"" + traceId + "\",\"segments\":[" + trace + "]},"
                    + "\"segments\":[" + trace + "]}}";
            String saved = request(origin, "POST", "/api/snapshots", input, 201);
            Matcher idMatch = Pattern.compile("\"id\":\"([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})\"").matcher(saved);
            check(idMatch.find(), "snapshot save did not return an ID");
            String id = idMatch.group(1);
            check(request(origin, "GET", "/api/snapshots/" + id, null, 200).equals(saved), "saved snapshot round-trip changed evidence");
            check(request(origin, "GET", "/api/snapshots", null, 200).contains("\"count\":1"), "saved snapshot not listed");
            request(origin, "POST", "/api/snapshots/" + id + "/rename", "{\"name\":\"Renamed Java 8 smoke\"}", 200);
            check(request(origin, "GET", "/api/snapshots/" + id, null, 200).contains("\"name\":\"Renamed Java 8 smoke\""), "rename not persisted");
            check(request(origin, "DELETE", "/api/snapshots/" + id, null, 200).contains("\"deleted\":true"), "snapshot delete failed");
            request(origin, "GET", "/api/snapshots/" + id, null, 404);
            check(request(origin, "GET", "/api/snapshots", null, 200).contains("\"count\":0"), "deleted snapshot still listed");
            check(exports.get() == 1 && unexpected.get() == 0, "offline execution/snapshot operations unexpectedly contacted the agent");
            stop(child);
            check(stdout.get(5, TimeUnit.SECONDS).isEmpty(), "viewer wrote unexpected stdout");
            stderr.get(5, TimeUnit.SECONDS);
        } finally {
            try {
                if (process != null) stop(process);
            } finally {
                readers.shutdownNow();
                upstream.stop(0);
            }
        }
    }

    private static String request(String origin, String method, String path, String body, int expectedStatus) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(origin + path).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Origin", origin);
        try {
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String response = stream == null ? "" : read(stream);
            check(status == expectedStatus, method + " " + path + " returned " + status + ": " + response);
            check("no-store".equals(connection.getHeaderField("Cache-Control")), "viewer response must not be cached");
            return response;
        } finally { connection.disconnect(); }
    }

    private static void stop(Process process) throws Exception {
        if (process.isAlive()) process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            check(process.waitFor(5, TimeUnit.SECONDS), "exact spawned process could not be stopped");
        }
    }

    private static Result run(String jar, String input, String... args) throws Exception {
        List<String> command = new ArrayList<String>();
        command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-jar"); command.add(jar); command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).start();
        ExecutorService readers = Executors.newFixedThreadPool(2);
        try {
            Future<String> stdout = readers.submit(() -> read(process.getInputStream()));
            Future<String> stderr = readers.submit(() -> read(process.getErrorStream()));
            process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("tool process timed out"); }
            return new Result(process.exitValue(), stdout.get(5, TimeUnit.SECONDS), stderr.get(5, TimeUnit.SECONDS));
        } finally {
            process.destroy();
            readers.shutdownNow();
        }
    }
    private static String read(InputStream stream) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        try (InputStream input = stream) {
            while ((count = input.read(buffer)) != -1) {
                bytes.write(buffer, 0, count);
                if (bytes.size() > 2 * 1024 * 1024) throw new AssertionError("unexpected tool output size");
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static final class Result {
        final int code; final String out, err;
        Result(int code, String out, String err) { this.code = code; this.out = out; this.err = err; }
    }
}
