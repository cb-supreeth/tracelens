package com.example.smoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Runs the delivered agent against unmodified servlet applications in real containers. */
public final class StandaloneSmoke {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String applicationUrl;
    private final String debugUrl;

    private StandaloneSmoke(int appPort, int debugPort) {
        applicationUrl = "http://127.0.0.1:" + appPort;
        debugUrl = "http://127.0.0.1:" + debugPort;
    }

    public static void main(String[] args) throws Exception {
        boolean legacy = "javax".equals(args[1]);
        boolean metadataOnly = args.length > 2 && "metadata".equals(args[2]);
        require((legacy ? "1.8" : "21").equals(System.getProperty("java.specification.version")),
                "wrong JVM for servlet compatibility test");
        Class<?> bridge = Class.forName("com.chargebee.tracelens.bridge.TraceRuntime", true, null);
        require(bridge.getClassLoader() == null, "bridge was not bootstrap-loaded");
        require((Boolean) bridge.getMethod("hasSink").invoke(null), "agent did not install its recording sink");
        int debugPort = ((Number) Class.forName("com.chargebee.tracelens.agent.StandaloneRuntime")
                .getMethod("port").invoke(null)).intValue();
        require(debugPort > 0, "agent debug server has no port");
        Path temp = Files.createTempDirectory("tracelens-standalone-" + args[1] + "-");
        Tomcat tomcat = new Tomcat();
        Throwable failure = null;
        try {
            Path app = temp.resolve("webapp");
            Files.createDirectories(app.resolve("WEB-INF"));
            copyTree(Paths.get(args[0]), app.resolve("WEB-INF/classes"));
            // This is the application's own descriptor: only its fixture router and
            // servlet are registered. No agent listener, filter, library, or initializer.
            Files.write(app.resolve("WEB-INF/web.xml"), webXml(legacy, !metadataOnly).getBytes(StandardCharsets.UTF_8));
            require(!Files.exists(app.resolve("WEB-INF/lib")), "host unexpectedly contains adapter libraries");
            tomcat.setBaseDir(temp.resolve("container").toString());
            tomcat.setPort(0);
            tomcat.getConnector().setProperty("address", "127.0.0.1");
            // Reuse one request thread to detect context leaking after completion.
            tomcat.getConnector().setProperty("maxThreads", "1");
            Context context = tomcat.addWebapp("", app.toString());
            for (org.apache.catalina.LifecycleListener listener : context.findLifecycleListeners()) {
                if (listener instanceof Tomcat.DefaultWebXmlListener) context.removeLifecycleListener(listener);
            }
            tomcat.start();
            ClassLoader appLoader = context.getLoader().getClassLoader();
            require(appLoader.loadClass("com.example.standalone.FixtureServlet").getClassLoader() == appLoader,
                    "fixture did not use isolated webapp classloader");
            require(appLoader.loadClass("com.chargebee.tracelens.bridge.TraceRuntime") == bridge,
                    "application sees a different bridge class");
            StandaloneSmoke smoke = new StandaloneSmoke(tomcat.getConnector().getLocalPort(), debugPort);
            if (metadataOnly) smoke.verifyMetadataOnly();
            else smoke.verify();
        } catch (Exception | Error error) {
            failure = error;
            throw error;
        } finally {
            try {
                tomcat.stop();
                tomcat.destroy();
            } catch (Exception | Error cleanupError) {
                if (failure != null) failure.addSuppressed(cleanupError);
                else throw cleanupError;
            } finally {
                deleteTree(temp);
            }
        }
        System.out.println("STANDALONE_SMOKE_OK servlet=" + args[1]
                + " java=" + System.getProperty("java.specification.version") + " metadataOnly=" + metadataOnly);
    }

    private void verifyMetadataOnly() throws Exception {
        require(System.getProperty("tracelens.configPath") == null, "metadata test accidentally supplied a config file");
        require(System.getProperty("tracelens.packages") == null, "metadata test accidentally supplied scope");
        require(debug("/summary").json().path("httpIncludePaths").equals(JSON.readTree("[\"/\"]")),
                "zero-config HTTP scope did not retain its default");
        Response response = app("/api/item");
        require(response.code == 200 && response.traceId != null && response.body.equals("GET:12:" + response.traceId),
                "agent without config/filter did not trace servlet request");
        JsonNode trace = export(response.traceId);
        require(trace.path("lensId").asText().equals("auto:GET /api/item"), "zero-config request label missing");
        require(trace.path("attributes").path("httpStatus").asInt() == 200, "zero-config HTTP status missing");
        require(trace.path("events").size() == 0, "unspecified package scope instrumented application lines");
        require(trace.path("user").asText().equals("anonymous"), "zero-config mode invented an application identity");
        require(trace.has("endedAt"), "zero-config request was not finalized");
        require(count() == 1, "zero-config nested HttpServlet.service methods duplicated request");
    }

