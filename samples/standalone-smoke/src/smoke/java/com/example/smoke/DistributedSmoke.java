package com.example.smoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Real service-to-service tests: Java 8/Tomcat 7 calls Java 21/Tomcat 10 over TCP. */
public final class DistributedSmoke {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String[] args;
    private final Path temp;
    private final String disallowedUrl;
    private final List<Service> services = new ArrayList<>();
    private int instance;

    private DistributedSmoke(String[] args, Path temp, String disallowedUrl) {
        this.args = args;
        this.temp = temp;
        this.disallowedUrl = disallowedUrl;
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("tracelens-distributed-smoke-");
        HttpServer disallowed = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        disallowed.createContext("/sink", exchange -> {
            String parent = exchange.getRequestHeaders().getFirst("traceparent");
            String state = exchange.getRequestHeaders().getFirst("tracestate");
            byte[] body = ("traceparent=" + (parent == null ? "none" : parent)
                    + "\ntracestate=" + (state == null ? "none" : state)).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (java.io.OutputStream output = exchange.getResponseBody()) { output.write(body); }
        });
        disallowed.start();
        try {
            DistributedSmoke smoke = new DistributedSmoke(args, temp,
                    "http://127.0.0.1:" + disallowed.getAddress().getPort() + "/sink");
            try {
                smoke.bothAgents();
                smoke.partialTopology(true, false);
                smoke.partialTopology(false, true);
                smoke.partialTopology(false, false);
            } catch (Exception | Error failure) {
                for (Service service : smoke.services) {
                    synchronized (service.log) { System.err.println(service.log); }
                }
                throw failure;
            }
        } finally {
            disallowed.stop(0);
            DistributedService.deleteTree(temp);
        }
        System.out.println("DISTRIBUTED_SMOKE_OK Java8caller=Tomcat7 Java21receiver=Tomcat10 topologies=4");
    }

