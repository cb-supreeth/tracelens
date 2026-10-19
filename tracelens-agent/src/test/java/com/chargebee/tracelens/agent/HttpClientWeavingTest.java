package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.HttpClientTraceRuntime;
import com.chargebee.tracelens.bridge.TraceContext;
import com.chargebee.tracelens.bridge.TraceScope;
import com.chargebee.tracelens.bridge.TraceSnapshot;
import com.chargebee.tracelens.bridge.W3CTraceContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real woven java.base traffic, including HTTPS delegation and unsafe redirects. */
class HttpClientWeavingTest {
    @TempDir Path temp;

    @Test void jdkHttpAndHttpsAreLinkedOnceWithoutLeakingAcrossRedirects() throws Exception {
        Instrumentation instrumentation = ByteBuddyAgent.install();
        injectBridge(instrumentation);
        HttpClientInstrumentation.install(instrumentation);
        assertThat(HttpClientTraceRuntime.isRedirectGuardReady()).isTrue();

        AtomicReference<String> seenParent = new AtomicReference<>();
        AtomicReference<String> seenState = new AtomicReference<>();
        HttpServer external = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        external.createContext("/", exchange -> reply(exchange, seenParent, seenState));
        external.start();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> reply(exchange, seenParent, seenState));
        server.createContext("/redirect", exchange -> {
            exchange.getRequestBody().close();
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + external.getAddress().getPort() + "/outside");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.start();
        SSLContext tls = tlsContext();
        HttpsServer secure = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        secure.setHttpsConfigurator(new HttpsConfigurator(tls));
        secure.createContext("/", exchange -> reply(exchange, seenParent, seenState));
        secure.start();
        Map<String, Map<String, String>> calls = new ConcurrentHashMap<>();
        HttpClientTraceRuntime.Listener listener = (segment, call) -> calls.put(call.get("spanId"), call);
        HttpClientTraceRuntime.registerListener(listener);
        HttpClientTraceRuntime.configure(true, Arrays.asList("127.0.0.1:" + server.getAddress().getPort(),
                "127.0.0.1:" + secure.getAddress().getPort()));
        W3CTraceContext parent = W3CTraceContext.parse("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01", "test=fixture");
        Map<String, String> attrs = new HashMap<>();
        attrs.put("spanId", parent.getSpanId());
        attrs.put("traceFlags", parent.getFlags());
        attrs.put("tracestate", parent.getTracestate());
        TraceSnapshot snapshot = new TraceSnapshot(parent.getTraceId(), "local-segment", "fixture", "private-user", attrs);
        HttpURLConnection untraced = connection("http://127.0.0.1:" + external.getAddress().getPort() + "/untraced");
        assertThat(untraced.getResponseCode()).isEqualTo(200);
        untraced.getInputStream().close();
        assertThat(calls).isEmpty();
        assertThat(seenParent.get()).isNull();
        try (TraceScope scope = TraceContext.activate(snapshot)) {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            HttpURLConnection get = connection(base + "/read;jsessionid=private?token=private");
            assertThat(get.getResponseCode()).isEqualTo(200);
            get.getInputStream().close();
            get.disconnect();
            assertLinked(calls, parent, seenParent.get(), "GET", "/read", "200");
            assertThat(seenState.get()).isEqualTo("test=fixture");
            assertThat(calls).hasSize(1);
            assertThat(calls.values().toString()).doesNotContain("private");

            HttpURLConnection post = connection(base + "/post");
            post.setRequestMethod("POST");
            post.setDoOutput(true);
            post.getOutputStream().write(new byte[]{1, 2, 3});
            post.getOutputStream().close();
            assertThat(post.getResponseCode()).isEqualTo(200);
            post.getInputStream().close();
            post.disconnect();
            assertLinked(calls, parent, seenParent.get(), "POST", "/post", "200");
            assertThat(calls).hasSize(2);

            HttpURLConnection conflict = connection(base + "/conflict");
            conflict.setRequestProperty("traceparent", parent.header());
            assertThat(conflict.getResponseCode()).isEqualTo(200);
            conflict.getInputStream().close();
            assertThat(seenParent.get()).isEqualTo(parent.header());
            assertThat(calls.values()).anyMatch(c -> "CONFLICT".equals(c.get("propagationStatus")));

            HttpURLConnection redirect = connection(base + "/redirect");
            assertThat(redirect.getResponseCode()).isEqualTo(200);
            redirect.getInputStream().close();
            assertThat(seenParent.get()).isNull();
            assertThat(seenState.get()).isNull();
            assertThat(calls.values()).anyMatch(c -> "REDIRECT_BLOCKED".equals(c.get("propagationStatus")));

            HttpURLConnection error = connection(base + "/error");
            assertThat(error.getResponseCode()).isEqualTo(503);
            error.getErrorStream().close();
            assertThat(calls.values()).anyMatch(c -> "503".equals(c.get("httpStatus")) && "ERROR".equals(c.get("status")));

            HttpsURLConnection https = (HttpsURLConnection) connection("https://127.0.0.1:" + secure.getAddress().getPort() + "/secure");
            https.setSSLSocketFactory(tls.getSocketFactory());
            https.setHostnameVerifier((host, session) -> true); // Isolated self-signed test fixture only.
            assertThat(https.getResponseCode()).isEqualTo(200);
            https.getInputStream().close();
            https.disconnect();
            assertLinked(calls, parent, seenParent.get(), "GET", "/secure", "200");
            assertThat(calls).hasSize(6);

            // If a future JDK breaks cleanup after preflight, fail this redirected
            // request closed instead of leaking already-injected correlation IDs.
            java.lang.reflect.Field remover = HttpClientTraceRuntime.class.getDeclaredField("removeHeader");
            remover.setAccessible(true);
            Object realRemover = remover.get(null);
            seenParent.set("not-contacted");
            try {
                remover.set(null, null);
                HttpURLConnection unsafe = connection(base + "/redirect");
                assertThatThrownBy(unsafe::getResponseCode).isInstanceOf(java.io.IOException.class);
                assertThat(seenParent.get()).isEqualTo("not-contacted");
            } finally { remover.set(null, realRemover); }
            assertThat(calls).hasSize(7);

            HttpClientTraceRuntime.disableRedirectGuard();
            HttpURLConnection noGuard = connection(base + "/guard-unavailable");
            assertThat(noGuard.getResponseCode()).isEqualTo(200);
            noGuard.getInputStream().close();
            assertThat(seenParent.get()).isNull();
            assertThat(calls.values()).anyMatch(c -> "GUARD_UNAVAILABLE".equals(c.get("propagationStatus")));
            HttpClientTraceRuntime.enableRedirectGuard();

            // The external fixture has no TraceLens server instrumentation and is
            // not trusted for propagation, but the caller still observes the hop.
            String outside = "http://127.0.0.1:" + external.getAddress().getPort();
            HttpURLConnection observed = connection(outside + "/dependency;session=private?token=private#private");
            assertThat(observed.getResponseCode()).isEqualTo(200);
            observed.getInputStream().close();
            assertThat(seenParent.get()).isNull();
            assertThat(seenState.get()).isNull();
            assertObserved(calls, "/dependency", "GET", "NOT_ALLOWLISTED", parent.getTraceId());
            assertThat(calls.values().toString()).doesNotContain("private");
            assertThat(calls).hasSize(9);

            // Not being allowlisted must not remove another tracer's headers.
            HttpURLConnection externalConflict = connection(outside + "/external-conflict");
            externalConflict.setRequestProperty("traceparent", parent.header());
            externalConflict.setRequestProperty("tracestate", "other=fixture");
            assertThat(externalConflict.getResponseCode()).isEqualTo(200);
            externalConflict.getInputStream().close();
            assertThat(seenParent.get()).isEqualTo(parent.header());
            assertThat(seenState.get()).isEqualTo("other=fixture");
            assertObserved(calls, "/external-conflict", "GET", "NOT_ALLOWLISTED", parent.getTraceId());

            // Ordinary local-only snapshots have UUID IDs and no W3C span. Even
            // an allowlisted destination receives no observer-owned headers.
            HttpClientTraceRuntime.configure(true, false, Collections.singletonList("127.0.0.1:" + server.getAddress().getPort()));
            String localId = "cccccccc-cccc-cccc-cccc-cccccccccccc";
            TraceSnapshot local = new TraceSnapshot(localId, "local-only", "fixture", "private-user", Collections.emptyMap());
            try (TraceScope localScope = TraceContext.activate(local)) {
                HttpURLConnection localOnly = connection(base + "/local-only");
                localOnly.setDoOutput(true); // JDK changes the default GET to POST internally.
                localOnly.getOutputStream().write(new byte[]{4, 5});
                localOnly.getOutputStream().close();
                assertThat(localOnly.getResponseCode()).isEqualTo(200);
                localOnly.getInputStream().close();
                assertThat(seenParent.get()).isNull();
                assertThat(seenState.get()).isNull();
                assertObserved(calls, "/local-only", "POST", "DISABLED", localId);

                HttpClientTraceRuntime.configure(true, true, Collections.singletonList("127.0.0.1:" + server.getAddress().getPort()));
                HttpURLConnection noContext = connection(base + "/no-w3c-context");
                assertThat(noContext.getResponseCode()).isEqualTo(200);
                noContext.getInputStream().close();
                assertThat(seenParent.get()).isNull();
                assertThat(seenState.get()).isNull();
                assertObserved(calls, "/no-w3c-context", "GET", "NO_CONTEXT", localId);
            }
            assertThat(calls).hasSize(12);

            HttpClientTraceRuntime.configure(true, false, Collections.singletonList("127.0.0.1:" + server.getAddress().getPort()));
            HttpURLConnection localRedirect = connection(base + "/redirect");
            assertThat(localRedirect.getResponseCode()).isEqualTo(200);
            localRedirect.getInputStream().close();
            assertThat(seenParent.get()).isNull();
            assertThat(seenState.get()).isNull();
            assertObserved(calls, "/redirect", "GET", "DISABLED", parent.getTraceId());
            assertThat(calls).hasSize(13);

            HttpClientTraceRuntime.configure(true, true, Collections.singletonList("127.0.0.1:" + server.getAddress().getPort()));
            HttpURLConnection stateConflict = connection(base + "/state-conflict");
            stateConflict.setRequestProperty("tracestate", "other=fixture");
            assertThat(stateConflict.getResponseCode()).isEqualTo(200);
            stateConflict.getInputStream().close();
            assertThat(seenParent.get()).isNull();
            assertThat(seenState.get()).isEqualTo("other=fixture");
            assertObserved(calls, "/state-conflict", "GET", "CONFLICT", parent.getTraceId());

            // Revoking capture/propagation must keep enough live state to remove
            // previously owned headers before a later write (not just redirects).
            HttpURLConnection revoked = connection(base + "/revoked-before-write");
            Object pending = HttpClientTraceRuntime.enter(revoked);
            assertThat(pending).isNotNull();
            assertThat(revoked.getRequestProperty("traceparent")).isNotNull();
            HttpClientTraceRuntime.configure(false, false, Collections.emptyList());
            assertThat(revoked.getResponseCode()).isEqualTo(200);
            revoked.getInputStream().close();
            HttpClientTraceRuntime.exit(pending, "getResponseCode", 200, null);
            assertThat(seenParent.get()).isNull();
            assertThat(seenState.get()).isNull();
            assertObserved(calls, "/revoked-before-write", "GET", "REDIRECT_BLOCKED", parent.getTraceId());
            assertThat(calls).hasSize(15);

            // Preserve explicitly validated W3C identity across the existing SQS
            // wire carrier and a real Queueman lifecycle before the next HTTP hop.
            HttpClientTraceRuntime.configure(true, true, Collections.singletonList("127.0.0.1:" + server.getAddress().getPort()));
            // Delay core/lifecycle linkage until after bootstrap bridge injection;
            // eager verifier resolution would create a second app-loader SPI.
            Class.forName("com.chargebee.tracelens.agent.MixedMessagingHttpFixture")
                    .getMethod("run", String.class).invoke(null, base + "/after-queue");
            assertLinked(calls, parent, seenParent.get(), "GET", "/after-queue", "200");
            assertThat(seenState.get()).isEqualTo("test=fixture");
            assertObserved(calls, "/after-queue", "GET", "INJECTED", parent.getTraceId());
            assertThat(calls).hasSize(16);

            HttpClientTraceRuntime.configure(false, Collections.emptyList());
            HttpURLConnection disabled = connection(base + "/disabled");
            assertThat(disabled.getResponseCode()).isEqualTo(200);
            disabled.getInputStream().close();
            assertThat(seenParent.get()).isNull();
            assertThat(calls).hasSize(16);
        } finally {
            HttpClientTraceRuntime.unregisterListener(listener);
            HttpClientTraceRuntime.configure(false, Collections.emptyList());
            server.stop(0);
            external.stop(0);
            secure.stop(0);
        }
        assertThat(TraceContext.current()).isNull();
    }