    private void verify() throws Exception {
        JsonNode summary = debug("/summary").json();
        require(summary.path("agentActive").asBoolean(), "standalone agent is not active");
        require(summary.path("traceCount").asInt() == 0, "store not initially empty");
        require(summary.path("httpIncludePaths").equals(JSON.readTree("[\"/api\"]")),
                "standalone summary did not report JSON HTTP capture scope");
        require(debug("/metadata").json().path("httpIncludePaths").equals(summary.path("httpIncludePaths")),
                "standalone metadata did not report the effective HTTP capture scope");
        require(debug("/metadata").json().path("applicationId").asText().equals("standalone-smoke"),
                "standalone runtime ignored configuration");
        require(app("/tracelens/summary").body.equals("APPLICATION"), "agent hijacked an application route");

        Response first = request(applicationUrl, "GET", "/api/item?secret=query-secret",
                headers("Authorization", "Bearer auth-secret", "Cookie", "session=cookie-secret"), null);
        require(first.code == 200 && first.traceId != null && first.body.equals("GET:12:" + first.traceId),
                "plain servlet request was not automatically traced: " + first.body);
        JsonNode firstTrace = export(first.traceId);
        require(firstTrace.path("lensId").asText().equals("auto:GET /api/item"), "API label missing");
        require(firstTrace.path("attributes").path("httpMethod").asText().equals("GET"), "HTTP method missing");
        require(firstTrace.path("attributes").path("path").asText().equals("/api/item"), "request path missing");
        require(firstTrace.path("attributes").path("httpStatus").asInt() == 200, "HTTP status missing");
        require(hasLine(firstTrace), "no line events from isolated application classes");
        require(!firstTrace.toString().contains("query-secret") && !firstTrace.toString().contains("auth-secret")
                && !firstTrace.toString().contains("cookie-secret"), "sensitive request metadata was captured");
        require(export(first.traceId).path("segmentId").asText().equals(first.traceId), "read evicted the trace");
        require(count() == 1, "nested filter and servlet created duplicate traces");
        cleanContext();

        String payload = "body-secret";
        Response posted = request(applicationUrl, "POST", "/api/item",
                headers("tracelens-id", "fixture:post", "tracelens-user", "developer_site",
                        "tracelens-action-id", "action-123", "tracelens-action-name", "update-item",
                        "tracelens-session-id", "session-123", "Content-Type", "text/plain"), payload);
        require(posted.code == 200 && posted.body.equals("POST:" + payload.length() + ":" + posted.traceId),
                "agent consumed or changed the application request body");
        JsonNode postTrace = export(posted.traceId);
        require(postTrace.path("lensId").asText().equals("fixture:post"), "explicit label missing");
        require(postTrace.path("user").asText().equals("developer_site"), "explicit actor missing");
        require(postTrace.path("attributes").path("actionId").asText().equals("action-123"), "action missing");
        require(postTrace.path("attributes").path("sessionId").asText().equals("session-123"), "session missing");
        require(!postTrace.toString().contains(payload), "request payload was captured");
        cleanContext();

        Response filtered = app("/api/filtered");
        require(filtered.code == 200 && filtered.traceId != null
                && filtered.body.equals("FILTER:" + filtered.traceId), "short-circuit application filter was not traced");
        require(hasLine(export(filtered.traceId)), "filter-only request has no execution events");
        require(count() == 3, "filter-only request produced the wrong number of segments");

        Response principal = app("/api/principal");
        require(export(principal.traceId).path("user").asText().equals("fixture-developer"),
                "principal established by application filter was not observed");
        cleanContext();

        Response failed = app("/api/fail");
        require(failed.code == 500, "uncaught application exception did not produce HTTP 500");
        JsonNode failedMatches = debug("/find-by-lens-id/auto%3AGET%20/api/fail").json();
        require(failedMatches.path("count").asInt() == 1, "failed request missing from API search");
        JsonNode failedTrace = export(failedMatches.path("traces").get(0).path("segmentId").asText());
        require(failedTrace.path("attributes").path("httpStatus").asInt() == 500,
                "uncaught exception recorded incorrect HTTP status");
        require(failedTrace.has("endedAt"), "failed trace was not completed");
        cleanContext();

        Response async = app("/api/async");
        require(async.code == 200 && async.traceId != null && async.body.equals("GET:12:" + async.traceId),
                "async redispatch did not continue the original segment: " + async.body);
        JsonNode asyncTrace = completedExport(async.traceId);
        require(hasLine(asyncTrace), "async trace has no line events");
        require(asyncTrace.path("lensId").asText().equals("auto:GET /api/async"), "redispatch changed request label");
        require(count() == 6, "async redispatch created a duplicate segment");
        cleanContext();

        Response asyncComplete = app("/api/async-complete");
        require(asyncComplete.code == 200 && asyncComplete.traceId != null && asyncComplete.body.equals("ASYNC:12"),
                "async completion changed application behavior");
        JsonNode completedAsyncTrace = completedExport(asyncComplete.traceId);
        require(completedAsyncTrace.has("endedAt"), "async completion did not finalize trace");
        require(hasMethod(completedAsyncTrace, "calculate"), "AsyncContext.start worker lost trace context");
        require(count() == 7, "async work created extra segments");
        cleanContext();

        Response recovered = app("/api/recovered");
        require(recovered.code == 200 && recovered.body.equals("RECOVERED"), "application error recovery changed");
        require(export(recovered.traceId).path("attributes").path("httpStatus").asInt() == 200,
                "inner exception overrode the outer filter's recovered HTTP status");
        require(count() == 8, "recovered exception created extra segments");
        cleanContext();

        verifyIdentityHooks();

        JsonNode status = debug("/summary").json();
        require(status.path("failedClasses").asInt() == 0, "instrumentation failed for application classes");
        require(debug("/instrumented-classes").json().path("count").asInt() > 0, "instrumentation proof missing");
        require(request(debugUrl, "GET", "/tracelens/summary",
                headers("Origin", "https://untrusted.example"), null).code == 403,
                "standalone debug server accepted a cross-origin browser request");
        require(request(debugUrl, "GET", "/tracelens/summary",
                headers("X-Forwarded-For", "127.0.0.1"), null).code == 403,
                "standalone debug server accepted a forwarded request");
    }

