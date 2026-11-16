package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.HttpTraceRuntime;
import com.chargebee.tracelens.bridge.TraceContext;
import com.chargebee.tracelens.bridge.TraceScope;
import com.chargebee.tracelens.bridge.TraceSnapshot;
import com.chargebee.tracelens.bridge.VertxHttpTraceRuntime;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** Real Vert.x 3.9.16 weaving, asynchronous callbacks, network completion and isolation. */
class VertxHttpWeavingTest {
    Vertx vertx;
    Recording listener;
    final List<String> callbackFailures = new CopyOnWriteArrayList<>();

    @BeforeAll static void instrument() {
        VertxHttpInstrumentation.install(ByteBuddyAgent.install());
    }

    @BeforeEach void start() {
        listener = new Recording();
        VertxHttpTraceRuntime.registerListener(listener);
        vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1).setWorkerPoolSize(2));
    }

    @AfterEach void stop() throws Exception {
        VertxHttpTraceRuntime.unregisterListener(listener);
        TraceContext.clear();
        CompletableFuture<Void> done = new CompletableFuture<>();
        vertx.close(result -> done.complete(null));
        done.get(10, TimeUnit.SECONDS);
        assertThat(callbackFailures).isEmpty();
    }

    @Test void bodyWorkerFutureAndTimerCallbacksKeepRequestIdentityUntilActualEnd() throws Exception {
        CompletableFuture<String> entered = new CompletableFuture<>();
        int port = listen(request -> {
            String segment = TraceContext.currentSegmentId();
            entered.complete(segment);
            request.bodyHandler(body -> {
                sameContext(segment, "request body");
                vertx.executeBlocking(work -> {
                    sameContext(segment, "worker");
                    work.complete("ready");
                }, result -> {
                    sameContext(segment, "worker result");
                    Future<String> next = Future.future();
                    next.onComplete(value -> {
                        sameContext(segment, "future completion");
                        request.response().setStatusCode(201).end("created");
                    });
                    vertx.setTimer(100, timer -> {
                        sameContext(segment, "timer");
                        if (!listener.completed.isEmpty()) callbackFailures.add("completed before response.end");
                        next.complete("go");
                    });
                });
            });
        });
        Response response = request(port, "/trace/body?secret=do-not-capture", "post-body-secret");
        String segment = entered.get(10, TimeUnit.SECONDS);
        awaitCompletion(1);
        assertThat(response.status).isEqualTo(201);
        assertThat(response.body).isEqualTo("created");
        assertThat(response.segment).isEqualTo(segment);
        assertThat(listener.started.get(segment).getAttributes()).containsEntry("path", "/trace/body")
                .containsEntry("httpAdapter", "vertx3-http1")
                .containsEntry("httpServerPort", Integer.toString(port))
                .containsEntry("timingBoundary", "response-end-submitted");
        assertThat(listener.completed.get(segment)).containsEntry("httpStatus", "201");
        assertThat(listener.started.toString() + listener.completed).doesNotContain("do-not-capture", "post-body-secret");
        assertThat(listener.completionCount.get()).isEqualTo(1);
        assertThat(VertxHttpTraceRuntime.inFlightRequests()).isZero();
        assertThat(TraceContext.current()).isNull();
    }

    @Test void overlappingRequestsOnOneEventLoopNeverShareContext() throws Exception {
        int port = listen(request -> {
            String segment = TraceContext.currentSegmentId();
            String path = request.path();
            vertx.setTimer(20 + Integer.parseInt(path.substring(path.lastIndexOf('/') + 1)) % 4 * 10L, timer -> {
                sameContext(segment, path);
                request.response().end(segment);
            });
        });
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<java.util.concurrent.Future<Response>> responses = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                final int index = i;
                responses.add(pool.submit(() -> request(port, "/trace/" + index, null)));
            }
            for (java.util.concurrent.Future<Response> pending : responses) {
                Response result = pending.get(10, TimeUnit.SECONDS);
                assertThat(result.body).isEqualTo(result.segment);
            }
        } finally { pool.shutdownNow(); }
        awaitCompletion(12);
        assertThat(listener.started).hasSize(12);
        assertThat(listener.started.values().stream().map(TraceSnapshot::getTraceId).distinct().count()).isEqualTo(12);
        CompletableFuture<TraceSnapshot> clean = new CompletableFuture<>();
        vertx.runOnContext(ignored -> clean.complete(TraceContext.current()));
        assertThat(clean.get(10, TimeUnit.SECONDS)).isNull();
        assertThat(VertxHttpTraceRuntime.inFlightRequests()).isZero();
    }

    @Test void sameJvmProxyAndModuleRequestsAreIndependentWithoutAnActualCarrier() throws Exception {
        int downstream = listen(request -> request.response().end(TraceContext.currentSegmentId()));
        HttpClient client = vertx.createHttpClient();
        int proxy = listen(request -> {
            String outer = TraceContext.currentSegmentId();
            client.get(downstream, "127.0.0.1", "/trace/module", response -> {
                sameContext(outer, "HTTP client response callback");
                response.bodyHandler(body -> {
                    sameContext(outer, "HTTP client body callback");
                    request.response().end(body);
                });
            }).end();
        });
        Response response = request(proxy, "/trace/proxy", null);
        awaitCompletion(2);
        TraceSnapshot outer = listener.started.get(response.segment);
        TraceSnapshot inner = listener.started.get(response.body);
        assertThat(outer).isNotNull();
        assertThat(inner).isNotNull();
        assertThat(outer.getTraceId()).isNotEqualTo(inner.getTraceId());
        assertThat(outer.getSegmentId()).isNotEqualTo(inner.getSegmentId());
        assertThat(inner.getAttributes()).doesNotContainKey("parentSpanId");
        client.close();
    }

    @Test void incomingW3CCarrierIsHonoredAndDuplicateParentsFailClosed() throws Exception {
        String traceId = "0123456789abcdef0123456789abcdef";
        String parent = "00-" + traceId + "-0123456789abcdef-01";
        int port = listen(request -> request.response().end("ok"));
        HttpURLConnection connection = connection(port, "/trace/parent");
        connection.setRequestProperty("traceparent", parent);
        Response valid = read(connection);
        awaitCompletion(1);
        assertThat(listener.started.get(valid.segment).getTraceId()).isEqualTo(traceId);
        assertThat(listener.started.get(valid.segment).getAttributes()).containsEntry("parentSpanId", "0123456789abcdef");
        HttpURLConnection duplicate = connection(port, "/trace/duplicate");
        duplicate.addRequestProperty("traceparent", parent);
        duplicate.addRequestProperty("traceparent", parent);
        Response invalid = read(duplicate);
        awaitCompletion(2);
        assertThat(listener.started.get(invalid.segment).getTraceId()).isNotEqualTo(traceId);
        assertThat(listener.started.get(invalid.segment).getAttributes()).doesNotContainKey("parentSpanId");
    }

    @Test void allowlistHealthAndNoListenerDoNotRetainRequests() throws Exception {
        listener.distributed = false;
        int port = listen(request -> request.response().end("ok"));
        assertThat(request(port, "/traceable/not-in-prefix", null).status).isEqualTo(200);
        assertThat(request(port, "/trace/healthz", null).status).isEqualTo(200);
        assertThat(listener.started).isEmpty();
        listener.decline = true;
        assertThat(request(port, "/trace/declined", null).status).isEqualTo(200);
        assertThat(listener.started).isEmpty();
        VertxHttpTraceRuntime.unregisterListener(listener);
        assertThat(request(port, "/trace/no-listener", null).status).isEqualTo(200);
        assertThat(listener.started).isEmpty();
        assertThat(VertxHttpTraceRuntime.inFlightRequests()).isZero();
    }

    @Test void disconnectCleansStateWithoutInventingHttpSuccess() throws Exception {
        int port = listen(request -> vertx.setTimer(10, timer -> request.response().close()));
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write("GET /trace/close HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertThat(socket.getInputStream().read()).isEqualTo(-1);
        }
        awaitCompletion(1);
        assertThat(listener.completed.values().iterator().next()).containsEntry("httpAborted", "true")
                .doesNotContainKey("httpStatus");
        assertThat(VertxHttpTraceRuntime.inFlightRequests()).isZero();
    }

    @Test void wrapperPreservesOriginalExceptionAndRestoresCallerContext() {
        IllegalArgumentException original = new IllegalArgumentException("original");
        Handler<Object> delegate = request -> { throw original; };
        @SuppressWarnings("unchecked") Handler<Object> wrapped = (Handler<Object>) VertxHttpTraceRuntime.wrapServerHandler(delegate);
        TraceSnapshot ambient = new TraceSnapshot("ambient", "ambient", null, null, null);
        try (TraceScope scope = TraceContext.activate(ambient)) {
            assertThatThrownBy(() -> wrapped.handle(new FakeRequest())).isSameAs(original);
            assertThat(TraceContext.current()).isSameAs(ambient);
        }
        assertThat(listener.completed.values().iterator().next())
                .containsEntry("filterException", IllegalArgumentException.class.getName()).doesNotContainKey("httpStatus");
        assertThat(TraceContext.current()).isNull();
    }

    @Test void peerDisconnectCleansIncompleteRequest() throws Exception {
        CompletableFuture<String> started = new CompletableFuture<>();
        int port = listen(request -> started.complete(TraceContext.currentSegmentId()));
        String segment;
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.getOutputStream().write("GET /trace/peer-close HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            segment = started.get(10, TimeUnit.SECONDS);
            assertThat(segment).isNotNull();
            assertThat(VertxHttpTraceRuntime.inFlightRequests()).isEqualTo(1);
        }
        awaitCompletion(1);
        assertThat(listener.completed.get(segment)).containsEntry("httpAborted", "true")
                .doesNotContainKey("httpStatus");
        assertThat(VertxHttpTraceRuntime.inFlightRequests()).isZero();
    }

    @Test void observerFailureCannotBreakResponseOrLeakContext() throws Exception {
        listener.throwOnBegin = true;
        int port = listen(request -> {
            if (TraceContext.current() != null) callbackFailures.add("failed listener leaked into handler");
            request.response().end("ok");
        });
        assertThat(request(port, "/trace/failure", null).body).isEqualTo("ok");
        assertThat(VertxHttpTraceRuntime.inFlightRequests()).isZero();
    }

    @Test void incompleteRequestRegistryIsBoundedAndUnregisterClearsIt() {
        List<FakeRequest> liveRequests = new ArrayList<>();
        @SuppressWarnings("unchecked") Handler<Object> wrapped = (Handler<Object>) VertxHttpTraceRuntime.wrapServerHandler((Handler<Object>) request -> { });
        for (int i = 0; i < 1100; i++) {
            FakeRequest request = new FakeRequest();
            liveRequests.add(request);
            wrapped.handle(request);
        }
        assertThat(VertxHttpTraceRuntime.inFlightRequests()).isEqualTo(1024);
        assertThat(listener.started).hasSize(1024);
        VertxHttpTraceRuntime.unregisterListener(listener);
        assertThat(VertxHttpTraceRuntime.inFlightRequests()).isZero();
        assertThat(liveRequests).hasSize(1100);
    }

    int listen(Handler<HttpServerRequest> handler) throws Exception {
        CompletableFuture<HttpServer> ready = new CompletableFuture<>();
        vertx.createHttpServer().requestHandler(handler).listen(0, "127.0.0.1", result -> {
            if (result.succeeded()) ready.complete(result.result()); else ready.completeExceptionally(result.cause());
        });
        return ready.get(10, TimeUnit.SECONDS).actualPort();
    }

    void sameContext(String expected, String boundary) {
        if (expected == null || !expected.equals(TraceContext.currentSegmentId())) {
            callbackFailures.add(boundary + ": wrong request context");
        }
    }

    void awaitCompletion(int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (listener.completed.size() < count && System.nanoTime() < deadline) Thread.sleep(5);
        assertThat(listener.completed).hasSize(count);
    }

    HttpURLConnection connection(int port, String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        return connection;
    }

    Response request(int port, String path, String body) throws Exception {
        HttpURLConnection connection = connection(port, path);
        if (body != null) {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            connection.getOutputStream().close();
        }
        return read(connection);
    }

    Response read(HttpURLConnection connection) throws Exception {
        try {
            int status = connection.getResponseCode();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                if (stream != null) {
                    byte[] buffer = new byte[1024];
                    int read;
                    while ((read = stream.read(buffer)) != -1) bytes.write(buffer, 0, read);
                }
            }
            return new Response(status, bytes.toString("UTF-8"), connection.getHeaderField("X-TraceLens-Id"));
        } finally { connection.disconnect(); }
    }

    static final class Response {
        final int status;
        final String body;
        final String segment;
        Response(int status, String body, String segment) { this.status = status; this.body = body; this.segment = segment; }
    }

    static final class Recording implements HttpTraceRuntime.Listener {
        final Map<String, TraceSnapshot> started = new ConcurrentHashMap<>();
        final Map<String, Map<String, String>> completed = new ConcurrentHashMap<>();
        final AtomicInteger completionCount = new AtomicInteger();
        volatile boolean distributed = true;
        volatile boolean decline;
        volatile boolean throwOnBegin;
        public boolean distributedTracingEnabled() { return distributed; }
        public List<String> httpIncludePaths() { return Collections.singletonList("/trace"); }
        public TraceSnapshot begin(String label, String user, Map<String, String> attributes) {
            if (throwOnBegin) {
                TraceContext.activate(new TraceSnapshot("bad", "bad", null, null, null));
                throw new IllegalStateException("observer failed");
            }
            if (decline || attributes.containsKey("captureSkipped")) return null;
            String segment = UUID.randomUUID().toString();
            TraceSnapshot snapshot = new TraceSnapshot(attributes.getOrDefault("distributedTraceId", segment), segment,
                    label, user, attributes);
            started.put(segment, snapshot);
            return snapshot;
        }
        public void complete(String segment, Map<String, String> attributes) {
            completionCount.incrementAndGet();
            completed.put(segment, new LinkedHashMap<>(attributes));
        }
    }

    public static final class FakeRequest {
        final FakeResponse response = new FakeResponse();
        public String path() { return "/trace/fake"; }
        public HttpMethod method() { return HttpMethod.GET; }
        public FakeResponse response() { return response; }
        public String getHeader(String key) { return null; }
        public io.vertx.core.MultiMap headers() { return io.vertx.core.MultiMap.caseInsensitiveMultiMap(); }
    }

    public static final class FakeResponse {
        public boolean ended() { return false; }
        public boolean headWritten() { return false; }
        public int getStatusCode() { return 200; }
        public long bytesWritten() { return 0; }
        public FakeResponse putHeader(String key, String value) { return this; }
        public io.vertx.core.MultiMap headers() { return io.vertx.core.MultiMap.caseInsensitiveMultiMap(); }
    }
}
