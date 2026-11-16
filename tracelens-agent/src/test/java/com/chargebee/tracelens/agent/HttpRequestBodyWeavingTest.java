package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.HttpTraceRuntime;
import com.chargebee.tracelens.bridge.TraceContext;
import com.chargebee.tracelens.bridge.TraceSnapshot;
import com.chargebee.tracelens.core.http.HttpPayloadSanitizer;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Real Tomcat request + InputBuffer, without opening a server or changing business data. */
class HttpRequestBodyWeavingTest {
    private Recording listener;
    @BeforeAll static void install() {
        // Exercise retransformation with both types loaded, rather than allowing
        // superclass verification to load InputBuffer while Request is transformed.
        new Request(new Connector());
        new org.apache.catalina.connector.InputBuffer();
        HttpRequestBodyInstrumentation.install(ByteBuddyAgent.install());
    }
    @AfterEach void cleanup() { HttpTraceRuntime.unregisterListener(listener); TraceContext.clear(); }

    @Test void observesJsonOnlyWhenApplicationConsumesItAndDoesNotChangeBytes() throws Exception {
        Fixture fixture = fixture("{\"feature\":\"xero\",\"token\":\"secret\"}", "application/json");
        Object scope = enter(fixture, true);
        assertThat(fixture.reads).hasValue(0);
        assertThat(TraceContext.current().getAttributes()).doesNotContainKey("httpQueryParameters");
        InputStream stream = fixture.request.getInputStream();
        assertThat(fixture.reads).hasValue(0);
        byte[] bytes = stream.readAllBytes();
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo(fixture.text);
        assertThat(fixture.request.getInputStream()).isSameAs(stream);
        assertThat(stream.read()).isEqualTo(-1);
        HttpTraceRuntime.exit(scope, null);
        assertThat(listener.completed).containsEntry("httpRequestBodyStatus", "captured");
        assertThat(listener.completed.get("httpRequestBody")).contains("xero", "[REDACTED]").doesNotContain("secret");
        assertThat(listener.completed.get("httpQueryParameters")).contains("limit").doesNotContain("query-secret");
    }

    @Test void realTomcatGetParameterMapConsumesFormNormallyAndRetainsRepeatedValues() throws Exception {
        Fixture fixture = fixture("feature=xero&values=one&values=two&password=secret", "application/x-www-form-urlencoded");
        Object scope = enter(fixture, true);
        assertThat(fixture.reads).hasValue(0);
        Map<String, String[]> first = fixture.request.getParameterMap();
        assertThat(first.get("feature")).containsExactly("xero");
        assertThat(first.get("values")).containsExactly("one", "two");
        assertThat(first.get("password")).containsExactly("secret");
        assertThat(fixture.request.getParameterMap()).isSameAs(first);
        HttpTraceRuntime.exit(scope, null);
        assertThat(listener.completed).containsEntry("httpRequestBodyStatus", "captured").containsEntry("httpRequestBodyFormat", "form");
        assertThat(listener.completed.get("httpRequestBody")).contains("xero", "one", "two", "[REDACTED]").doesNotContain("secret");
    }

    @Test void readerCharacterCaptureAndAsyncThreadReadsDoNotNeedThreadLocalContext() throws Exception {
        Fixture fixture = fixture("{\"name\":\"café\"}", "application/json");
        fixture.request.setCharacterEncoding("UTF-8");
        Object scope = enter(fixture, true);
        Reader reader = fixture.request.getReader();
        StringBuilder applicationRead = new StringBuilder();
        Thread thread = new Thread(() -> {
            assertThat(TraceContext.current()).isNull();
            try { char[] buffer = new char[4]; int size; while ((size = reader.read(buffer)) >= 0) applicationRead.append(buffer, 0, size); }
            catch (Exception failure) { throw new AssertionError(failure); }
        });
        thread.start(); thread.join();
        assertThat(applicationRead.toString()).isEqualTo(fixture.text);
        HttpTraceRuntime.exit(scope, null);
        assertThat(listener.completed).containsEntry("httpRequestBodyStatus", "captured");
        assertThat(listener.completed.get("httpRequestBody")).contains("café");
    }