    private void verifyIdentityHooks() throws Exception {
        int before = count();
        requireIdentity(app("/api/identity/direct"), "apicall_42");
        cleanContext();

        Response trusted = request(applicationUrl, "GET", "/api/identity/trusted",
                headers("X-Fixture-Caller", "middleware", "X-Fixture-User", "alice"), null);
        requireIdentity(trusted, "alice_42");

        Response untrusted = request(applicationUrl, "GET", "/api/identity/untrusted",
                headers("X-Fixture-Caller", "external", "X-Fixture-User", "mallory"), null);
        JsonNode untrustedTrace = requireIdentity(untrusted, "apicall_42");
        require(!untrustedTrace.toString().contains("mallory"), "untrusted claimed username leaked into trace");

        Response absentUser = request(applicationUrl, "GET", "/api/identity/absent-user",
                headers("X-Fixture-Caller", "middleware", "X-Fixture-User", "none"), null);
        requireIdentity(absentUser, "apicall_42");

        Response explicit = request(applicationUrl, "GET", "/api/identity/explicit",
                headers("X-Fixture-Caller", "middleware", "X-Fixture-User", "alice",
                        "tracelens-user", "operator_override"), null);
        requireIdentity(explicit, "operator_override");

        Response denied = request(applicationUrl, "GET", "/api/identity/denied",
                headers("X-Fixture-Caller", "middleware", "X-Fixture-User", "mallory"), null);
        require(denied.code == 401 && denied.body.equals("AUTH_DENIED"), "authentication rejection changed");
        JsonNode deniedTrace = export(denied.traceId);
        require(deniedTrace.path("user").asText().equals("anonymous"), "failed authentication published provisional identity");
        require(!deniedTrace.path("attributes").has("siteId")
                && !deniedTrace.path("attributes").has("identitySource"), "failed hook enriched trace metadata");
        require(deniedTrace.path("attributes").path("httpStatus").asInt() == 401, "authentication status missing");
        cleanContext();

        Response async = request(applicationUrl, "GET", "/api/identity/async-complete",
                headers("X-Fixture-Caller", "middleware", "X-Fixture-User", "alice"), null);
        require(async.code == 200 && async.body.equals("ASYNC:12:alice_42"),
                "async worker did not inherit captured identity: " + async.body);
        requireIdentity(async, "alice_42");
        require(completedExport(async.traceId).has("endedAt"), "identity-enriched async trace did not complete");
        cleanContext();

        JsonNode unrelated = export(app("/api/item").traceId);
        require(unrelated.path("user").asText().equals("anonymous"), "identity leaked to next traced request");
        require(!unrelated.path("attributes").has("siteId") && !unrelated.path("attributes").has("identitySource"),
                "identity metadata leaked to next traced request");
        require(count() == before + 8, "identity hooks created duplicate request segments");
    }

