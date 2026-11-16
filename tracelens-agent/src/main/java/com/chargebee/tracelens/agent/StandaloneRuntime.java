package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.AgentStatus;
import com.chargebee.tracelens.bridge.HttpTraceRuntime;
import com.chargebee.tracelens.bridge.HttpClientTraceRuntime;
import com.chargebee.tracelens.bridge.ApacheHttpClientTraceRuntime;
import com.chargebee.tracelens.bridge.VertxHttpTraceRuntime;
import com.chargebee.tracelens.bridge.W3CTraceContext;
import com.chargebee.tracelens.bridge.TraceRuntime;
import com.chargebee.tracelens.bridge.TraceSnapshot;
import com.chargebee.tracelens.core.config.TraceLensConfig;
import com.chargebee.tracelens.core.config.TraceLensConfigLoader;
import com.chargebee.tracelens.core.export.TraceExporter;
import com.chargebee.tracelens.core.export.TraceProjection;
import com.chargebee.tracelens.core.record.TraceLifecycle;
import com.chargebee.tracelens.core.record.TraceSink;
import com.chargebee.tracelens.core.record.Tracer;
import com.chargebee.tracelens.core.store.TraceStore;
import com.chargebee.tracelens.core.store.TraceSummary;
import com.chargebee.tracelens.core.trace.EntryChannel;
import com.chargebee.tracelens.core.trace.Trace;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent-owned recording and loopback debug API. Everything except the bootstrap
 * bridge is shaded in the agent; no framework or application dependency is needed.
 * One runtime/store is shared by all instrumented applications in the JVM.
 */
public final class StandaloneRuntime implements HttpTraceRuntime.Listener, HttpClientTraceRuntime.Listener {
    private static final String PREFIX = "/tracelens";
    private static StandaloneRuntime instance;

    private final TraceLensConfig config;
    private final List<String> httpIncludePaths;
    private final TraceStore store;
    private final TraceExporter exporter;
    private final TraceRuntime.Sink sink;
    private final TraceLifecycle lifecycle;
    private final String serviceInstanceId = UUID.randomUUID().toString();
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private HttpServer server;
    private ThreadPoolExecutor workers;
    private Thread shutdownHook;

    private StandaloneRuntime(TraceLensConfig config) {
        this.config = config;
        this.httpIncludePaths = Collections.unmodifiableList(new ArrayList<>(config.getHttpIncludePaths()));
        this.store = new TraceStore(config);
        this.exporter = new TraceExporter(config, true);
        this.sink = new TraceSink(store);
        this.lifecycle = new TraceLifecycle(new Tracer(store));
    }

