package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.TraceContext;
import com.chargebee.tracelens.bridge.HttpTraceRuntime;
import com.chargebee.tracelens.bridge.TraceRuntime;
import com.chargebee.tracelens.core.config.TraceLensConfig;
import com.chargebee.tracelens.core.record.TraceLifecycle;
import com.chargebee.tracelens.core.record.TraceSink;
import com.chargebee.tracelens.core.record.Tracer;
import com.chargebee.tracelens.core.store.TraceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class StandaloneRuntimeTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private String previousPort;

    @BeforeEach
    void ephemeralPort() {
        previousPort = System.getProperty("tracelens.port");
        System.setProperty("tracelens.port", "0");
    }

    @AfterEach
    void cleanUp() {
        StandaloneRuntime.stop();
        TraceRuntime.unregisterSink();
        TraceRuntime.unregisterLifecycleListener();
        if (previousPort == null) System.clearProperty("tracelens.port");
        else System.setProperty("tracelens.port", previousPort);
    }

    private static TraceLensConfig config() {
        // An empty scope is a supported HTTP-metadata-only standalone setup.
        return new TraceLensConfig().setApplicationId("standalone-test");
    }

    @Test
    void reportsRuntimeAndControlsRecordingWithoutApplicationDependencies() throws Exception {
        assertThat(StandaloneRuntime.start(config())).isTrue();
        int port = StandaloneRuntime.port();
        assertThat(port).isPositive();
        assertThat(StandaloneRuntime.start(config())).isTrue();
        assertThat(StandaloneRuntime.port()).isEqualTo(port);

        JsonNode summary = request("GET", "/summary").json();
        assertThat(summary.path("mode").asText()).isEqualTo("standalone");
        assertThat(summary.path("port").asInt()).isEqualTo(port);
        assertThat(summary.path("javaVersion").asText()).isNotEmpty();
        assertThat(summary.path("lineCaptureEnabled").asBoolean()).isFalse();
        assertThat(summary.path("bridge").path("sinkRegistered").asBoolean()).isTrue();
        assertThat(summary.path("bridge").path("httpListenerRegistered").asBoolean()).isTrue();

        assertThat(request("GET", "/disable").status).isEqualTo(405);
        assertThat(request("POST", "/disable").json().path("recordingEnabled").asBoolean()).isFalse();
        assertThat(TraceRuntime.beginLifecycle(null, "disabled", "developer", "HTTP", Collections.emptyMap())).isNull();
        assertThat(request("POST", "/enable").json().path("recordingEnabled").asBoolean()).isTrue();
        assertThat(request("GET", "/metadata").json().path("applicationId").asText()).isEqualTo("standalone-test");
        assertThat(request("GET", "/instrumented-classes").json().path("names").isArray()).isTrue();
        StandaloneRuntime.stop();
        assertThat(StandaloneRuntime.port()).isEqualTo(-1);
        assertThat(TraceRuntime.hasSink()).isFalse();
    }

    @Test
    void preservesLiveTraceCompletionDuringMcpReadsAndFindsExactLabels() throws Exception {
        assertThat(StandaloneRuntime.start(config())).isTrue();
        String label = "auto:GET /features/";
        String segment = TraceRuntime.beginLifecycle(null, label, "developer", "HTTP",
                Collections.singletonMap("path", "/features/"));
        try {
            TraceRuntime.onLine("example.Features", 25);
            JsonNode live = request("GET", "/export/" + segment + "?evict=false").json();
            assertThat(live.path("status").asText()).isEqualTo("ACTIVE");
            assertThat(live.path("eventCount").asInt()).isEqualTo(1);
            assertThat(live.path("events").get(0).path("line").asInt()).isEqualTo(25);
            assertThat(request("GET", "/traces").json().path("count").asInt()).isEqualTo(1);
            JsonNode matching = request("GET", "/find-by-lens-id/" + URLEncoder.encode(label, "UTF-8").replace("+", "%20")).json();
            assertThat(matching.path("count").asInt()).isEqualTo(1);
            assertThat(matching.path("traces").get(0).path("lensId").asText()).isEqualTo(label);
        } finally {
            TraceRuntime.endLifecycle(segment);
        }
        assertThat(TraceContext.current()).isNull();
        JsonNode completed = request("GET", "/export/" + segment + "?evict=false").json();
        assertThat(completed.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(completed.has("endedAt")).isTrue();
        assertThat(completed.path("durationMs").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(request("GET", "/export/" + segment).json().path("status").asText()).isEqualTo("EXPORTED");
        assertThat(request("GET", "/export/" + segment + "?evict=false").status).isEqualTo(404);
    }

    @Test
    void storesConfiguredIdentityOverPrincipalButPreservesExplicitLabels() throws Exception {
        assertThat(StandaloneRuntime.start(config())).isTrue();
        for (String label : new String[] {null, "explicit-developer"}) {
            IdentityRequest req = new IdentityRequest(label);
            Object scope = HttpTraceRuntime.enter(req, new IdentityResponse());
            String segment = TraceContext.current().getSegmentId();
            HttpTraceRuntime.identify(req, "alice_42", "42", "verified-auth");
            HttpTraceRuntime.exit(scope, null);
            assertThat(TraceContext.current()).isNull();
            JsonNode trace = request("GET", "/export/" + segment + "?evict=false").json();
            assertThat(trace.path("user").asText()).isEqualTo(label == null ? "alice_42" : label);
            assertThat(trace.path("attributes").path("siteId").asText()).isEqualTo("42");
            assertThat(trace.path("attributes").path("identitySource").asText()).isEqualTo("verified-auth");
        }
        assertThat(request("GET", "/metadata").json().path("httpIdentityHooks").isArray()).isTrue();
    }

    public static final class IdentityRequest {
        private final java.util.Map<String, Object> attributes = new java.util.HashMap<>();
        private final String label;
        private String traceparent;
        private java.util.List<String> tracestate = Collections.emptyList();
        private String uri = "/identity-fixture";
        IdentityRequest(String label) { this.label = label; }
        public Object getAttribute(String key) { return attributes.get(key); }
        public void setAttribute(String key, Object value) { attributes.put(key, value); }
        public String getMethod() { return "GET"; }
        public String getRequestURI() { return uri; }
        public String getContextPath() { return ""; }
        public String getHeader(String key) {
            if ("traceparent".equals(key)) return traceparent;
            return "tracelens-user".equals(key) ? label : null;
        }
        public java.util.Enumeration<String> getHeaders(String key) {
            return Collections.enumeration("tracestate".equals(key) ? tracestate
                    : traceparent != null && "traceparent".equals(key) ? Collections.singletonList(traceparent) : Collections.emptyList());
        }
        public java.security.Principal getUserPrincipal() { return () -> "alice"; }
        public boolean isAsyncStarted() { return false; }
    }

    public static final class IdentityResponse {
        public void setHeader(String key, String value) { }
        public int getStatus() { return 200; }
    }

    @Test
    void continuesRemoteTraceWithFreshSegmentAndExposesReadOnlyLookup() throws Exception {
        assertThat(StandaloneRuntime.start(config().setDistributedTracingEnabled(true))).isTrue();
        String id = "0af7651916cd43dd8448eb211c80319c";
        java.util.Set<String> segments = new java.util.HashSet<>();
        for (int i = 0; i < 2; i++) {
            IdentityRequest req = new IdentityRequest(null);
            req.traceparent = "00-" + id + "-b7ad6b7169203331-01";
            Object scope = HttpTraceRuntime.enter(req, new IdentityResponse());
            String segment = TraceContext.currentSegmentId();
            assertThat(TraceContext.currentTraceId()).isEqualTo(id);
            assertThat(segments.add(segment)).isTrue();
            assertThat(segment).isNotEqualTo(id);
            HttpTraceRuntime.exit(scope, null);
            JsonNode trace = request("GET", "/export/" + segment + "?evict=false").json();
            assertThat(trace.path("parentSpanId").asText()).isEqualTo("b7ad6b7169203331");
            assertThat(trace.path("spanId").asText()).hasSize(16).isNotEqualTo("b7ad6b7169203331");
            assertThat(trace.path("serviceInstanceId").asText()).isNotEmpty();
            assertThat(trace.path("httpCalls").isArray()).isTrue();
        }
        assertThat(request("GET", "/find-by-trace-id/" + id).json().path("count").asInt()).isEqualTo(2);
        assertThat(request("POST", "/find-by-trace-id/" + id).status).isEqualTo(405);
        assertThat(request("GET", "/find-by-trace-id/invalid").status).isEqualTo(400);
        assertThat(TraceContext.current()).isNull();
    }

    @Test
    void combinesBoundedTracestateHeadersAndRejectsDuplicateVendorKeys() {
        assertThat(StandaloneRuntime.start(config().setDistributedTracingEnabled(true))).isTrue();
        IdentityRequest req = new IdentityRequest(null);
        req.traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        req.tracestate = java.util.Arrays.asList("vendor1=one", "vendor2=two");
        Object token = HttpTraceRuntime.enter(req, new IdentityResponse());
        assertThat(TraceContext.current().getAttributes().get("tracestate")).isEqualTo("vendor1=one,vendor2=two");
        HttpTraceRuntime.exit(token, null);
        req = new IdentityRequest(null);
        req.traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        req.tracestate = java.util.Arrays.asList("vendor=one", "vendor=two");
        token = HttpTraceRuntime.enter(req, new IdentityResponse());
        assertThat(TraceContext.currentTraceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(TraceContext.current().getAttributes()).doesNotContainKey("tracestate");
        HttpTraceRuntime.exit(token, null);
    }

    @Test
    void contextSurvivesDisabledRecordingAndPathFiltersWithoutRetainingData() throws Exception {
        assertThat(StandaloneRuntime.start(config().setDistributedTracingEnabled(true))).isTrue();
        request("POST", "/disable");
        for (String path : new String[] {"/identity-fixture", "/favicon.ico"}) {
            IdentityRequest req = new IdentityRequest(null);
            req.uri = path;
            Object token = HttpTraceRuntime.enter(req, new IdentityResponse());
            assertThat(TraceContext.currentTraceId()).hasSize(32);
            assertThat(TraceContext.current().getAttributes().get("spanId")).hasSize(16);
            HttpTraceRuntime.exit(token, null);
            assertThat(TraceContext.current()).isNull();
        }
        assertThat(request("GET", "/traces").json().path("count").asInt()).isZero();
    }

    @Test
    void disabledFeaturePreservesIndependentLocalRoots() throws Exception {
        assertThat(StandaloneRuntime.start(config())).isTrue();
        IdentityRequest req = new IdentityRequest(null);
        req.traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        Object scope = HttpTraceRuntime.enter(req, new IdentityResponse());
        assertThat(TraceContext.currentTraceId()).isEqualTo(TraceContext.currentSegmentId()).hasSize(36);
        assertThat(TraceContext.current().getAttributes()).doesNotContainKey("spanId");
        HttpTraceRuntime.exit(scope, null);
    }

    @Test
    void blocksBrowserOriginsForwardedRequestsAndUnexpectedHosts() throws Exception {
        assertThat(StandaloneRuntime.start(config())).isTrue();
        String localHost = "127.0.0.1:" + StandaloneRuntime.port();
        assertThat(request("GET", "/summary", localHost, "Origin: https://example.test\r\n").status).isEqualTo(403);
        assertThat(request("GET", "/summary", localHost, "Forwarded: for=127.0.0.1\r\n").status).isEqualTo(403);
        assertThat(request("GET", "/summary", localHost, "X-Forwarded-Host: localhost\r\n").status).isEqualTo(403);
        assertThat(request("GET", "/summary", localHost, "Sec-Fetch-Site: cross-site\r\n").status).isEqualTo(403);
        assertThat(request("GET", "/summary", "evil.test:" + StandaloneRuntime.port(), "").status).isEqualTo(403);
        assertThat(request("GET", "/summary", "localhost:1", "").status).isEqualTo(403);
        assertThat(request("GET", "/summary", "localhost:" + StandaloneRuntime.port(), "").status).isEqualTo(200);
    }

    @Test
    void resetUsesExplicitMutationAndKeepsResponseSchema() throws Exception {
        assertThat(StandaloneRuntime.start(config())).isTrue();
        String segment = TraceRuntime.beginLifecycle(null, "first", "developer", "SCHEDULED", Collections.emptyMap());
        TraceRuntime.endLifecycle(segment);
        String instance = request("GET", "/metadata").json().path("serviceInstanceId").asText();
        assertThat(request("GET", "/export/" + segment + "?evict=false").json().path("serviceInstanceId").asText()).isEqualTo(instance);
        assertThat(request("GET", "/reset/" + segment).status).isEqualTo(405);
        assertThat(request("DELETE", "/reset/" + segment).json().path("traceCount").asInt()).isZero();
        assertThat(request("POST", "/reset/" + segment).status).isEqualTo(404);
        segment = TraceRuntime.beginLifecycle(null, "second", "developer", "HTTP", Collections.emptyMap());
        TraceRuntime.endLifecycle(segment);
        assertThat(request("POST", "/reset").json().path("traceCount").asInt()).isZero();
    }

    @Test
    void occupiedPortFailsOpenAndDoesNotReplaceExistingRuntime() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            System.setProperty("tracelens.port", String.valueOf(occupied.getLocalPort()));
            assertThat(StandaloneRuntime.start(config())).isFalse();
            assertThat(StandaloneRuntime.port()).isEqualTo(-1);
            assertThat(TraceRuntime.hasSink()).isFalse();
        }
        System.setProperty("tracelens.port", "0");
        try (TraceStore existing = new TraceStore(config())) {
            TraceRuntime.registerSink(new TraceSink(existing));
            assertThat(StandaloneRuntime.start(config())).isFalse();
            assertThat(TraceRuntime.hasSink()).isTrue();
        }
    }

    @Test
    void stoppingUnregistersOnlyOwnedListeners() {
        assertThat(StandaloneRuntime.start(config())).isTrue();
        try (TraceStore replacement = new TraceStore(config())) {
            TraceRuntime.registerSink(new TraceSink(replacement));
            TraceRuntime.registerLifecycleListener(new TraceLifecycle(new Tracer(replacement)));
            StandaloneRuntime.stop();
            assertThat(TraceRuntime.hasSink()).isTrue();
            String segment = TraceRuntime.beginLifecycle(null, "replacement", "developer", "HTTP", Collections.emptyMap());
            try {
                assertThat(segment).isNotNull();
                TraceRuntime.onLine("example.Replacement", 14);
                assertThat(replacement.get(segment).get().eventCount()).isEqualTo(1);
            } finally {
                TraceRuntime.endLifecycle(segment);
            }
        }
    }

    private Response request(String method, String suffix) throws Exception {
        return request(method, suffix, "127.0.0.1:" + StandaloneRuntime.port(), "");
    }

    private Response request(String method, String suffix, String host, String headers) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", StandaloneRuntime.port())) {
            socket.setSoTimeout(5000);
            String request = method + " /tracelens" + suffix + " HTTP/1.1\r\nHost: " + host
                    + "\r\nConnection: close\r\nContent-Length: 0\r\n" + headers + "\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String[] parts = response.split("\r\n\r\n", 2);
            return new Response(Integer.parseInt(parts[0].split(" ")[1]), parts.length == 2 ? parts[1] : "");
        }
    }

    private final class Response {
        private final int status;
        private final String body;
        private Response(int status, String body) { this.status = status; this.body = body; }
        private JsonNode json() throws Exception { return mapper.readTree(body); }
    }
}