    private void bothAgents() throws Exception {
        try (Service receiver = start(false, true, null); Service caller = start(true, true, receiver)) {
            Response get = request(caller.url + "/api/gateway/get", "GET", headers(
                    "tracelens-user", "alice_42", "tracelens-id", "private-label",
                    "tracelens-action-id", "private-action", "tracelens-session-id", "private-session"), null);
            verifyLink(caller, receiver, get, "GET", 200);
            for (String field : Arrays.asList("user", "action", "session", "label")) {
                require(field(get.body, field).equals("none"), "identity/context header escaped into downstream request: " + field);
            }
            JsonNode receiverTrace = receiver.export(get.downstreamId);
            require(receiverTrace.path("user").asText().equals("anonymous"), "downstream inherited caller identity");
            String traceId = receiverTrace.path("traceId").asText();
            require(caller.debug("/find-by-trace-id/" + traceId).path("count").asInt() == 1,
                    "caller cannot discover local segment by distributed trace ID");
            require(receiver.debug("/find-by-trace-id/" + traceId).path("count").asInt() == 1,
                    "receiver cannot discover local segment by distributed trace ID");

            String payload = "request-body-secret";
            Response post = request(caller.url + "/api/gateway/post", "POST", Collections.<String, String>emptyMap(), payload);
            verifyLink(caller, receiver, post, "POST", 200);
            require(field(post.body, "bodyLength").equals(Integer.toString(payload.length())), "instrumentation consumed POST body");
            require(!caller.export(post.traceId).toString().contains(payload), "outbound call stored request body");

            Response failed = request(caller.url + "/api/gateway/fail", "GET", Collections.<String, String>emptyMap(), null);
            verifyLink(caller, receiver, failed, "GET", 500);

            Response timeout = request(caller.url + "/api/gateway/slow", "GET", Collections.<String, String>emptyMap(), null);
            require(timeout.code == 504 && timeout.body.equals("TIMEOUT"), "HTTP client timeout behavior changed");
            JsonNode timedCall = oneCall(caller.export(timeout.traceId));
            require(timedCall.path("errorType").asText().endsWith("SocketTimeoutException"), "timeout cause missing from call");

            Response redirect = request(caller.url + "/api/gateway/redirect", "GET", Collections.<String, String>emptyMap(), null);
            require(redirect.code == 200 && field(redirect.body, "traceparent").equals("none")
                    && field(redirect.body, "tracestate").equals("none"), "redirect leaked W3C context to unallowlisted destination");
            Response disallowed = request(caller.url + "/api/gateway/disallowed", "GET", Collections.<String, String>emptyMap(), null);
            require(disallowed.code == 200 && field(disallowed.body, "traceparent").equals("none"), "unallowlisted origin received W3C context");

            String existing = "00-11111111111111111111111111111111-2222222222222222-01";
            Response preserved = request(caller.url + "/api/gateway/get", "GET", headers("X-Fixture-Existing", existing), null);
            require(field(preserved.body, "traceparent").equals(existing), "agent overwrote application's existing traceparent");
            require(field(preserved.body, "tracestate").equals("fixture=preserved"), "agent overwrote existing tracestate");
            JsonNode continued = receiver.export(preserved.downstreamId);
            require(continued.path("traceId").asText().equals("11111111111111111111111111111111")
                    && continued.path("parentSpanId").asText().equals("2222222222222222"), "valid incoming W3C context was not continued");

            Response malformed = request(receiver.url + "/api/receiver/get", "GET",
                    headers("traceparent", "00-00000000000000000000000000000000-0000000000000000-01"), null);
            JsonNode fresh = receiver.export(malformed.traceId);
            require(hex(fresh.path("traceId").asText(), 32) && !fresh.path("traceId").asText().matches("0+"),
                    "invalid incoming context did not create a fresh valid root");
            require(fresh.path("parentSpanId").asText().isEmpty(), "invalid context acquired a parent span");

            ExecutorService requests = Executors.newFixedThreadPool(2);
            try {
                List<Future<Response>> pending = new ArrayList<>();
                for (int index = 0; index < 2; index++) pending.add(requests.submit(() ->
                        request(caller.url + "/api/gateway/get", "GET", Collections.<String, String>emptyMap(), null)));
                Set<String> traces = new HashSet<>();
                Set<String> segments = new HashSet<>();
                for (Future<Response> pendingRequest : pending) {
                    Response response = pendingRequest.get(10, TimeUnit.SECONDS);
                    verifyLink(caller, receiver, response, "GET", 200);
                    traces.add(caller.export(response.traceId).path("traceId").asText());
                    segments.add(response.traceId);
                    segments.add(response.downstreamId);
                }
                require(traces.size() == 2 && segments.size() == 4, "concurrent requests shared trace/span state");
            } finally {
                requests.shutdownNow();
            }
            verifyUnretainedProbe(caller);
            verifyUnretainedProbe(receiver);
        }
    }

    private void verifyUnretainedProbe(Service service) throws Exception {
        Response first = request(service.url + "/probe", "GET", Collections.<String, String>emptyMap(), null);
        Response second = request(service.url + "/probe", "GET", Collections.<String, String>emptyMap(), null);
        // Excluded routes still receive propagation-only context. Each boundary must
        // create a new root, and neither request should be stored as a local trace.
        require(hex(first.body, 32) && hex(second.body, 32) && !first.body.equals(second.body)
                        && first.traceId != null && second.traceId != null && !first.traceId.equals(second.traceId),
                "excluded request reused or leaked an earlier request's context");
        require(service.debug("/find-by-trace-id/" + first.body).path("count").asInt() == 0
                        && service.debug("/find-by-trace-id/" + second.body).path("count").asInt() == 0,
                "excluded propagation-only request was unexpectedly retained");
    }

