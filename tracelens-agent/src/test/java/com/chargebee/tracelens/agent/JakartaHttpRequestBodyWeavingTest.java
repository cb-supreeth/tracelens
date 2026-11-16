package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.HttpTraceRuntime;
import com.chargebee.tracelens.bridge.TraceContext;
import com.chargebee.tracelens.bridge.TraceSnapshot;
import com.chargebee.tracelens.core.http.HttpPayloadSanitizer;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Actual Jakarta Tomcat in a child-first loader alongside the javax test dependency. */
class JakartaHttpRequestBodyWeavingTest {
    private static URLClassLoader tomcat;
    private final Recording listener = new Recording();
    @BeforeAll static void install() throws Exception {
        String jar = System.getProperty("tracelens.test.tomcatJakartaJar");
        assertThat(jar).as("isolated Jakarta Tomcat test fixture").isNotBlank();
        tomcat = new URLClassLoader(new URL[]{new File(jar).toURI().toURL()}, JakartaHttpRequestBodyWeavingTest.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    if (name.startsWith("org.apache.catalina.") || name.startsWith("org.apache.coyote.")
                            || name.startsWith("org.apache.tomcat.") || name.startsWith("org.apache.juli.") || name.startsWith("jakarta.servlet.")) {
                        Class<?> value = findLoadedClass(name);
                        if (value == null) value = findClass(name);
                        if (resolve) resolveClass(value);
                        return value;
                    }
                    return super.loadClass(name, resolve);
                }
            }
        };
        HttpRequestBodyInstrumentation.install(ByteBuddyAgent.install());
    }
    @AfterEach void cleanup() { HttpTraceRuntime.unregisterListener(listener); TraceContext.clear(); }
    @AfterAll static void close() throws Exception { if (tomcat != null) tomcat.close(); }

    @Test void jakartaJsonAndNativeByteBufferReadsPreservePositionsAndSanitize() throws Exception {
        Fixture fixture = fixture("{\"name\":\"EMS\",\"token\":\"secret\"}", "application/json");
        Object scope = HttpTraceRuntime.enter(fixture.request, fixture.response);
        assertThat(scope).isNotNull();
        InputStream stream = (InputStream) call(fixture.request, "getInputStream");
        assertThat(fixture.reads).hasValue(0);
        ByteBuffer buffer = ByteBuffer.allocate(1024); buffer.position(3);
        int count = (Integer) stream.getClass().getMethod("read", ByteBuffer.class).invoke(stream, buffer);
        assertThat(count).isEqualTo(fixture.body.length);
        assertThat(buffer.position()).isEqualTo(3);
        byte[] observed = new byte[count]; buffer.get(observed);
        assertThat(observed).isEqualTo(fixture.body);
        HttpTraceRuntime.exit(scope, null);
        assertThat(listener.completed).containsEntry("httpRequestBodyStatus", "captured");
        assertThat(listener.completed.get("httpRequestBody")).contains("EMS", "[REDACTED]").doesNotContain("secret");
    }

    @Test void jakartaFormParameterParsingUsesIdenticalPassiveCapture() throws Exception {
        Fixture fixture = fixture("feature=ems&value=one&value=two&password=secret", "application/x-www-form-urlencoded");
        Object scope = HttpTraceRuntime.enter(fixture.request, fixture.response);
        assertThat(fixture.reads).hasValue(0);
        @SuppressWarnings("unchecked") Map<String, String[]> params = (Map<String, String[]>) call(fixture.request, "getParameterMap");
        assertThat(params.get("feature")).containsExactly("ems");
        assertThat(params.get("value")).containsExactly("one", "two");
        HttpTraceRuntime.exit(scope, null);
        assertThat(listener.completed).containsEntry("httpRequestBodyStatus", "captured").containsEntry("httpRequestBodyFormat", "form");
        assertThat(listener.completed.get("httpRequestBody")).contains("ems", "one", "two", "[REDACTED]").doesNotContain("secret");
    }

    private Fixture fixture(String text, String contentType) throws Exception {
        HttpTraceRuntime.registerListener(listener);
        Class<?> connectorType = tomcat.loadClass("org.apache.catalina.connector.Connector");
        Class<?> requestType = tomcat.loadClass("org.apache.catalina.connector.Request");
        Class<?> responseType = tomcat.loadClass("org.apache.catalina.connector.Response");
        Class<?> coyoteType = tomcat.loadClass("org.apache.coyote.Request");
        Class<?> coyoteResponseType = tomcat.loadClass("org.apache.coyote.Response");
        Object connector = connectorType.getConstructor().newInstance();
        connectorType.getMethod("setParseBodyMethods", String.class).invoke(connector, "POST");
        Fixture result = new Fixture(text.getBytes(StandardCharsets.UTF_8),
                requestType.getConstructor(connectorType).newInstance(connector),
                responseType.getConstructor().newInstance());
        Object coyote = coyoteType.getConstructor().newInstance();
        Object method = call(coyote, "method"); method.getClass().getMethod("setString", String.class).invoke(method, "POST");
        Object uri = call(coyote, "requestURI"); uri.getClass().getMethod("setString", String.class).invoke(uri, "/api/features");
        Object headers = call(coyote, "getMimeHeaders");
        Object contentTypeHeader = headers.getClass().getMethod("addValue", String.class).invoke(headers, "Content-Type");
        contentTypeHeader.getClass().getMethod("setString", String.class).invoke(contentTypeHeader, contentType);
        coyoteType.getMethod("setContentLength", long.class).invoke(coyote, (long) result.body.length);
        Class<?> inputType = tomcat.loadClass("org.apache.coyote.InputBuffer");
        Object input = Proxy.newProxyInstance(tomcat, new Class<?>[]{inputType}, (proxy, operation, args) -> {
            if (operation.getName().equals("available")) return 0;
            if (operation.getName().equals("doRead")) {
                if (result.reads.getAndIncrement() > 0) return -1;
                args[0].getClass().getMethod("setByteBuffer", ByteBuffer.class).invoke(args[0], ByteBuffer.wrap(result.body));
                return result.body.length;
            }
            return null;
        });
        coyoteType.getMethod("setInputBuffer", inputType).invoke(coyote, input);
        requestType.getMethod("setCoyoteRequest", coyoteType).invoke(result.request, coyote);
        coyoteType.getMethod("setNote", int.class, Object.class).invoke(coyote, 1, result.request);
        Object coyoteResponse = coyoteResponseType.getConstructor().newInstance();
        responseType.getMethod("setCoyoteResponse", coyoteResponseType).invoke(result.response, coyoteResponse);
        coyoteType.getMethod("setResponse", coyoteResponseType).invoke(coyote, coyoteResponse);
        coyoteResponseType.getMethod("setRequest", coyoteType).invoke(coyoteResponse, coyote);
        responseType.getMethod("setRequest", requestType).invoke(result.response, result.request);
        requestType.getMethod("setResponse", responseType).invoke(result.request, result.response);
        return result;
    }
    private static Object call(Object value, String name) throws Exception { return value.getClass().getMethod(name).invoke(value); }
    private static class Fixture {
        final byte[] body;
        final Object request, response;
        final AtomicInteger reads = new AtomicInteger();
        Fixture(byte[] body, Object request, Object response) { this.body = body; this.request = request; this.response = response; }
    }
    private static class Recording implements HttpTraceRuntime.Listener {
        Map<String, String> completed = new LinkedHashMap<>();
        public boolean captureHttpRequestBodies() { return true; }
        public java.util.List<String> httpIncludePaths() { return Collections.singletonList("/api"); }
        public Map<String, String> sanitizeHttpPayload(String input, String format, boolean query) { return HttpPayloadSanitizer.sanitize(input, format, query, 4096); }
        public TraceSnapshot begin(String label, String user, Map<String, String> attributes) { return new TraceSnapshot("trace", "segment", "lens", "user", attributes); }
        public void complete(String id, Map<String, String> attributes) { completed = attributes; }
    }
}