    private static void assertObserved(Map<String, Map<String, String>> calls, String path, String method,
                                       String propagation, String traceId) {
        assertThat(calls.values()).anySatisfy(call -> {
            assertThat(call).containsEntry("path", path).containsEntry("httpMethod", method)
                    .containsEntry("propagationStatus", propagation).containsEntry("traceId", traceId)
                    .containsEntry("httpStatus", "200").containsEntry("status", "COMPLETED")
                    .containsEntry("timingBoundary", "response-headers").containsKeys("spanId", "peer", "durationNanos");
        });
    }

    private static void assertLinked(Map<String, Map<String, String>> calls, W3CTraceContext parent,
                                     String header, String method, String path, String status) {
        W3CTraceContext child = W3CTraceContext.parse(header, null);
        assertThat(child).isNotNull();
        assertThat(child.getTraceId()).isEqualTo(parent.getTraceId());
        assertThat(child.getSpanId()).isNotEqualTo(parent.getSpanId());
        assertThat(calls.get(child.getSpanId())).containsEntry("parentSpanId", parent.getSpanId())
                .containsEntry("httpMethod", method).containsEntry("path", path)
                .containsEntry("httpStatus", status).containsEntry("status", "COMPLETED");
    }

    private static void reply(HttpExchange exchange, AtomicReference<String> parent, AtomicReference<String> state) throws java.io.IOException {
        parent.set(exchange.getRequestHeaders().getFirst("traceparent"));
        state.set(exchange.getRequestHeaders().getFirst("tracestate"));
        exchange.getRequestBody().close();
        exchange.sendResponseHeaders(exchange.getRequestURI().getPath().equals("/error") ? 503 : 200, 2);
        exchange.getResponseBody().write(new byte[]{'o', 'k'});
        exchange.close();
    }

    private static HttpURLConnection connection(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        return connection;
    }

    private SSLContext tlsContext() throws Exception {
        Path store = temp.resolve("fixture.p12");
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "fixture-pass",
                "-keypass", "fixture-pass", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-noprompt")
                .redirectErrorStream(true).redirectOutput(temp.resolve("keytool.log").toFile()).start();
        assertThat(keytool.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(keytool.exitValue()).isZero();
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(store)) { keys.load(input, "fixture-pass".toCharArray()); }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, "fixture-pass".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), new TrustManager[]{new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] certs, String auth) { }
            public void checkServerTrusted(X509Certificate[] certs, String auth) { }
        }}, new SecureRandom());
        return context;
    }

    private void injectBridge(Instrumentation instrumentation) throws Exception {
        Path classes = Path.of("..", "tracelens-bridge", "build/classes/java/main").toAbsolutePath().normalize();
        Path jar = temp.resolve("bridge.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar)); java.util.stream.Stream<Path> paths = Files.walk(classes)) {
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