    private void partialTopology(boolean callerAgent, boolean receiverAgent) throws Exception {
        try (Service receiver = start(false, receiverAgent, null); Service caller = start(true, callerAgent, receiver)) {
            Response response = request(caller.url + "/api/gateway/get", "GET", Collections.<String, String>emptyMap(), null);
            require(response.code == 200 && field(response.body, "method").equals("GET"), "partial installation changed application behavior");
            require((response.traceId != null) == callerAgent, "caller tracing presence does not match installation");
            require((response.downstreamId != null) == receiverAgent, "receiver tracing presence does not match installation");
            if (callerAgent) {
                JsonNode local = caller.export(response.traceId);
                require(oneCall(local).path("httpStatus").asInt() == 200, "caller-only installation lost outbound evidence");
                require(field(response.body, "traceparent").startsWith("00-"), "caller did not propagate to uninstrumented receiver");
            } else {
                require(field(response.body, "traceparent").equals("none"), "uninstrumented caller invented propagation headers");
            }
            if (receiverAgent) {
                JsonNode local = receiver.export(response.downstreamId);
                require(hex(local.path("traceId").asText(), 32) && local.path("parentSpanId").asText().isEmpty(),
                        "receiver-only installation failed to start its own root trace");
            }
        }
    }

    private void verifyLink(Service caller, Service receiver, Response response, String method, int status) throws Exception {
        require(response.code == status && response.traceId != null && response.downstreamId != null,
                "service request or response correlation missing: status=" + response.code + " body=" + response.body);
        JsonNode parent = caller.export(response.traceId);
        JsonNode child = receiver.export(response.downstreamId);
        JsonNode call = oneCall(parent);
        require(hex(parent.path("traceId").asText(), 32), "caller did not create a W3C trace ID");
        require(parent.path("traceId").equals(child.path("traceId")), "cross-process trace IDs differ");
        require(parent.path("traceId").equals(call.path("traceId")), "outbound call has different trace ID");
        require(!parent.path("segmentId").equals(child.path("segmentId")), "different JVMs reused local segment ID");
        java.util.UUID.fromString(parent.path("segmentId").asText());
        java.util.UUID.fromString(child.path("segmentId").asText());
        require(hex(parent.path("spanId").asText(), 16) && hex(child.path("spanId").asText(), 16)
                && hex(call.path("spanId").asText(), 16), "invalid W3C span ID");
        require(call.path("parentSpanId").equals(parent.path("spanId")), "client span not parented to local server span");
        require(child.path("parentSpanId").equals(call.path("spanId")), "downstream parent is not outbound client span");
        require(!child.path("spanId").equals(call.path("spanId")), "client and downstream server reused span ID");
        require(call.path("httpMethod").asText().equals(method) && call.path("httpStatus").asInt() == status,
                "outgoing call method/status missing");
        require(!call.toString().contains("outbound-query-secret") && !call.path("path").asText().contains("?"),
                "outgoing call captured query values");
        require(child.path("attributes").path("httpStatus").asInt() == status, "downstream HTTP status missing");
        require(!parent.path("applicationId").equals(child.path("applicationId")), "service identities not distinguished");
        require(!parent.path("serviceInstanceId").asText().isEmpty() && !child.path("serviceInstanceId").asText().isEmpty(),
                "service instance identity missing");
    }

    private Service start(boolean caller, boolean instrumented, Service downstream) throws Exception {
        int offset = caller ? 1 : 4;
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("applicationId", caller ? "distributed-caller" : "distributed-receiver");
        config.put("applicationVersion", "SMOKE");
        config.put("packages", Arrays.asList("com.example.distributed"));
        config.put("instrumentExecutors", false);
        config.put("instrumentMessaging", false);
        config.put("instrumentScheduled", false);
        config.put("distributedTracingEnabled", true);
        config.put("httpPropagationAllowedDestinations", downstream == null ? Collections.emptyList()
                : Arrays.asList("127.0.0.1:" + downstream.port));
        Path configFile = temp.resolve("service-" + instance++ + ".json");
        Files.write(configFile, JSON.writeValueAsBytes(config));
        List<String> command = new ArrayList<>();
        command.add(args[offset]);
        if (instrumented) {
            command.add("-javaagent:" + args[0] + "=standalone");
            command.add("-Dtracelens.configPath=" + configFile);
            command.add("-Dtracelens.port=0");
            command.add("-Dtracelens.http.includePaths=/api");
        }
        command.add("-Dfixture.downstream=" + (downstream == null ? "" : downstream.url));
        command.add("-Dfixture.redirectTarget=" + disallowedUrl);
        command.add("-cp");
        command.add(args[offset + 1]);
        command.add(DistributedService.class.getName());
        command.add(args[offset + 2]);
        command.add(caller ? "javax" : "jakarta");
        command.add(Boolean.toString(instrumented));
        Service service = new Service(new ProcessBuilder(command).redirectErrorStream(true).start());
        services.add(service);
        return service;
    }

