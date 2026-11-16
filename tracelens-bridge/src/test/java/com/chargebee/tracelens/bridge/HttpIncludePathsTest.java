package com.chargebee.tracelens.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HttpIncludePathsTest {
    private Recording listener;

    @AfterEach void cleanup() { HttpTraceRuntime.unregisterListener(listener); TraceContext.clear(); }

    private void configure(List<String> paths) {
        listener = new Recording(paths); HttpTraceRuntime.registerListener(listener);
    }

    private boolean request(String path, String context) {
        Object scope = HttpTraceRuntime.enter(new Request(context + path, context), new Response());
        HttpTraceRuntime.exit(scope, null);
        return scope != null;
    }

    @Test void matchesExactAndSlashBoundaryPrefixesRelativeToContext() {
        configure(Arrays.asList("/api/", "/cbapi/subscriptions"));
        for (String path : Arrays.asList("/api", "/api/", "/api/features", "/cbapi/subscriptions", "/cbapi/subscriptions/item")) {
            assertThat(request(path, "/host")).as(path).isTrue();
        }
        for (String path : Arrays.asList("/", "/api-other", "/apis", "/cbapi/subscriptions-other", "/other/api")) {
            assertThat(request(path, "/host")).as(path).isFalse();
        }
        assertThat(listener.begun.get(0).get("path")).isEqualTo("/api");
    }

    @Test void explicitEmptyAndNullFailClosedButDefaultListenerStillCaptures() {
        configure(Collections.emptyList()); assertThat(request("/api", "")).isFalse();
        configure(null); assertThat(request("/api", "")).isFalse();
        listener = new Recording(Collections.emptyList()) {
            @Override public List<String> httpIncludePaths() { return defaultPaths(); }
        };
        HttpTraceRuntime.registerListener(listener); assertThat(request("/api", "")).isTrue();
    }

    @Test void preservesIgnoredPathsEvenWhenAllPathsIncluded() {
        configure(Collections.singletonList("/"));
        for (String path : Arrays.asList("/tracelens", "/tracelens/summary", "/health", "/health/deep", "/healthz",
                "/readyz", "/actuator/health", "/admin/status", "/favicon.ico", "/assets/a", "/static/a")) {
            assertThat(request(path, "")).as(path).isFalse();
        }
        assertThat(request("/healthcare", "")).isTrue();
    }

    @Test void registrationSnapshotsPathsAndIgnoresLaterLegacyPropertyChanges() {
        List<String> paths = new ArrayList<>(Collections.singletonList("/api")); configure(paths);
        paths.clear(); paths.add("/");
        String key = "tracelens.http.includePaths", previous = System.getProperty(key);
        try {
            System.setProperty(key, "/other");
            assertThat(request("/api/one", "")).isTrue(); assertThat(request("/other", "")).isFalse();
            System.setProperty(key, "/"); assertThat(request("/other", "")).isFalse();
        } finally { if (previous == null) System.clearProperty(key); else System.setProperty(key, previous); }
    }

    @Test void filteredDistributedRequestsKeepPropagationWithExplicitSkippedCapture() {
        configure(Collections.emptyList()); listener.distributed = true;
        Request request = new Request("/excluded", "");
        request.parent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        Object scope = HttpTraceRuntime.enter(request, new Response());
        assertThat(scope).isNotNull();
        assertThat(listener.begun.get(0)).containsEntry("captureSkipped", "path-filter")
                .containsEntry("distributedTraceId", "0af7651916cd43dd8448eb211c80319c")
                .containsEntry("parentSpanId", "b7ad6b7169203331");
        assertThat(TraceContext.current()).isNotNull();
        HttpTraceRuntime.exit(scope, null); assertThat(TraceContext.current()).isNull();
    }

    static class Recording implements HttpTraceRuntime.Listener {
        final List<String> paths;
        final List<Map<String, String>> begun = new ArrayList<>();
        boolean distributed;
        Recording(List<String> paths) { this.paths = paths; }
        List<String> defaultPaths() { return HttpTraceRuntime.Listener.super.httpIncludePaths(); }
        @Override public List<String> httpIncludePaths() { return paths; }
        @Override public boolean distributedTracingEnabled() { return distributed; }
        @Override public TraceSnapshot begin(String label, String user, Map<String, String> attributes) {
            begun.add(new LinkedHashMap<>(attributes));
            return new TraceSnapshot("request-" + begun.size(), "test", "anonymous", attributes);
        }
        @Override public void complete(String id, Map<String, String> attributes) { }
    }
    public static class Request {
        final Map<String, Object> attributes = new HashMap<>();
        final String uri, context;
        String parent;
        Request(String uri, String context) { this.uri = uri; this.context = context; }
        public String getMethod() { return "GET"; }
        public String getRequestURI() { return uri; }
        public String getContextPath() { return context; }
        public Object getAttribute(String key) { return attributes.get(key); }
        public void setAttribute(String key, Object value) { attributes.put(key, value); }
        public String getHeader(String key) { return "traceparent".equals(key) ? parent : null; }
        public boolean isAsyncStarted() { return false; }
    }
    public static class Response {
        public void setHeader(String name, String value) { }
        public int getStatus() { return 200; }
    }
}
