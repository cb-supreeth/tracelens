package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.ApacheHttpClientTraceRuntime;
import com.chargebee.tracelens.bridge.HttpClientTraceRuntime;
import com.chargebee.tracelens.bridge.TraceContext;
import com.chargebee.tracelens.bridge.TraceScope;
import com.chargebee.tracelens.bridge.TraceSnapshot;
import com.chargebee.tracelens.bridge.W3CTraceContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.apache.http.HttpHost;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Real synchronous Apache 4 dispatch; tests never contact application services. */
class ApacheHttpClientWeavingTest {
    @TempDir Path temp;

    @Test void capturesAllExecuteShapesAndGuardsRedirectsWithoutReadingPrivateData() throws Exception {
        Instrumentation instrumentation = ByteBuddyAgent.install();
        injectBridge(instrumentation);
        ApacheHttpClientInstrumentation.install(instrumentation);

        AtomicReference<String> seenParent = new AtomicReference<>();
        AtomicReference<String> seenState = new AtomicReference<>();
        HttpServer external = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        external.createContext("/", exchange -> reply(exchange, seenParent, seenState));
        external.start();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> reply(exchange, seenParent, seenState));
        server.createContext("/redirect-outside", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + external.getAddress().getPort() + "/outside");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/redirect-inside", exchange -> {
            exchange.getResponseHeaders().set("Location", "/inside");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        String outside = "http://127.0.0.1:" + external.getAddress().getPort();
        Map<String, Map<String, String>> calls = new LinkedHashMap<>();
        HttpClientTraceRuntime.Listener listener = (segment, call) -> calls.put(call.get("spanId"), call);
        ApacheHttpClientTraceRuntime.registerListener(listener);
        ApacheHttpClientTraceRuntime.configure(true, true, Collections.singletonList("127.0.0.1:" + server.getAddress().getPort()));
        W3CTraceContext parent = W3CTraceContext.parse("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01", "test=fixture");
        Map<String, String> attrs = new HashMap<>();
        attrs.put("spanId", parent.getSpanId());
        attrs.put("traceFlags", parent.getFlags());
        attrs.put("tracestate", parent.getTracestate());
        TraceSnapshot snapshot = new TraceSnapshot(parent.getTraceId(), "apache-segment", "fixture", "private-user", attrs);
        try (CloseableHttpClient client = HttpClients.custom().disableAutomaticRetries().build();
             CloseableHttpClient minimal = HttpClients.createMinimal()) {
            assertStatus(client, new HttpGet(base + "/untraced"), 200);
            assertThat(calls).isEmpty();
            assertThat(seenParent.get()).isNull();
            try (TraceScope scope = TraceContext.activate(snapshot)) {
                HttpGet read = new HttpGet(base + "/read;session=private?token=private#private");
                read.setHeader("Authorization", "Basic private-credential");
                read.setHeader("Cookie", "private-cookie");
                try (CloseableHttpResponse response = client.execute(read)) {
                    assertThat(response.getStatusLine().getStatusCode()).isEqualTo(200);
                    // The observer must leave the streaming entity untouched for the caller.
                    assertThat(EntityUtils.toString(response.getEntity())).isEqualTo("private-response-body");
                }
                assertCall(calls, "/read", "GET", "200", "INJECTED");
                assertThat(W3CTraceContext.parse(seenParent.get(), seenState.get()).getTraceId()).isEqualTo(parent.getTraceId());
                assertThat(seenParent.get()).isNotEqualTo(parent.header());
                assertThat(seenState.get()).isEqualTo("test=fixture");
                assertThat(read.getFirstHeader("traceparent")).isNull();
                assertThat(read.getFirstHeader("Authorization").getValue()).isEqualTo("Basic private-credential");
                assertThat(calls).hasSize(1);

                HttpPost post = new HttpPost(base + "/post?secret=private");
                post.setEntity(new StringEntity("private-request-body"));
                String body = client.execute(post, response -> EntityUtils.toString(response.getEntity()));
                assertThat(body).isEqualTo("private-response-body");
                assertCall(calls, "/post", "POST", "200", "INJECTED");
                assertThat(calls).hasSize(2); // Response-handler overloads must not double-count.

                try (CloseableHttpResponse response = client.execute(new HttpHost("127.0.0.1", server.getAddress().getPort()),
                        new BasicHttpRequest("GET", "/relative?private=value"))) {
                    assertThat(response.getStatusLine().getStatusCode()).isEqualTo(200);
                }
                assertCall(calls, "/relative", "GET", "200", "INJECTED");
                assertStatus(minimal, new HttpGet(base + "/minimal"), 200);
                assertCall(calls, "/minimal", "GET", "200", "INJECTED");

                HttpGet conflict = new HttpGet(base + "/conflict");
                conflict.setHeader("traceparent", parent.header());
                conflict.setHeader("tracestate", "other=fixture");
                assertStatus(client, conflict, 200);
                assertThat(seenParent.get()).isEqualTo(parent.header());
                assertThat(seenState.get()).isEqualTo("other=fixture");
                assertCall(calls, "/conflict", "GET", "200", "CONFLICT");

                HttpGet stateConflict = new HttpGet(base + "/state-conflict");
                stateConflict.setHeader("tracestate", "other=fixture");
                assertStatus(client, stateConflict, 200);
                assertThat(seenParent.get()).isNull();
                assertThat(seenState.get()).isEqualTo("other=fixture");
                assertCall(calls, "/state-conflict", "GET", "200", "CONFLICT");

                assertStatus(client, new HttpGet(base + "/redirect-inside"), 200);
                assertThat(seenParent.get()).isNotNull();
                assertCall(calls, "/redirect-inside", "GET", "200", "INJECTED");
                assertStatus(client, new HttpGet(base + "/redirect-outside"), 200);
                assertThat(seenParent.get()).isNull();
                assertThat(seenState.get()).isNull();
                assertCall(calls, "/redirect-outside", "GET", "200", "REDIRECT_BLOCKED");

                assertStatus(client, new HttpGet(outside + "/observed-only"), 200);
                assertThat(seenParent.get()).isNull();
                assertCall(calls, "/observed-only", "GET", "200", "NOT_ALLOWLISTED");
                HttpGet outsideConflict = new HttpGet(outside + "/external-conflict");
                outsideConflict.setHeader("traceparent", parent.header());
                assertStatus(client, outsideConflict, 200);
                assertThat(seenParent.get()).isEqualTo(parent.header());

                assertStatus(client, new HttpGet(base + "/error"), 503);
                assertThat(find(calls, "/error")).containsEntry("status", "ERROR").containsEntry("httpStatus", "503");
                int closedPort;
                try (ServerSocket socket = new ServerSocket(0)) { closedPort = socket.getLocalPort(); }
                assertThatThrownBy(() -> client.execute(new HttpGet("http://127.0.0.1:" + closedPort + "/transport-failure")))
                        .isInstanceOf(IOException.class);
                assertThat(find(calls, "/transport-failure")).containsEntry("status", "ERROR").containsKey("errorType");

                ApacheHttpClientTraceRuntime.configure(true, false, Collections.emptyList());
                assertStatus(client, new HttpGet(base + "/disabled"), 200);
                assertThat(seenParent.get()).isNull();
                assertCall(calls, "/disabled", "GET", "200", "DISABLED");
                ApacheHttpClientTraceRuntime.configure(true, true, Collections.singletonList("127.0.0.1:" + server.getAddress().getPort()));
                try (TraceScope local = TraceContext.activate(new TraceSnapshot("legacy-local-id", "legacy", "fixture", "private-user", Collections.emptyMap()))) {
                    assertStatus(client, new HttpGet(base + "/legacy"), 200);
                    assertThat(seenParent.get()).isNull();
                    assertCall(calls, "/legacy", "GET", "200", "NO_CONTEXT");
                }
                assertThat(calls).hasSize(14);
                assertThat(calls.values().toString()).doesNotContain("private", "secret=", "token=", "Cookie", "Authorization");
                assertThat(calls.values()).allSatisfy(call -> {
                    assertThat(Long.parseLong(call.get("durationNanos"))).isGreaterThanOrEqualTo(0);
                    assertThat(call).containsEntry("timingBoundary", "response-headers");
                });

                ApacheHttpClientTraceRuntime.registerListener((segment, call) -> { throw new RuntimeException("sink failure"); });
                assertStatus(client, new HttpGet(base + "/failing-listener"), 200);
            }
        } finally {
            ApacheHttpClientTraceRuntime.configure(false, false, Collections.emptyList());
            ApacheHttpClientTraceRuntime.registerListener(null);
            server.stop(0);
            external.stop(0);
        }
    }

    private static void assertStatus(CloseableHttpClient client, HttpGet request, int status) throws Exception {
        try (CloseableHttpResponse response = client.execute(request)) {
            assertThat(response.getStatusLine().getStatusCode()).isEqualTo(status);
            EntityUtils.consume(response.getEntity());
        }
    }

    private static Map<String, String> find(Map<String, Map<String, String>> calls, String path) {
        return calls.values().stream().filter(call -> path.equals(call.get("path"))).findFirst()
                .orElseThrow(() -> new AssertionError("No call for " + path + ": " + calls));
    }

    private static void assertCall(Map<String, Map<String, String>> calls, String path, String method, String status, String propagation) {
        assertThat(find(calls, path)).containsEntry("httpMethod", method).containsEntry("httpStatus", status)
                .containsEntry("status", "COMPLETED").containsEntry("propagationStatus", propagation);
    }

    private static void reply(HttpExchange exchange, AtomicReference<String> parent, AtomicReference<String> state) throws IOException {
        parent.set(exchange.getRequestHeaders().getFirst("traceparent"));
        state.set(exchange.getRequestHeaders().getFirst("tracestate"));
        exchange.getRequestBody().close();
        byte[] body = "private-response-body".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(exchange.getRequestURI().getPath().equals("/error") ? 503 : 200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void injectBridge(Instrumentation instrumentation) throws Exception {
        Path classes = Path.of("..", "tracelens-bridge", "build/classes/java/main").toAbsolutePath().normalize();
        Path jar = temp.resolve("bridge.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
             java.util.stream.Stream<Path> paths = Files.walk(classes)) {
            for (Iterator<Path> files = paths.filter(Files::isRegularFile).iterator(); files.hasNext();) {
                Path file = files.next();
                out.putNextEntry(new JarEntry(classes.relativize(file).toString()));
                Files.copy(file, out);
                out.closeEntry();
            }
        }
        instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(jar.toFile()));
    }
}
