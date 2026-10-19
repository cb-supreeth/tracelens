package com.chargebee.tracelens.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpIdentityRuntimeTest {
    private final RecordingListener listener = new RecordingListener();

    @AfterEach void cleanup() {
        HttpIdentityRuntime.configure(Collections.emptyList());
        HttpTraceRuntime.unregisterListener(listener);
        TraceContext.clear();
    }

    @Test void postAuthIdentitySurvivesModelCleanupAndNestedScopesWithoutBleedingToNextRequest() {
        Request request = new Request();
        Object outer = begin(request);
        Object inner = HttpTraceRuntime.enter(request, new Response());
        Model model = new Model(request, "alice", 42L);
        configure(null, null, 0);
        identify(model);
        assertThat(TraceContext.current().getUser()).isEqualTo("alice_42");
        assertThat(TraceContext.current().getAttributes()).containsEntry("siteId", "42");
        model.user = null;
        model.site = null;
        HttpTraceRuntime.exit(inner, null);
        assertThat(TraceContext.current().getUser()).isEqualTo("alice_42");
        HttpTraceRuntime.exit(outer, null);
        assertThat(TraceContext.current()).isNull();
        assertThat(listener.completed.get(0)).containsEntry("user", "alice_42")
                .containsEntry("siteId", "42").containsEntry("identitySource", "auth");

        Object next = begin(new Request());
        assertThat(TraceContext.current().getUser()).isEqualTo("anonymous");
        HttpTraceRuntime.exit(next, null);
        assertThat(listener.completed.get(1)).doesNotContainKeys("user", "siteId", "identitySource");
        assertThat(TraceContext.current()).isNull();
    }

    @Test void explicitHeaderWinsButSiteAndIdentitySourceRemainAvailable() {
        Request request = new Request();
        request.headers.put("tracelens-user", "manual-debugger");
        Object scope = begin(request);
        configure(null, null, 0);
        identify(new Model(request, "alice", 42));
        assertThat(TraceContext.current().getUser()).isEqualTo("manual-debugger");
        HttpTraceRuntime.exit(scope, null);
        assertThat(listener.completed.get(0)).containsEntry("user", "manual-debugger")
                .containsEntry("siteId", "42").containsEntry("identitySource", "auth");
    }

    @Test void callerHeaderOnlyUnlocksUserPathForAllowedValuesOtherwiseUsesFallback() {
        configure("X-Caller", "apicall", 0);
        for (String caller : new String[]{"untrusted", "middleware", null}) {
            Request request = new Request();
            if (caller != null) request.headers.put("X-Caller", caller);
            Object scope = begin(request);
            Model model = new Model(request, "alice", "site-42");
            identify(model);
            assertThat(model.userReads).isEqualTo("middleware".equals(caller) ? 1 : 0);
            HttpTraceRuntime.exit(scope, null);
            assertThat(listener.completed.get(listener.completed.size() - 1)).containsEntry("user",
                    ("middleware".equals(caller) ? "alice" : "apicall") + "_site-42");
        }
    }

    @Test void nullOrNonStringUserUsesFallbackEvenForTrustedCaller() {
        configure("X-Caller", "apicall", 0);
        for (Object user : new Object[]{null, 12L, new SecretObject()}) {
            Request request = new Request();
            request.headers.put("X-Caller", "middleware");
            Object scope = begin(request);
            identify(new Model(request, user, 42));
            HttpTraceRuntime.exit(scope, null);
            assertThat(listener.completed.get(listener.completed.size() - 1)).containsEntry("user", "apicall_42");
        }
    }

    @Test void callbackDoesNotStartTraceOrChangeUnrelatedThreadContext() {
        configure(null, null, 0);
        HttpTraceRuntime.registerListener(listener);
        identify(new Model(new Request(), "alice", 42));
        assertThat(listener.begun).isZero();
        Request request = new Request();
        Object scope = begin(request);
        TraceSnapshot unrelated = new TraceSnapshot("unrelated", "other", "worker", null);
        try (TraceScope ignored = TraceContext.activate(unrelated)) {
            identify(new Model(request, "alice", 42));
            assertThat(TraceContext.current()).isSameAs(unrelated);
        }
        HttpTraceRuntime.exit(scope, null);
        assertThat(TraceContext.current()).isNull();
        assertThat(listener.completed.get(0)).containsEntry("user", "alice_42");
    }

    @Test void asyncWrapperCapturesEnrichedSnapshotAndRestoresWorkerContext() throws Exception {
        Request request = new Request();
        Object scope = begin(request);
        configure(null, null, -1);
        HttpIdentityRuntime.onSuccessfulExit("auth", new Model(request, "alice", 42), null);
        AtomicReference<TraceSnapshot> seen = new AtomicReference<>();
        AtomicReference<TraceSnapshot> after = new AtomicReference<>();
        Runnable worker = HttpTraceRuntime.wrapAsync(new Async(request), () -> seen.set(TraceContext.current()));
        HttpTraceRuntime.exit(scope, null);
        Thread thread = new Thread(() -> { worker.run(); after.set(TraceContext.current()); });
        thread.start();
        thread.join();
        assertThat(seen.get().getUser()).isEqualTo("alice_42");
        assertThat(seen.get().getAttributes()).containsEntry("identitySource", "auth");
        assertThat(after.get()).isNull();
        assertThat(TraceContext.current()).isNull();
    }

    @Test void missingThrowingStaticAndNonPublicGettersFailOpenWithoutIdentity() {
        configure(null, null, 0);
        Request request = new Request();
        Object scope = begin(request);
        assertThatCode(() -> {
            HttpIdentityRuntime.onSuccessfulExit("auth", null, null);
            HttpIdentityRuntime.onSuccessfulExit("auth", null, new Object[0]);
            identify(null);
            identify(new Object());
            identify(new ThrowingModel(request, "alice", 42));
            identify(new StaticModel());
            identify(new PrivateModel());
        }).doesNotThrowAnyException();
        HttpTraceRuntime.exit(scope, null);
        assertThat(listener.completed.get(0)).doesNotContainKeys("user", "siteId", "identitySource");
    }

    @Test void malformedValuesAreNeverRenderedOrStoredAsIdentity() {
        configure(null, null, 0);
        for (Object site : new Object[]{new SecretObject(), null, "42\n", "\t42", "a".repeat(257)}) {
            Request request = new Request();
            Object scope = begin(request);
            identify(new Model(request, "alice", site));
            HttpTraceRuntime.exit(scope, null);
            assertThat(listener.completed.get(listener.completed.size() - 1))
                    .doesNotContainKeys("user", "siteId", "identitySource");
        }
        for (String user : new String[]{"alice\n", "\ralice", "a".repeat(255)}) {
            Request request = new Request();
            Object scope = begin(request);
            identify(new Model(request, user, 42));
            HttpTraceRuntime.exit(scope, null);
            assertThat(listener.completed.get(listener.completed.size() - 1)).doesNotContainKey("user");
        }
    }

    @Test void completedRequestCannotBeReidentified() {
        Request request = new Request();
        Object scope = begin(request);
        HttpTraceRuntime.exit(scope, null);
        configure(null, null, 0);
        identify(new Model(request, "alice", 42));
        assertThat(listener.completed).hasSize(1);
        assertThat(listener.completed.get(0)).doesNotContainKey("user");
        assertThat(TraceContext.current()).isNull();
    }

    @Test void pathsAreBoundedAndCannotInvokeObjectMethods() {
        assertThatThrownBy(() -> new HttpIdentityRuntime.Hook("x", 0, List.of("getClass"),
                List.of("getUser"), List.of("getSite"), null, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpIdentityRuntime.Hook("x", 0, Collections.nCopies(9, "req"),
                List.of("getUser"), List.of("getSite"), null, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpIdentityRuntime.Hook("x", 0, List.of("req"),
                List.of("getUser"), List.of("site"), "authorization", List.of("blocked"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Object begin(Request request) {
        HttpTraceRuntime.registerListener(listener);
        Object scope = HttpTraceRuntime.enter(request, new Response());
        assertThat(scope).isNotNull();
        return scope;
    }
    private void configure(String header, String fallback, int argument) {
        HttpIdentityRuntime.configure(List.of(new HttpIdentityRuntime.Hook("auth", argument,
                List.of("req"), List.of("getUser"), List.of("site"), header,
                header == null ? List.of() : List.of("middleware"), fallback)));
    }
    private void identify(Object model) { HttpIdentityRuntime.onSuccessfulExit("auth", null, new Object[]{model}); }

    public static class Model {
        private final Request request;
        Object user;
        Object site;
        int userReads;
        Model(Request request, Object user, Object site) { this.request = request; this.user = user; this.site = site; }
        public Request req() { return request; }
        public Object getUser() { userReads++; return user; }
        public Object site() { return site; }
    }
    public static class ThrowingModel extends Model {
        ThrowingModel(Request request, Object user, Object site) { super(request, user, site); }
        @Override public Object site() { throw new IllegalStateException("Unavailable app context"); }
    }
    public static class StaticModel { public static Request req() { throw new AssertionError("Static getter invoked"); } }
    public static class PrivateModel { private Request req() { throw new AssertionError("Private getter invoked"); } }
    public static class SecretObject { @Override public String toString() { throw new AssertionError("Must not serialize model"); } }
    public static class Request {
        final Map<String, Object> attributes = new HashMap<>();
        final Map<String, String> headers = new HashMap<>();
        public Object getAttribute(String name) { return attributes.get(name); }
        public void setAttribute(String name, Object value) { attributes.put(name, value); }
        public String getHeader(String name) { return headers.get(name); }
        public String getMethod() { return "GET"; }
        public String getRequestURI() { return "/api/example"; }
        public String getContextPath() { return ""; }
        public boolean isAsyncStarted() { return false; }
    }
    public static class Response {
        public int getStatus() { return 200; }
        public void setHeader(String name, String value) { }
    }
    public static class Async {
        private final Request request;
        Async(Request request) { this.request = request; }
        public Request getRequest() { return request; }
    }
    static class RecordingListener implements HttpTraceRuntime.Listener {
        int begun;
        final List<Map<String, String>> completed = new ArrayList<>();
        @Override public TraceSnapshot begin(String label, String user, Map<String, String> attrs) {
            return new TraceSnapshot("request-" + ++begun, "test", user == null ? "anonymous" : user, attrs);
        }
        @Override public void complete(String id, Map<String, String> attrs) { completed.add(new LinkedHashMap<>(attrs)); }
    }
}