    /** Startup failures disable tracing without failing the host application's startup. */
    public static synchronized boolean start(TraceLensConfig config) {
        if (instance != null) return true;
        StandaloneRuntime starting = null;
        try {
            TraceLensConfigLoader.rejectLegacyHttpIncludePaths();
            // Standalone mode deliberately permits an empty instrumentation scope:
            // HTTP metadata still works, without instrumenting arbitrary libraries.
            if (config == null) throw new IllegalArgumentException("invalid TraceLens configuration");
            List<String> errors = config.validate(true);
            if (!errors.isEmpty()) throw new IllegalArgumentException("invalid TraceLens configuration: " + String.join("; ", errors));
            int requestedPort = Integer.parseInt(System.getProperty("tracelens.port", "7778"));
            if (requestedPort < 0 || requestedPort > 65535) {
                throw new IllegalArgumentException("tracelens.port must be between 0 and 65535");
            }
            starting = new StandaloneRuntime(config);
            starting.openServer(requestedPort);
            synchronized (TraceRuntime.class) {
                if (TraceRuntime.hasSink() || HttpTraceRuntime.hasListener() || VertxHttpTraceRuntime.hasListener()) {
                    throw new IllegalStateException("another TraceLens runtime is already installed");
                }
                TraceRuntime.registerSink(starting.sink);
                TraceRuntime.registerLifecycleListener(starting.lifecycle);
                HttpTraceRuntime.registerListener(starting);
                VertxHttpTraceRuntime.registerListener(starting);
                HttpClientTraceRuntime.registerListener(starting);
                HttpClientTraceRuntime.configure(config.isHttpClientCaptureEnabled(), config.isDistributedTracingEnabled(), config.getHttpPropagationAllowedDestinations());
                ApacheHttpClientTraceRuntime.registerListener(starting);
                ApacheHttpClientTraceRuntime.configure(config.isHttpClientCaptureEnabled(), config.isDistributedTracingEnabled(), config.getHttpPropagationAllowedDestinations());
                com.chargebee.tracelens.bridge.JdbcQueryRuntime.configure(config.isCaptureJdbcQueries(),
                        config.getMaxSqlLength(), config.getMaxSqlQueriesPerRequest(), config.isCaptureJdbcParameters());
                com.chargebee.tracelens.bridge.ClickHouseQueryRuntime.configure(config.isCaptureClickHouseQueries(),
                        config.getMaxSqlLength(), config.getMaxSqlQueriesPerRequest(), config.isCaptureClickHouseParameters());
            }
            starting.store.startSweeper();
            starting.shutdownHook = new Thread(StandaloneRuntime::stop, "tracelens-shutdown");
            Runtime.getRuntime().addShutdownHook(starting.shutdownHook);
            instance = starting;
            System.err.println("[tracelens] standalone debug API: http://127.0.0.1:"
                    + starting.server.getAddress().getPort() + PREFIX);
            return true;
        } catch (Throwable failure) {
            if (starting != null) starting.close();
            System.err.println("[tracelens] standalone runtime disabled: "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            return false;
        }
    }

    public static synchronized int port() {
        return instance == null ? -1 : instance.server.getAddress().getPort();
    }

    public static synchronized void stop() {
        StandaloneRuntime running = instance;
        instance = null;
        if (running != null) {
            running.close();
            com.chargebee.tracelens.bridge.JdbcQueryRuntime.configure(false, 4096, 100);
            com.chargebee.tracelens.bridge.ClickHouseQueryRuntime.configure(false, 4096, 100, false);
        }
    }

    private void openServer(int requestedPort) throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), requestedPort), 16);
        workers = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(32), runnable -> {
                    Thread thread = new Thread(runnable, "tracelens-debug-http");
                    thread.setDaemon(true);
                    return thread;
                });
        server.setExecutor(workers);
        server.createContext(PREFIX, this::handle);

        // HttpServer's dispatcher inherits daemon status from the thread that
        // starts it. TraceLens must not keep an otherwise finished JVM alive.
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread startup = new Thread(() -> {
            try {
                server.start();
            } catch (Throwable problem) {
                failure.set(problem);
            } finally {
                started.countDown();
            }
        }, "tracelens-debug-start");
        startup.setDaemon(true);
        startup.start();
        try {
            if (!started.await(5, TimeUnit.SECONDS)) throw new IOException("debug API startup timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        if (failure.get() != null) throw new IOException("debug API startup failed", failure.get());
    }

    private void close() {
        // A later adapter must not be unregistered when only this runtime closes.
        quietly(() -> HttpTraceRuntime.unregisterListener(this));
        quietly(() -> VertxHttpTraceRuntime.unregisterListener(this));
        quietly(() -> HttpClientTraceRuntime.unregisterListener(this));
        quietly(() -> ApacheHttpClientTraceRuntime.unregisterListener(this));
        quietly(() -> TraceRuntime.unregisterLifecycleListener(lifecycle));
        quietly(() -> TraceRuntime.unregisterSink(sink));
        quietly(store::close);
        if (server != null) quietly(() -> server.stop(0));
        if (workers != null) quietly(() -> workers.shutdownNow());
        if (shutdownHook != null && shutdownHook != Thread.currentThread()) {
            quietly(() -> Runtime.getRuntime().removeShutdownHook(shutdownHook));
        }
    }

    private static void quietly(Runnable action) {
        try { action.run(); } catch (Throwable ignored) { /* never affect host shutdown */ }
    }

    @Override
    public boolean distributedTracingEnabled() { return config.isDistributedTracingEnabled(); }

    @Override
    public List<String> httpIncludePaths() { return httpIncludePaths; }

    @Override public boolean captureHttpQueryParameters() { return config.isCaptureHttpQueryParameters(); }
    @Override public boolean captureHttpRequestBodies() { return config.isCaptureHttpRequestBodies(); }
    @Override public boolean captureHttpDetails(String segmentId) { return store.get(segmentId).isPresent(); }
    @Override public int maxHttpPayloadLength() { return config.getMaxHttpPayloadLength(); }
    @Override public Map<String, String> sanitizeHttpPayload(String input, String format, boolean query) {
        return com.chargebee.tracelens.core.http.HttpPayloadSanitizer.sanitize(
                input, format, query, config.getMaxHttpPayloadLength(), config.isRedactHttpPayloads());
    }

    @Override
    public void record(String segmentId, Map<String, String> call) {
        store.get(segmentId).ifPresent(trace -> trace.recordHttpCall(call));
    }

    @Override
    public TraceSnapshot begin(String label, String user, Map<String, String> attributes) {
        boolean capture = !(config.isRequireLabels() && (blank(label) || blank(user)));
        if (!capture && !config.isDistributedTracingEnabled()) return null;
        if (blank(label)) {
            String method = attributes == null ? null : attributes.get("httpMethod");
            String path = attributes == null ? null : attributes.get("path");
            label = "auto:" + (method == null ? "HTTP" : method) + " " + (path == null ? "/" : path);
        }
        if (blank(user)) user = "anonymous";
        Map<String, String> attrs = attributes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attributes);
        attrs.put("serviceInstanceId", serviceInstanceId);
        String distributedId = attrs.remove("distributedTraceId");
        Trace trace;
        if (config.isDistributedTracingEnabled() && W3CTraceContext.isValidTraceId(distributedId)) {
            trace = new Trace(distributedId, UUID.randomUUID().toString(), null, label, user, EntryChannel.HTTP,
                    System.currentTimeMillis(), System.nanoTime(), Trace.DEFAULT_MAX_EVENTS_PER_TRACE);
        } else {
            trace = new Trace(label, user, EntryChannel.HTTP, System.currentTimeMillis(), System.nanoTime());
        }
        attrs.forEach(trace::putAttribute);
        boolean retained = capture && !attrs.containsKey("captureSkipped") && store.register(trace);
        // Even skipped/disabled recording must carry context through an instrumented hop.
        if (!retained && !config.isDistributedTracingEnabled()) return null;
        if (!retained && !attrs.containsKey("captureSkipped")) attrs.put("captureSkipped", "not-retained");
        // The bridge owns context activation and servlet async completion.
        return new TraceSnapshot(trace.getTraceId(), trace.getSegmentId(), label, user, attrs);
    }

    @Override
    public void complete(String segmentId, Map<String, String> attributes) {
        store.get(segmentId).ifPresent(trace -> {
            if (attributes != null) {
                attributes.forEach((key, value) -> {
                    if ("user".equals(key)) {
                        // A configured hook can enrich a servlet principal with its site.
                        // Explicit labels are preserved by HttpTraceRuntime before this call.
                        if ("anonymous".equals(trace.getUser()) || attributes.containsKey("identitySource")) {
                            trace.setUser(value);
                        }
                    } else {
                        trace.putAttribute(key, value);
                    }
                });
            }
            trace.complete(System.currentTimeMillis());
        });
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!isLocalRequest(exchange)) {
                error(exchange, 403, "direct loopback access required");
                return;
            }
            String raw = exchange.getRequestURI().getRawPath();
            if (!(raw.equals(PREFIX) || raw.startsWith(PREFIX + "/"))) {
                error(exchange, 404, "unknown route");
                return;
            }
            String path;
            try {
                path = normalizePath(URLDecoder.decode(raw.substring(PREFIX.length()).replace("+", "%2B"), "UTF-8"));
            } catch (IllegalArgumentException invalid) {
                error(exchange, 400, "invalid path encoding");
                return;
            }
            String allowed = allowedMethods(path);
            if (allowed == null) {
                error(exchange, 404, "unknown route");
                return;
            }
            String method = exchange.getRequestMethod();
            boolean accepted = "GET".equals(allowed) ? "GET".equals(method)
                    : "POST".equals(method) || ("POST, DELETE".equals(allowed) && "DELETE".equals(method));
            if (!accepted) {
                exchange.getResponseHeaders().set("Allow", allowed);
                error(exchange, 405, "method not allowed");
                return;
            }
            if (path.equals("/summary")) {
                json(exchange, 200, summary());
            } else if (path.equals("/traces")) {
                json(exchange, 200, traces(store.summaries()));
            } else if (path.startsWith("/export/")) {
                export(exchange, path.substring("/export/".length()));
            } else if (path.startsWith("/overview/") || path.startsWith("/events/") || path.startsWith("/request-data/")) {
                projection(exchange, path);
            } else if (path.startsWith("/find-by-lens-id/")) {
                List<TraceSummary> matches = new ArrayList<>();
                for (Trace trace : store.findByLensId(path.substring("/find-by-lens-id/".length()))) {
                    matches.add(new TraceSummary(trace));
                }
                json(exchange, 200, traces(matches));
            } else if (path.startsWith("/find-by-trace-id/")) {
                String traceId = path.substring("/find-by-trace-id/".length());
                if (!validLookupId(traceId)) {
                    error(exchange, 400, "invalid trace id");
                    return;
                }
                List<TraceSummary> matches = new ArrayList<>();
                for (Trace trace : store.findByTraceId(traceId)) matches.add(new TraceSummary(trace));
                json(exchange, 200, traces(matches));
            } else if (path.equals("/metadata")) {
                json(exchange, 200, metadata());
            } else if (path.equals("/instrumented-classes")) {
                json(exchange, 200, instrumentedClasses());
            } else if (path.equals("/enable") || path.equals("/disable")) {
                store.setRecordingEnabled(path.equals("/enable"));
                json(exchange, 200, status("recording " + (store.isRecordingEnabled() ? "enabled" : "disabled")));
            } else if (path.equals("/reset")) {
                json(exchange, 200, status("cleared " + store.resetAll() + " traces"));
            } else if (store.reset(path.substring("/reset/".length()))) {
                json(exchange, 200, status("trace cleared"));
            } else {
                error(exchange, 404, "no such trace");
            }
        } catch (Throwable failure) {
            try { error(exchange, 500, "cannot process debug request"); } catch (IOException ignored) { }
        } finally {
            exchange.close();
        }
    }

    private boolean isLocalRequest(HttpExchange exchange) {
        if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) return false;
        for (String name : exchange.getRequestHeaders().keySet()) {
            if ("Origin".equalsIgnoreCase(name) || "Forwarded".equalsIgnoreCase(name)
                    || name.regionMatches(true, 0, "X-Forwarded-", 0, 12)) return false;
        }
        String fetchSite = exchange.getRequestHeaders().getFirst("Sec-Fetch-Site");
        if (fetchSite != null && !"none".equalsIgnoreCase(fetchSite)) return false;
        List<String> hosts = exchange.getRequestHeaders().get("Host");
        if (hosts == null || hosts.size() != 1) return false;
        String host = hosts.get(0);
        int boundPort = exchange.getLocalAddress().getPort();
        return ("127.0.0.1:" + boundPort).equals(host)
                || ("localhost:" + boundPort).equalsIgnoreCase(host)
                || ("[::1]:" + boundPort).equals(host)
                || (boundPort == 80 && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)));
    }

    private static String allowedMethods(String path) {
        if (path.equals("/summary") || path.equals("/traces") || path.startsWith("/export/")
                || path.startsWith("/overview/") || path.startsWith("/events/") || path.startsWith("/request-data/")
                || path.startsWith("/find-by-lens-id/") || path.startsWith("/find-by-trace-id/") || path.equals("/metadata")
                || path.equals("/instrumented-classes")) return "GET";
        if (path.equals("/enable") || path.equals("/disable")) return "POST";
        if (path.equals("/reset") || path.startsWith("/reset/")) return "POST, DELETE";
        return null;
    }

    private ObjectNode summary() {
        ObjectNode node = mapper.createObjectNode();
        TraceProjection.capabilities(node, config);
        node.put("mode", "standalone");
        node.put("javaVersion", System.getProperty("java.version"));
        node.put("port", server.getAddress().getPort());
        node.put("applicationId", config.getApplicationId());
        node.put("applicationVersion", config.getApplicationVersion());
        node.put("serviceInstanceId", serviceInstanceId);
        node.put("distributedTracingEnabled", config.isDistributedTracingEnabled());
        node.put("httpRedirectGuardReady", HttpClientTraceRuntime.isRedirectGuardReady());
        node.put("httpClientCaptureEnabled", config.isHttpClientCaptureEnabled());
        node.put("captureMessagingBoundaries", config.isCaptureMessagingBoundaries());
        node.put("captureDataStoreBoundaries", config.isCaptureDataStoreBoundaries());
        node.put("captureJdbcQueries", config.isCaptureJdbcQueries());
        node.put("captureJdbcParameters", config.isCaptureJdbcQueries() && config.isCaptureJdbcParameters());
        requestEvidenceSettings(node);
        node.put("maxSqlLength", config.getMaxSqlLength());
        node.put("maxSqlQueriesPerRequest", config.getMaxSqlQueriesPerRequest());
        node.put("instrumentMessaging", config.isInstrumentMessaging());
        node.putPOJO("httpIncludePaths", httpIncludePaths);
        node.putPOJO("packages", config.getPackages());
        node.put("recordingEnabled", store.isRecordingEnabled());
        node.put("traceCount", store.size());
        node.put("totalEvents", store.totalEvents());
        node.put("maxTrackedEvents", config.getMaxTrackedEvents());
        node.put("maxTrackedRequests", config.getMaxTrackedRequests());
        node.put("httpRetentionPercent", config.getHttpRetentionPercent());
        node.set("retention", mapper.valueToTree(store.retentionSummary()));
        node.put("agentActive", AgentStatus.agentActive());
        node.put("instrumentedClasses", AgentStatus.instrumentedCount());
        node.put("failedClasses", AgentStatus.failedCount());
        node.put("premainMs", AgentStatus.premainMillis());
        node.put("scope", AgentStatus.scopeDescription());
        ObjectNode bridge = node.putObject("bridge");
        bridge.put("sinkRegistered", TraceRuntime.hasSink());
        bridge.put("httpListenerRegistered", HttpTraceRuntime.hasListener());
        bridge.put("capturedRequests", HttpTraceRuntime.capturedRequests() + VertxHttpTraceRuntime.capturedRequests());
        bridge.put("vertxHttpListenerRegistered", VertxHttpTraceRuntime.hasListener());
        bridge.put("vertxCapturedRequests", VertxHttpTraceRuntime.capturedRequests());
        bridge.put("vertxInFlightRequests", VertxHttpTraceRuntime.inFlightRequests());
        bridge.put("emitted", TraceRuntime.emittedCount());
        bridge.put("droppedNoSink", TraceRuntime.droppedNoSinkCount());
        bridge.put("droppedNoTrace", TraceRuntime.droppedNoTraceCount());
        bridge.put("wrappedTasks", TraceRuntime.wrappedTaskCount());
        return node;
    }

    private ObjectNode traces(List<TraceSummary> summaries) {
        ObjectNode node = mapper.createObjectNode();
        ArrayNode array = node.putArray("traces");
        for (TraceSummary summary : summaries) {
            ObjectNode item = mapper.valueToTree(summary);
            item.put("applicationId", config.getApplicationId());
            item.put("applicationVersion", config.getApplicationVersion());
            item.put("serviceInstanceId", serviceInstanceId);
            array.add(item);
        }
        node.put("count", summaries.size());
        return node;
    }

    private void export(HttpExchange exchange, String segmentId) throws IOException {
        boolean preserve = false;
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null) {
            try {
                for (String pair : query.split("&")) {
                    String[] pieces = pair.split("=", 2);
                    if (pieces.length == 2 && "evict".equals(URLDecoder.decode(pieces[0], "UTF-8"))
                            && "false".equalsIgnoreCase(URLDecoder.decode(pieces[1], "UTF-8"))) preserve = true;
                }
            } catch (IllegalArgumentException invalid) {
                error(exchange, 400, "invalid query encoding");
                return;
            }
        }
        // Observing a live request must not mark it EXPORTED; that would prevent
        // subsequent completion from setting its end time and duration.
        Optional<Trace> trace = preserve ? store.get(segmentId) : store.export(segmentId);
        if (!trace.isPresent()) {
            error(exchange, 404, "no such trace");
            return;
        }
        ObjectNode exported = exporter.toJson(trace.get());
        // Scheduler/queue traces use the shared lifecycle rather than HTTP begin().
        // All exports from this runtime must match their instance-tagged summaries.
        exported.put("serviceInstanceId", serviceInstanceId);
        json(exchange, 200, exported);
    }

    private ObjectNode metadata() {
        ObjectNode node = mapper.createObjectNode();
        TraceProjection.capabilities(node, config);
        node.put("mode", "standalone");
        node.put("applicationId", config.getApplicationId());
        node.put("applicationVersion", config.getApplicationVersion());
        node.putPOJO("httpIncludePaths", httpIncludePaths);
        node.putPOJO("packages", config.getPackages());
        node.putPOJO("modules", config.getModules());
        node.putPOJO("excludedPackages", config.getExcludedPackages());
        node.putPOJO("blacklistedNodes", config.getBlacklistedNodes());
        node.put("capturePayloads", false);
        node.put("trackMethods", config.isTrackMethods());
        node.put("trackExceptions", config.isTrackExceptions());
        node.put("instrumentExecutors", config.isInstrumentExecutors());
        node.put("instrumentScheduled", config.isInstrumentScheduled());
        node.putPOJO("chargebeeJobTypes", config.getChargebeeJobTypes());
        node.put("idleTtlMinutes", config.getIdleTtlMinutes());
        node.put("evictOnExport", config.isEvictOnExport());
        node.put("requireLabels", config.isRequireLabels());
        node.putPOJO("httpIdentityHooks", config.getHttpIdentityHooks());
        node.put("serviceInstanceId", serviceInstanceId);
        node.put("distributedTracingEnabled", config.isDistributedTracingEnabled());
        node.putPOJO("httpPropagationAllowedDestinations", config.getHttpPropagationAllowedDestinations());
        node.put("httpClientCaptureEnabled", config.isHttpClientCaptureEnabled());
        node.put("captureMessagingBoundaries", config.isCaptureMessagingBoundaries());
        node.put("captureDataStoreBoundaries", config.isCaptureDataStoreBoundaries());
        node.put("captureJdbcQueries", config.isCaptureJdbcQueries());
        node.put("captureJdbcParameters", config.isCaptureJdbcQueries() && config.isCaptureJdbcParameters());
        requestEvidenceSettings(node);
        node.put("maxSqlLength", config.getMaxSqlLength());
        node.put("maxSqlQueriesPerRequest", config.getMaxSqlQueriesPerRequest());
        node.put("instrumentMessaging", config.isInstrumentMessaging());
        node.put("httpRedirectGuardReady", HttpClientTraceRuntime.isRedirectGuardReady());
        node.put("agentActive", AgentStatus.agentActive());
        return node;
    }

    private void requestEvidenceSettings(ObjectNode node) {
        node.put("captureHttpQueryParameters", config.isCaptureHttpQueryParameters());
        node.put("captureHttpRequestBodies", config.isCaptureHttpRequestBodies());
        node.put("redactHttpPayloads", config.isRedactHttpPayloads());
        node.put("maxHttpPayloadLength", config.getMaxHttpPayloadLength());
        node.put("httpRequestBodyCaptureSupport", "tomcat-application-reads-json-form");
        node.put("captureClickHouseQueries", config.isCaptureClickHouseQueries());
        node.put("captureClickHouseParameters", config.isCaptureClickHouseQueries() && config.isCaptureClickHouseParameters());
    }

    private ObjectNode instrumentedClasses() {
        ObjectNode node = mapper.createObjectNode();
        node.put("count", AgentStatus.instrumentedCount());
        node.put("failed", AgentStatus.failedCount());
        node.put("retainedNamesCap", true);
        ArrayNode names = node.putArray("names");
        for (String name : AgentStatus.instrumentedClassNames()) names.add(name);
        return node;
    }

    private ObjectNode status(String message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("status", message);
        node.put("recordingEnabled", store.isRecordingEnabled());
        node.put("traceCount", store.size());
        return node;
    }

    private void error(HttpExchange exchange, int code, String message) throws IOException {
        ObjectNode node = mapper.createObjectNode();
        node.put("error", message);
        json(exchange, code, node);
    }

    private void json(HttpExchange exchange, int code, ObjectNode node) throws IOException {
        respond(exchange, code, mapper.writeValueAsString(node));
    }

    private void projection(HttpExchange exchange, String path) throws IOException {
        String operation = path.substring(1, path.indexOf('/', 1));
        Optional<Trace> found = store.get(path.substring(operation.length() + 2));
        if (!found.isPresent()) { error(exchange, 404, "no such trace"); return; }
        try {
            TraceProjection projection = new TraceProjection(exporter);
            Map<String, String> params = TraceProjection.query(exchange.getRequestURI().getRawQuery());
            if (!"events".equals(operation) && !params.isEmpty()) throw new IllegalArgumentException("No query accepted for this projection");
            ObjectNode result = "overview".equals(operation) ? projection.overview(found.get())
                    : "events".equals(operation) ? projection.events(found.get(), params) : projection.requestData(found.get());
            result.put("serviceInstanceId", serviceInstanceId);
            respond(exchange, 200, TraceProjection.serialize(result));
        } catch (IllegalArgumentException invalid) { error(exchange, 400, invalid.getMessage()); }
    }

    private static void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String normalizePath(String path) {
        if (path == null || path.trim().isEmpty() || path.equals("/")) return "/summary";
        if (path.startsWith("/find-by-lens-id/") || path.startsWith("/export/") || path.startsWith("/reset/")) {
            return path;
        }
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static boolean validLookupId(String id) {
        if (W3CTraceContext.isValidTraceId(id)) return true;
        try { return UUID.fromString(id).toString().equals(id); }
        catch (IllegalArgumentException invalid) { return false; }
    }
}
