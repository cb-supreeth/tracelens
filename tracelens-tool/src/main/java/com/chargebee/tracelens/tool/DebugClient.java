package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Bounded debug-API client. Its URL comes only from local operator configuration. */
public class DebugClient {
    /** Preserve transport versus malformed-evidence failures for diagnostics. */
    static final class Failure extends ToolException {
        final String kind;
        Failure(int status, String kind, String message) {
            super(status, message);
            this.kind = kind;
        }
    }
    public static final int MAX_TRACE_SUMMARY_BYTES = 1024 * 1024;
    public final String baseUrl;
    private final ThreadLocal<Integer> responseBytes = ThreadLocal.withInitial(() -> 0);
    public int lastResponseBytes() { return responseBytes.get(); }
    public DebugClient(String url) {
        URI parsed;
        try { parsed = URI.create(url); }
        catch (Exception exc) { throw new IllegalArgumentException("Invalid debug API URL"); }
        if (!("http".equals(parsed.getScheme()) || "https".equals(parsed.getScheme()))
                || parsed.getHost() == null || parsed.getRawUserInfo() != null
                || parsed.getQuery() != null || parsed.getFragment() != null)
            throw new IllegalArgumentException("Use an HTTP(S) debug URL without credentials, query or fragment");
        this.baseUrl = url.replaceAll("/+$", "");
    }
    public ObjectNode request(String method, String path) {
        return request(method, path, 8 * 1024 * 1024, 5000);
    }
    public ObjectNode request(String method, String path, int maxBytes, int timeoutMillis) {
        responseBytes.set(0);
        if (maxBytes < 1 || maxBytes > 8 * 1024 * 1024 || timeoutMillis < 1 || timeoutMillis > 15000)
            throw new IllegalArgumentException("Invalid debug response budget");
        if (!("GET".equals(method) || "POST".equals(method)) || !path.startsWith("/") || path.startsWith("//"))
            throw new IllegalArgumentException("Invalid debug operation");
        BoundedReads.Allowance allowance = BoundedReads.allowance();
        if (allowance != null) {
            allowance.start(); maxBytes = Math.min(maxBytes, allowance.maxBytes);
            timeoutMillis = allowance.timeout(timeoutMillis);
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(baseUrl + "/tracelens" + path).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300)
                throw new Failure(status, "http", "Configured debug endpoint returned HTTP " + status + ".");
            long length = connection.getContentLengthLong();
            if (length > maxBytes) {
                if (allowance != null) throw new BoundedReads.Limit("byte");
                throw new Failure(502, "malformed", "Debug response exceeds the byte limit.");
            }
            byte[] bytes;
            try (InputStream input = connection.getInputStream()) {
                bytes = readBounded(input, maxBytes, System.nanoTime() + timeoutMillis * 1_000_000L, count -> {
                    responseBytes.set(count); if (allowance != null) allowance.consumed = count;
                });
            }
            JsonNode doc;
            try { doc = Json.parse(bytes); }
            catch (IllegalArgumentException exc) { throw new Failure(502, "malformed", "Configured debug endpoint returned invalid JSON."); }
            if (!doc.isObject()) throw new Failure(502, "malformed", "Configured debug endpoint returned an invalid evidence object.");
            return (ObjectNode) doc;
        } catch (IOException exc) {
            throw new Failure(502, "unreachable", "Configured debug endpoint is unavailable. Check service and agent readiness.");
        } catch (ToolException exc) {
            if (exc instanceof BoundedReads.Limit) throw exc;
            if (allowance != null && exc.status == 413) throw new BoundedReads.Limit("byte");
            if (exc instanceof Failure) throw exc;
            throw new Failure(exc.status, exc.status == 408 ? "unreachable" : "malformed", exc.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
    public ObjectNode summary() { return request("GET", "/summary", 65536, 5000); }
    public ObjectNode metadata() { return request("GET", "/metadata", 65536, 5000); }
    public ObjectNode instrumentedClasses() { return request("GET", "/instrumented-classes", 1024 * 1024, 5000); }
    public ObjectNode traces() { return traces(MAX_TRACE_SUMMARY_BYTES); }
    public ObjectNode traces(int maxBytes) {
        if (maxBytes < 1 || maxBytes > MAX_TRACE_SUMMARY_BYTES) throw new IllegalArgumentException("Invalid summary byte budget");
        ObjectNode doc = request("GET", "/traces", maxBytes, 5000);
        if (!doc.path("traces").isArray()) throw new ToolException(502, "Invalid trace summaries");
        for (JsonNode row : doc.path("traces"))
            if (!row.isObject()) throw new ToolException(502, "Invalid trace summaries");
        RequestEvidence.removePayload(doc);
        return doc;
    }
    public ObjectNode export(String id) { return request("GET", "/export/" + encode(id) + "?evict=false"); }
    public ObjectNode overview(String id) { return request("GET", "/overview/" + encode(id), 1024 * 1024, 5000); }
    public ObjectNode events(String id, long after, long through, int limit, int maxBytes, String kind) {
        return request("GET", "/events/" + encode(id) + "?after=" + after + "&through=" + through
                + "&limit=" + limit + "&maxBytes=" + maxBytes + "&kind=" + kind, maxBytes, 5000);
    }
    public ObjectNode requestData(String id) { return request("GET", "/request-data/" + encode(id), 1024 * 1024, 5000); }
    public ObjectNode findByTraceId(String id) { return RequestEvidence.summary(request("GET", "/find-by-trace-id/" + encode(id))); }
    public ObjectNode findByLensId(String label) { return RequestEvidence.summary(request("GET", "/find-by-lens-id/" + encode(label).replace("%2F", "/"))); }
    public ObjectNode enable() { return request("POST", "/enable"); }
    public ObjectNode disable() { return request("POST", "/disable"); }
    public ObjectNode reset(String id) { return request("POST", "/reset" + (id == null ? "" : "/" + encode(id))); }
    public static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (IOException exc) { throw new IllegalStateException(exc); }
    }
    static byte[] readBounded(InputStream input, int limit, long deadlineNanos) throws IOException {
        return readBounded(input, limit, deadlineNanos, count -> {});
    }
    private static byte[] readBounded(InputStream input, int limit, long deadlineNanos,
                                      java.util.function.IntConsumer progress) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer, 0, Math.min(buffer.length, limit + 1 - out.size()))) != -1) {
            out.write(buffer, 0, count);
            progress.accept(out.size());
            if (out.size() > limit) throw new ToolException(413, "Response exceeds the byte limit.");
            if (System.nanoTime() > deadlineNanos) throw new ToolException(408, "Response read timed out.");
        }
        return out.toByteArray();
    }
}