    private JsonNode requireIdentity(Response response, String user) throws Exception {
        require(response.code == 200 && response.traceId != null, "identity fixture request failed");
        JsonNode trace = export(response.traceId);
        require(trace.path("user").asText().equals(user), "incorrect identity: expected " + user + ", got " + trace.path("user"));
        require(trace.path("attributes").path("siteId").asText().equals("42"), "identity site missing");
        require(trace.path("attributes").path("identitySource").asText().equals("fixture-authentication"),
                "identity hook attribution missing");
        return trace;
    }

    private void cleanContext() throws Exception {
        require(app("/probe").body.equals("CONTEXT:none"), "completed request leaked context to reused container thread");
    }

    private int count() throws Exception { return debug("/traces").json().path("count").asInt(); }

    private JsonNode completedExport(String segmentId) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            JsonNode trace = export(segmentId);
            if (trace.has("endedAt")) return trace;
            Thread.sleep(20);
        }
        throw new AssertionError("async segment did not complete: " + segmentId);
    }

    private JsonNode export(String segmentId) throws Exception {
        Response response = debug("/export/" + segmentId + "?evict=false");
        require(response.code == 200, "trace export failed: " + response.body);
        return response.json();
    }

    private Response app(String path) throws Exception {
        return request(applicationUrl, "GET", path, Collections.<String, String>emptyMap(), null);
    }

    private Response debug(String path) throws Exception {
        return request(debugUrl, "GET", "/tracelens" + path, Collections.<String, String>emptyMap(), null);
    }

    private static Response request(String base, String method, String path,
                                    Map<String, String> headers, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        for (Map.Entry<String, String> header : headers.entrySet()) conn.setRequestProperty(header.getKey(), header.getValue());
        try {
            if (body != null) {
                conn.setDoOutput(true);
                try (java.io.OutputStream out = conn.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = conn.getResponseCode();
            InputStream input = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (input != null) {
                try (InputStream in = input) {
                    byte[] buffer = new byte[4096];
                    int length;
                    while ((length = in.read(buffer)) != -1) bytes.write(buffer, 0, length);
                }
            }
            return new Response(status, new String(bytes.toByteArray(), StandardCharsets.UTF_8),
                    conn.getHeaderField("X-TraceLens-Id"));
        } finally {
            conn.disconnect();
        }
    }

    private static boolean hasLine(JsonNode trace) {
        for (JsonNode event : trace.path("events")) if (event.path("type").asText().equals("LINE")) return true;
        return false;
    }

    private static boolean hasMethod(JsonNode trace, String method) {
        for (JsonNode event : trace.path("events")) {
            if (event.path("methodName").asText().equals(method)) return true;
        }
        return false;
    }

    private static Map<String, String> headers(String... pairs) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put(pairs[i], pairs[i + 1]);
        return values;
    }

    private static String webXml(boolean legacy, boolean filter) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + (legacy ? "<web-app xmlns=\"http://java.sun.com/xml/ns/javaee\" version=\"3.0\" metadata-complete=\"true\">"
                          : "<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.0\" metadata-complete=\"true\">")
                + (filter ? "<filter><filter-name>router</filter-name><filter-class>com.example.standalone.FixtureFilter</filter-class>"
                + "<async-supported>true</async-supported></filter>"
                + "<filter-mapping><filter-name>router</filter-name><url-pattern>/*</url-pattern>"
                + "<dispatcher>REQUEST</dispatcher><dispatcher>ASYNC</dispatcher><dispatcher>ERROR</dispatcher></filter-mapping>" : "")
                + "<servlet><servlet-name>fixture</servlet-name><servlet-class>com.example.standalone.FixtureServlet</servlet-class>"
                + "<load-on-startup>1</load-on-startup><async-supported>true</async-supported></servlet>"
                + "<servlet-mapping><servlet-name>fixture</servlet-name><url-pattern>/</url-pattern></servlet-mapping></web-app>";
    }

    private static void copyTree(final Path source, final Path target) throws Exception {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws java.io.IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
                Files.copy(file, target.resolve(source.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path createdTempDirectory) throws Exception {
        Files.walkFileTree(createdTempDirectory, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, java.io.IOException error) throws java.io.IOException {
                if (error != null) throw error;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Response {
        final int code;
        final String body, traceId;
        Response(int code, String body, String traceId) {
            this.code = code;
            this.body = body;
            this.traceId = traceId;
        }
        JsonNode json() throws Exception { return JSON.readTree(body); }
    }
}
