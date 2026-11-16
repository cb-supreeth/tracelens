package com.chargebee.tracelens.compat;

import com.chargebee.tracelens.bridge.AgentStatus;
import com.chargebee.tracelens.bridge.TraceRuntime;
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

/** Integration assertions against the deliverable artifacts, without an application dependency. */
public final class Java8ServletSmoke {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String baseUrl;

    private Java8ServletSmoke(int port) {
        baseUrl = "http://127.0.0.1:" + port;
    }

    public static void main(String[] args) throws Exception {
        require("1.8".equals(System.getProperty("java.specification.version")), "must run on Java 8");
        require(AgentStatus.agentActive(), "shaded agent did not start");
        require(TraceRuntime.class.getClassLoader() == null, "bridge must be bootstrap-loaded");
        Path temp = Files.createTempDirectory("tracelens-tomcat7-smoke-");
        Tomcat tomcat = new Tomcat();
        Throwable failure = null;
        try {
            Path app = temp.resolve("webapp");
            Path lib = app.resolve("WEB-INF/lib");
            Files.createDirectories(lib);
            Files.copy(Paths.get(args[0]), lib.resolve("tracelens-adapter.jar"));
            copyTree(Paths.get(args[1]), app.resolve("WEB-INF/classes"));
            Files.write(app.resolve("WEB-INF/web.xml"), webXml().getBytes(StandardCharsets.UTF_8));
            tomcat.setBaseDir(temp.resolve("container").toString());
            tomcat.setPort(0);
            tomcat.getConnector().setProperty("address", "127.0.0.1");
            // Reuse one request thread so excluded requests also check context cleanup.
            tomcat.getConnector().setProperty("maxThreads", "1");
            Context context = tomcat.addWebapp("", app.toString());
            // The fixture defines its sole servlet in web.xml and contains no JSPs.
            for (org.apache.catalina.LifecycleListener listener : context.findLifecycleListeners()) {
                if (listener instanceof Tomcat.DefaultWebXmlListener) context.removeLifecycleListener(listener);
            }
            tomcat.start();
            ClassLoader appLoader = context.getLoader().getClassLoader();
            require(appLoader.loadClass("com.chargebee.tracelens.servlet.TraceLensFilter").getClassLoader()
                    == appLoader, "adapter was not loaded from WEB-INF/lib");
            require(appLoader.loadClass("com.chargebee.legacyfixture.FixtureServlet").getClassLoader()
                    == appLoader, "fixture escaped the webapp classloader");
            require(appLoader.loadClass("com.chargebee.tracelens.bridge.TraceRuntime") == TraceRuntime.class,
                    "webapp loaded a second copy of the bootstrap bridge");
            Java8ServletSmoke smoke = new Java8ServletSmoke(tomcat.getConnector().getLocalPort());
            if (Boolean.getBoolean("tracelens.enabled")) smoke.enabled();
            else smoke.disabled();
            require(AgentStatus.failedCount() == 0, "failed instrumentation classes");
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
        require(!TraceRuntime.hasSink(), "adapter did not unregister its sink on webapp shutdown");
        System.out.println("JAVA8_SERVLET_SMOKE_OK enabled=" + Boolean.getBoolean("tracelens.enabled"));
    }

    private void enabled() throws Exception {
        require(get("/tracelens/summary").json().path("traceCount").asInt() == 0, "store not initially empty");
        require(get("/tracelens/summary").json().path("httpIncludePaths").equals(JSON.readTree("[\"/api\"]")),
                "adapter summary did not report JSON HTTP capture scope");
        require(get("/tracelens/metadata").json().path("httpIncludePaths").equals(JSON.readTree("[\"/api\"]")),
                "adapter metadata did not report JSON HTTP capture scope");
        require(get("/tracelens/metadata").json().path("applicationId").asText().equals("cb-app-servlet-smoke"),
                "metadata/config schema mismatch");

        Response first = request("GET", "/api/item?secret=query-secret", headers("Authorization", "Bearer auth-secret",
                "Cookie", "session=cookie-secret"), null);
        String firstId = first.traceId;
        require(first.code == 200 && firstId != null && first.body.equals("GET:12:" + firstId), "GET tracing failed");
        JsonNode exported = export(firstId);
        require(exported.path("lensId").asText().equals("auto:GET /api/item"), "API label not generated");
        require(exported.path("attributes").path("httpMethod").asText().equals("GET"), "method missing");
        require(exported.path("attributes").path("path").asText().equals("/api/item"), "path missing");
        require(exported.path("attributes").path("httpStatus").asInt() == 200, "status missing");
        require(hasLine(exported), "no woven line events from WEB-INF/classes");
        require(!exported.toString().contains("query-secret") && !exported.toString().contains("auth-secret")
                && !exported.toString().contains("cookie-secret"), "sensitive request material was exported");
        require(export(firstId).path("segmentId").asText().equals(firstId), "MCP read evicted the trace");
        JsonNode overview = get("/tracelens/overview/" + firstId).json();
        require(overview.path("detailsAvailable").asBoolean() && overview.path("segmentId").asText().equals(firstId),
                "bounded overview missing identity/capability");
        Response pageResponse = get("/tracelens/events/" + firstId + "?limit=2&maxBytes=4096");
        require(pageResponse.code == 200 && pageResponse.body.getBytes(StandardCharsets.UTF_8).length <= 4096,
                "event page did not honor actual UTF-8 byte bound");
        require(pageResponse.json().path("events").size() <= 2, "event count bound ignored");
        require(get("/tracelens/events/" + firstId + "?maxBytes=1048577").code == 400, "invalid page budget accepted");
        require(get("/tracelens/request-data/" + firstId).json().path("segmentId").asText().equals(firstId),
                "request-data identity missing");
        require(get("/tracelens/summary").json().path("projectionVersion").asInt() == 1, "projection capabilities missing");
        require(get("/probe").body.equals("GET:12:none"), "servlet thread retained prior trace context");

        String payload = "body-secret";
        Response posted = request("POST", "/api/item", headers("tracelens-id", "fixture:post+value/",
                "tracelens-user", "developer_site", "tracelens-action-id", "action-123",
                "tracelens-action-name", "update-item", "tracelens-session-id", "session-123",
                "Content-Type", "text/plain"), payload);
        require(posted.code == 200 && posted.body.equals("POST:" + payload.length() + ":" + posted.traceId),
                "filter changed or consumed application body");
        require("action-123".equals(posted.actionId), "action response header missing");
        JsonNode postTrace = export(posted.traceId);
        require(postTrace.path("lensId").asText().equals("fixture:post+value/"), "explicit label lost");
        require(postTrace.path("user").asText().equals("developer_site"), "explicit actor lost");
        require(postTrace.path("attributes").path("actionId").asText().equals("action-123"), "action lost");
        require(postTrace.path("attributes").path("sessionId").asText().equals("session-123"), "session lost");
        require(!postTrace.toString().contains(payload), "request body was exported");
        require(get("/tracelens/find-by-lens-id/fixture%3Apost%2Bvalue/").json().path("count").asInt() == 1,
                "encoded trailing-slash label lookup failed");
        require(get("/tracelens/find-by-lens-id/fixture%3Apost%2Bvalue").json().path("count").asInt() == 0,
                "label lookup failed to preserve exact trailing slash");

        Response failed = get("/api/fail");
        require(failed.code == 500, "uncaught exception did not produce HTTP 500: " + failed.code);
        // Tomcat's error handler can clear response headers after the filter exits.
        // The failed request must still be discoverable through its API label.
        JsonNode failedMatches = get("/tracelens/find-by-lens-id/auto%3AGET%20/api/fail").json();
        require(failedMatches.path("count").asInt() == 1, "failed API request was not discoverable");
        JsonNode failedTrace = export(failedMatches.path("traces").get(0).path("segmentId").asText());
        require(failedTrace.path("attributes").path("httpStatus").asInt() == 500,
                "uncaught servlet exception recorded an incorrect HTTP status");
        require(failedTrace.path("attributes").path("filterException").asText().equals("java.lang.IllegalStateException"),
                "uncaught servlet exception class was not recorded");
        require(get("/probe").body.equals("GET:12:none"), "failed request leaked thread context");

        Response async = get("/api/async");
        require(async.code == 200 && async.traceId != null && async.body.equals("GET:12:" + async.traceId),
                "async redispatch did not continue the request segment");
        require(get("/probe").body.equals("GET:12:none"), "async dispatch leaked context onto container thread");
        JsonNode asyncTrace = export(async.traceId);
        require(asyncTrace.has("endedAt") && hasLine(asyncTrace), "async trace was not completed/recorded");
        JsonNode traces = get("/tracelens/traces").json();
        require(traces.path("count").asInt() == 4, "debug calls or async redispatch created extra traces");
        require(get("/tracelens/instrumented-classes").json().path("count").asInt() > 0,
                "instrumentation proof missing");

        require(request("GET", "/tracelens/reset", Collections.<String, String>emptyMap(), null).code == 405,
                "GET reset must not mutate");
        Response badDelete = request("DELETE", "/tracelens/disable", Collections.<String, String>emptyMap(), null);
        require(badDelete.code == 405 && "POST".equals(badDelete.allow), "DELETE toggle must be rejected");
        require(request("GET", "/tracelens/summary", headers("Origin", "https://untrusted.example"), null).code == 403,
                "browser cross-origin debug request accepted");
        require(request("GET", "/tracelens/summary", headers("X-Forwarded-For", "127.0.0.1"), null).code == 403,
                "forwarded debug request accepted");
        require(post("/tracelens/disable").code == 200, "recording disable failed");
        Response bypassed = get("/api/item");
        require(bypassed.traceId == null && bypassed.body.equals("GET:12:none"), "disabled recording did not pass through");
        require(get("/tracelens/traces").json().path("count").asInt() == 4, "disabled request was captured");
        require(post("/tracelens/enable").json().path("recordingEnabled").asBoolean(), "recording enable failed");
        require(request("DELETE", "/tracelens/reset/" + firstId, Collections.<String, String>emptyMap(), null).code == 200,
                "individual reset failed");
        require(get("/tracelens/export/" + firstId + "?evict=false").code == 404, "reset trace retained");
        require(post("/tracelens/reset").json().path("traceCount").asInt() == 0, "reset-all failed");
    }

    private void disabled() throws Exception {
        Response response = get("/api/item");
        require(response.code == 200 && response.body.equals("GET:12:none") && response.traceId == null,
                "disabled adapter changed the application response");
        require(get("/tracelens/summary").code == 404, "disabled adapter exposed a debug endpoint");
        require(!TraceRuntime.hasSink(), "disabled adapter installed a sink");
    }

    private JsonNode export(String segmentId) throws Exception {
        Response response = get("/tracelens/export/" + segmentId + "?evict=false");
        require(response.code == 200, "export failed: " + response.body);
        require(response.contentType != null && response.contentType.toLowerCase().contains("utf-8"),
                "JSON response encoding not set");
        return response.json();
    }

    private Response get(String path) throws Exception {
        return request("GET", path, Collections.<String, String>emptyMap(), null);
    }

    private Response post(String path) throws Exception {
        return request("POST", path, Collections.<String, String>emptyMap(), null);
    }

    private Response request(String method, String path, Map<String, String> headers, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
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
                    conn.getHeaderField("X-TraceLens-Id"), conn.getHeaderField("X-TraceLens-Action-Id"),
                    conn.getHeaderField("Allow"), conn.getContentType());
        } finally {
            conn.disconnect();
        }
    }

    private static boolean hasLine(JsonNode trace) {
        for (JsonNode event : trace.path("events")) if (event.path("type").asText().equals("LINE")) return true;
        return false;
    }

    private static Map<String, String> headers(String... pairs) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put(pairs[i], pairs[i + 1]);
        return values;
    }

    private static String webXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<web-app xmlns=\"http://java.sun.com/xml/ns/javaee\" version=\"3.0\" metadata-complete=\"true\">"
                + "<filter><filter-name>tracelens</filter-name>"
                + "<filter-class>com.chargebee.tracelens.servlet.TraceLensFilter</filter-class>"
                + "<async-supported>true</async-supported></filter>"
                + "<filter-mapping><filter-name>tracelens</filter-name><url-pattern>/*</url-pattern>"
                + "<dispatcher>REQUEST</dispatcher><dispatcher>ASYNC</dispatcher><dispatcher>ERROR</dispatcher></filter-mapping>"
                + "<servlet><servlet-name>fixture</servlet-name>"
                + "<servlet-class>com.chargebee.legacyfixture.FixtureServlet</servlet-class>"
                + "<load-on-startup>1</load-on-startup><async-supported>true</async-supported></servlet>"
                + "<servlet-mapping><servlet-name>fixture</servlet-name><url-pattern>/</url-pattern></servlet-mapping>"
                + "</web-app>";
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
        final String body, traceId, actionId, allow, contentType;
        Response(int code, String body, String traceId, String actionId, String allow, String contentType) {
            this.code = code;
            this.body = body;
            this.traceId = traceId;
            this.actionId = actionId;
            this.allow = allow;
            this.contentType = contentType;
        }
        JsonNode json() throws Exception { return JSON.readTree(body); }
    }
}