    private static JsonNode oneCall(JsonNode trace) {
        require(trace.path("httpCalls").size() == 1, "nested HttpURLConnection methods duplicated/missed call: " + trace.path("httpCalls"));
        return trace.path("httpCalls").get(0);
    }

    private static boolean hex(String value, int length) { return value.matches("[0-9a-f]{" + length + "}"); }
    private static String field(String body, String name) {
        for (String line : body.split("\n")) if (line.startsWith(name + "=")) return line.substring(name.length() + 1);
        throw new AssertionError("response field missing: " + name + " in " + body);
    }
    private static Map<String, String> headers(String... pairs) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) values.put(pairs[index], pairs[index + 1]);
        return values;
    }

    private static Response request(String url, String method, Map<String, String> headers, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        connection.setRequestMethod(method);
        for (Map.Entry<String, String> header : headers.entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
        try {
            if (body != null) {
                connection.setDoOutput(true);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (stream != null) try (InputStream in = stream) {
                byte[] buffer = new byte[4096];
                int length;
                while ((length = in.read(buffer)) != -1) bytes.write(buffer, 0, length);
            }
            return new Response(status, new String(bytes.toByteArray(), StandardCharsets.UTF_8),
                    connection.getHeaderField("X-TraceLens-Id"), connection.getHeaderField("X-Fixture-Downstream-Segment"));
        } finally { connection.disconnect(); }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class Response {
        final int code;
        final String body, traceId, downstreamId;
        Response(int code, String body, String traceId, String downstreamId) {
            this.code = code; this.body = body; this.traceId = traceId; this.downstreamId = downstreamId;
        }
    }

    private static final class Service implements AutoCloseable {
        final Process child;
        final StringBuilder log = new StringBuilder();
        final int port, debugPort;
        final String url;
        Service(Process child) throws Exception {
            this.child = child;
            ArrayBlockingQueue<String> ready = new ArrayBlockingQueue<>(1);
            Thread reader = new Thread(() -> {
                try (BufferedReader output = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = output.readLine()) != null) {
                        synchronized (log) { if (log.length() < 40000) log.append(line).append('\n'); }
                        if (line.startsWith("FIXTURE_READY ")) ready.offer(line);
                    }
                } catch (java.io.IOException ignored) { }
            }, "distributed-child-output");
            reader.setDaemon(true);
            reader.start();
            String marker = ready.poll(30, TimeUnit.SECONDS);
            if (marker == null) {
                close();
                throw new AssertionError("service startup failed: " + log);
            }
            String[] values = marker.split(" ");
            port = Integer.parseInt(values[1]);
            debugPort = Integer.parseInt(values[2]);
            url = "http://127.0.0.1:" + port;
        }
        JsonNode debug(String path) throws Exception {
            require(debugPort > 0, "debug request issued to uninstrumented service");
            Response response = request("http://127.0.0.1:" + debugPort + "/tracelens" + path,
                    "GET", Collections.<String, String>emptyMap(), null);
            require(response.code == 200, "debug request failed: " + response.body + "\n" + log);
            return JSON.readTree(response.body);
        }
        JsonNode export(String segmentId) throws Exception {
            require(segmentId != null, "missing response segment ID");
            for (int attempt = 0; attempt < 100; attempt++) {
                JsonNode trace = debug("/export/" + segmentId + "?evict=false");
                if (trace.has("endedAt")) return trace;
                Thread.sleep(20);
            }
            throw new AssertionError("request trace not completed: " + segmentId);
        }
        @Override public void close() throws Exception {
            try { child.getOutputStream().close(); } catch (java.io.IOException ignored) { }
            if (!child.waitFor(10, TimeUnit.SECONDS)) {
                child.destroy();
                if (!child.waitFor(3, TimeUnit.SECONDS)) child.destroyForcibly();
            }
        }
    }
}