    @Test void asyncCompletionPersistsBodyConsumedAfterDispatchReturnedAndThenCleansBinding() throws Exception {
        Fixture fixture = fixture("{\"name\":\"async\"}", "application/json");
        Object scope = enter(fixture, true);
        InputStream stream = fixture.request.getInputStream();
        AtomicReference<AsyncListener> completion = new AtomicReference<>();
        fixture.request.asyncContext = (AsyncContext) java.lang.reflect.Proxy.newProxyInstance(
                AsyncContext.class.getClassLoader(), new Class<?>[]{AsyncContext.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("addListener")) completion.set((AsyncListener) arguments[0]);
                    if (method.getName().equals("getRequest")) return fixture.request;
                    if (method.getName().equals("getResponse")) return fixture.response;
                    return null;
                });
        HttpTraceRuntime.exit(scope, null);
        assertThat(TraceContext.current()).isNull();
        assertThat(listener.completed).isEmpty();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread async = new Thread(() -> {
            try {
                assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(fixture.text);
                completion.get().onComplete(new AsyncEvent(fixture.request.asyncContext, fixture.request, fixture.response));
            } catch (Throwable error) { failure.set(error); }
        });
        async.start(); async.join();
        assertThat(failure.get()).isNull();
        assertThat(listener.completed).containsEntry("httpRequestBodyStatus", "captured");
        assertThat(listener.completed.get("httpRequestBody")).contains("async");
        // Recycled buffers must not append to the just-completed request.
        int sanitizations = listener.sanitizations;
        assertThat(stream.read()).isEqualTo(-1);
        assertThat(listener.sanitizations).isEqualTo(sanitizations);
    }

    @Test void invalidUtf8ReadFailureAndResetDoNotExposeAnIncorrectBody() throws Exception {
        Fixture failed = fixture("{\"x\":1}", "application/json");
        java.io.IOException original = new java.io.IOException("application read failure");
        failed.coyote.setInputBuffer(input(handler -> { throw original; }));
        Object scope = enter(failed, true);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> failed.request.getInputStream().read())
                .isInstanceOf(org.apache.catalina.connector.ClientAbortException.class).hasCause(original);
        HttpTraceRuntime.exit(scope, null);
        assertThat(listener.completed).containsEntry("httpRequestBodyStatus", "incomplete").doesNotContainKey("httpRequestBody");

        Fixture invalid = fixture("{}", "application/json");
        invalid.coyote.setInputBuffer(input(handler -> {
            if (invalid.reads.getAndIncrement() > 0) return -1;
            handler.setByteBuffer(ByteBuffer.wrap(new byte[]{(byte) 0xc3, 0x28})); return 2;
        }));
        scope = enter(invalid, true); invalid.request.getInputStream().readAllBytes(); HttpTraceRuntime.exit(scope, null);
        assertThat(listener.completed).containsEntry("httpRequestBodyStatus", "omitted-invalid").doesNotContainKey("httpRequestBody");

        Fixture reset = fixture("{\"x\":1}", "application/json");
        scope = enter(reset, true);
        Reader reader = reset.request.getReader(); reader.mark(64); reader.read(); reader.reset();
        while (reader.read() >= 0) { }
        HttpTraceRuntime.exit(scope, null);
        assertThat(listener.completed).containsEntry("httpRequestBodyStatus", "incomplete").doesNotContainKey("httpRequestBody");
    }

    @Test void offExcludedAndNotRetainedTracesNeverInspectPayloads() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            Fixture fixture = fixture("{\"token\":\"secret\"}", "application/json");
            listener = new Recording();
            listener.enabled = mode != 0; listener.excluded = mode == 1; listener.notRetained = mode == 2;
            HttpTraceRuntime.registerListener(listener);
            Object scope = HttpTraceRuntime.enter(fixture.request, fixture.response);
            assertThat(fixture.request.getInputStream().readAllBytes()).isNotEmpty();
            HttpTraceRuntime.exit(scope, null);
            assertThat(listener.sanitizations).isZero();
            assertThat(listener.completed).doesNotContainKeys("httpRequestBody", "httpRequestBodyStatus", "httpQueryParameters");
        }
    }

    @Test void incompleteUnreadOversizedAndUnsupportedBodiesHaveStatusWithoutRawPrefix() throws Exception {
        for (String scenario : new String[]{"partial", "unread", "oversize", "binary", "compressed"}) {
            Fixture fixture = fixture("{\"token\":\"secret\"}", scenario.equals("binary") ? "application/octet-stream" : "application/json");
            if (scenario.equals("compressed")) fixture.coyote.getMimeHeaders().addValue("Content-Encoding").setString("gzip");
            Object scope = enter(fixture, true);
            if (scenario.equals("oversize")) { listener.max = 4; HttpTraceRuntime.exit(scope, null); fixture = fixture(fixture.text, "application/json"); scope = HttpTraceRuntime.enter(fixture.request, fixture.response); }
            if (scenario.equals("partial")) fixture.request.getInputStream().read();
            else if (!scenario.equals("unread")) fixture.request.getInputStream().readAllBytes();
            HttpTraceRuntime.exit(scope, null);
            assertThat(listener.completed).doesNotContainKey("httpRequestBody");
            String expected = scenario.equals("partial") ? "incomplete" : scenario.equals("unread") ? "not-consumed"
                    : scenario.equals("oversize") ? "omitted-too-large" : scenario.equals("compressed") ? "unsupported-encoding" : "unsupported-content-type";
            assertThat(listener.completed).containsEntry("httpRequestBodyStatus", expected);
        }
    }

    private Object enter(Fixture fixture, boolean enabled) {
        listener = new Recording(); listener.enabled = enabled; HttpTraceRuntime.registerListener(listener);
        return HttpTraceRuntime.enter(fixture.request, fixture.response);
    }
    private Fixture fixture(String text, String contentType) {
        Fixture fixture = new Fixture(text);
        fixture.coyote.method().setString("POST");
        fixture.coyote.requestURI().setString("/api/features");
        fixture.coyote.queryString().setString("limit=1&access_token=query-secret");
        fixture.coyote.getMimeHeaders().addValue("Content-Type").setString(contentType);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        fixture.coyote.setContentLength(bytes.length);
        fixture.coyote.setInputBuffer(input(handler -> {
            if (fixture.reads.getAndIncrement() > 0) return -1;
            handler.setByteBuffer(ByteBuffer.wrap(bytes)); return bytes.length;
        }));
        fixture.request.setCoyoteRequest(fixture.coyote);
        fixture.coyote.setNote(1, fixture.request);
        fixture.response.setCoyoteResponse(new org.apache.coyote.Response());
        fixture.coyote.setResponse(fixture.response.getCoyoteResponse());
        fixture.response.getCoyoteResponse().setRequest(fixture.coyote);
        fixture.response.setRequest(fixture.request); fixture.request.setResponse(fixture.response);
        return fixture;
    }
    private interface ReadOperation {
        int read(org.apache.tomcat.util.net.ApplicationBufferHandler handler) throws java.io.IOException;
    }
    private static org.apache.coyote.InputBuffer input(ReadOperation operation) {
        return new org.apache.coyote.InputBuffer() {
            public int doRead(org.apache.tomcat.util.net.ApplicationBufferHandler handler) throws java.io.IOException { return operation.read(handler); }
            public int available() { return 0; }
        };
    }
    private static class Fixture {
        final String text;
        final AtomicInteger reads = new AtomicInteger();
        final org.apache.coyote.Request coyote = new org.apache.coyote.Request();
        final CaptureRequest request = new CaptureRequest();
        final Response response = new Response();
        Fixture(String text) { this.text = text; }
    }
    private static class CaptureRequest extends Request {
        AsyncContext asyncContext;
        CaptureRequest() { super(connector()); }
        @Override public boolean isAsyncStarted() { return asyncContext != null; }
        @Override public AsyncContext getAsyncContext() { return asyncContext; }
    }
    private static Connector connector() { Connector connector = new Connector(); connector.setParseBodyMethods("POST"); return connector; }
    private static class Recording implements HttpTraceRuntime.Listener {
        boolean enabled, excluded, notRetained;
        int sanitizations, max = 4096;
        Map<String, String> completed = new LinkedHashMap<>();
        public boolean distributedTracingEnabled() { return true; }
        public java.util.List<String> httpIncludePaths() { return Collections.singletonList(excluded ? "/other" : "/api"); }
        public boolean captureHttpQueryParameters() { return enabled; }
        public boolean captureHttpRequestBodies() { return enabled; }
        public int maxHttpPayloadLength() { return max; }
        public Map<String, String> sanitizeHttpPayload(String raw, String format, boolean query) {
            sanitizations++; return HttpPayloadSanitizer.sanitize(raw, format, query, max);
        }
        public TraceSnapshot begin(String label, String user, Map<String, String> attributes) {
            if (notRetained) attributes.put("captureSkipped", "not-retained");
            return new TraceSnapshot("trace", "segment", "lens", "user", attributes);
        }
        public void complete(String id, Map<String, String> attributes) { completed = attributes; }
    }
}
