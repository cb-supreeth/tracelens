package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Explicit, bounded incoming-request search. Results contain identities, never payloads. */
final class RequestSearch implements AutoCloseable {
    static final int MAX_SEGMENTS = 200, MAX_KEYWORD = 256, MAX_REQUEST_BYTES = 128 * 1024;
    static final int MAX_FETCH_BYTES = 8 * 1024 * 1024, MAX_TOTAL_BYTES = 32 * 1024 * 1024;
    static final int MAX_FETCH_MILLIS = 2000, MAX_TOTAL_MILLIS = 12000;
    static final int MAX_FIELD_LENGTH = 16384; // Same maximum as agent capture and request-data display.
    private final boolean federated;
    private final Map<String, DebugClient> clients;
    private final DebugClient single;
    private final int fetchBytes, totalBytes, fetchMillis, totalMillis;
    // This pool is shared by searches in one viewer, not created per segment or
    // request. A slow configured agent cannot create unbounded background work.
    private final ThreadPoolExecutor readers = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(16), runnable -> {
                Thread thread = new Thread(runnable, "tracelens-request-search");
                thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    RequestSearch(boolean federated, Map<String, DebugClient> clients, DebugClient single) {
        this(federated, clients, single, MAX_FETCH_BYTES, MAX_TOTAL_BYTES, MAX_FETCH_MILLIS, MAX_TOTAL_MILLIS);
    }
    RequestSearch(boolean federated, Map<String, DebugClient> clients, DebugClient single,
                  int fetchBytes, int totalBytes, int fetchMillis, int totalMillis) {
        this.federated = federated; this.clients = clients; this.single = single;
        this.fetchBytes = fetchBytes; this.totalBytes = totalBytes;
        this.fetchMillis = fetchMillis; this.totalMillis = totalMillis;
    }
    @Override public void close() { readers.shutdownNow(); }

    ObjectNode search(JsonNode body) {
        List<ObjectNode> segments = validate(body); // Validate the entire batch before any upstream read.
        String keyword = body.path("keyword").textValue().toLowerCase(Locale.ROOT);
        long deadline = System.nanoTime() + totalMillis * 1_000_000L;
        int bytes = 0, attempted = 0, scanned = 0, withData = 0, unavailable = 0;
        boolean limited = false;
        ArrayNode matches = Json.arr();
        for (ObjectNode identity : segments) {
            long remaining = deadline - System.nanoTime();
            if (remaining < 1_000_000L || bytes >= totalBytes || Thread.currentThread().isInterrupted()) { limited = true; break; }
            int maximum = Math.min(fetchBytes, totalBytes - bytes);
            int timeout = (int) Math.min(fetchMillis, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            DebugClient client = federated ? clients.get(Json.text(identity, "service")) : single;
            Future<Read> pending = null;
            attempted++;
            try {
                pending = readers.submit(() -> {
                    try {
                        ObjectNode exported;
                        try { exported = client.request("GET", "/request-data/" + DebugClient.encode(Json.text(identity, "segmentId")), maximum, timeout); }
                        catch (ToolException unsupported) {
                            if (unsupported.status != 404 && unsupported.status != 405) throw unsupported;
                            exported = client.request("GET", "/export/" + DebugClient.encode(Json.text(identity, "segmentId")) + "?evict=false", maximum, timeout);
                        }
                        return new Read(exported, Math.min(maximum, client.lastResponseBytes()));
                    } catch (RuntimeException unavailableRead) {
                        return new Read(null, Math.min(maximum, client.lastResponseBytes()));
                    }
                });
                // The wall-clock budget also covers connection, headers and slow
                // reads. The underlying client independently bounds bytes and I/O.
                long waitNanos = Math.min(fetchMillis * 1_000_000L, deadline - System.nanoTime());
                if (waitNanos <= 0) throw new java.util.concurrent.TimeoutException();
                Read read = pending.get(waitNanos, TimeUnit.NANOSECONDS);
                bytes += read.bytes;
                if (!sameIdentity(identity, read.trace)) { unavailable++; continue; }
                scanned++;
                ObjectNode fields = matchingFields(read.trace, keyword);
                limited |= fields.path("limited").asBoolean();
                if (fields.path("withData").asBoolean()) withData++;
                if (fields.path("fields").size() > 0) {
                    ObjectNode match = identity.deepCopy();
                    match.set("fields", fields.get("fields")); matches.add(match);
                }
            } catch (java.util.concurrent.TimeoutException timeoutRead) {
                // A timed-out worker may still be winding down. Conservatively
                // reserve its whole byte allowance so overlapping reads stay bounded.
                bytes += maximum; unavailable++; limited = true;
                if (pending != null) pending.cancel(true);
            } catch (InterruptedException interrupted) {
                bytes += maximum; unavailable++; limited = true;
                if (pending != null) pending.cancel(true);
                Thread.currentThread().interrupt(); break;
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.RejectedExecutionException failedRead) {
                bytes += maximum; unavailable++; limited = true;
            } finally { readers.purge(); }
        }
        int unsearched = segments.size() - attempted;
        return Json.obj("schemaVersion", 1, "kind", "request-data-search", "matches", matches,
                "counts", Json.obj("requested", segments.size(), "scanned", scanned, "withData", withData,
                        "matched", matches.size(), "unavailable", unavailable, "unsearched", unsearched),
                "limited", limited || unsearched > 0);
    }

    private List<ObjectNode> validate(JsonNode body) {
        if (body == null || !body.isObject() || !fields(body).equals(new HashSet<>(Arrays.asList("keyword", "segments"))))
            throw new IllegalArgumentException("Invalid request search fields");
        String keyword = Json.text(body, "keyword");
        if (keyword == null || keyword.trim().isEmpty() || keyword.length() > MAX_KEYWORD || keyword.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid request search keyword");
        JsonNode rows = body.get("segments");
        if (!rows.isArray() || rows.size() > MAX_SEGMENTS) throw new IllegalArgumentException("Invalid request search segments");
        List<ObjectNode> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode row : rows) {
            Set<String> names = fields(row);
            if (!row.isObject() || !names.containsAll(Arrays.asList("service", "serviceInstanceId", "segmentId"))
                    || !new HashSet<>(Arrays.asList("service", "serviceInstanceId", "segmentId", "traceId")).containsAll(names))
                throw new IllegalArgumentException("Invalid request search identity");
            String service = coordinate(row, "service", "[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");
            coordinate(row, "serviceInstanceId", "[A-Za-z0-9_-]{1,128}");
            coordinate(row, "segmentId", "[A-Za-z0-9_-]{1,128}");
            if (row.has("traceId")) coordinate(row, "traceId", "[A-Za-z0-9_-]{1,128}");
            if (federated && !clients.containsKey(service)) throw new IllegalArgumentException("Unknown configured service");
            String key = Json.stringify(Json.arr(service, row.get("serviceInstanceId"), row.get("segmentId")));
            if (!seen.add(key)) throw new IllegalArgumentException("Duplicate request search segment");
            result.add(((ObjectNode) row).deepCopy());
        }
        return result;
    }
    private static Set<String> fields(JsonNode object) {
        Set<String> names = new HashSet<>(); object.fieldNames().forEachRemaining(names::add); return names;
    }
    private static String coordinate(JsonNode row, String name, String pattern) {
        String value = Json.text(row, name);
        if (value == null || !value.matches(pattern)) throw new IllegalArgumentException("Invalid request search identity");
        return value;
    }
    private boolean sameIdentity(ObjectNode requested, ObjectNode exported) {
        if (exported == null || !requested.path("segmentId").equals(exported.path("segmentId"))
                || !requested.path("serviceInstanceId").equals(exported.path("serviceInstanceId"))
                || requested.has("traceId") && !requested.path("traceId").equals(exported.path("traceId"))) return false;
        if (federated) return true; // The configured service selects the only allowed endpoint.
        String service = Json.text(requested, "service");
        // Single mode labels its configured endpoint local/default; discovery may
        // instead use the application's recorded name. Never accept another name
        // solely because it was supplied in the request.
        return "local".equals(service) || "default".equals(service) || clients.containsKey(service)
                || service.equals(Json.text(exported, "service")) || service.equals(Json.text(exported, "applicationId"));
    }
    static ObjectNode matchingFields(JsonNode trace, String keyword) {
        JsonNode attributes = trace.path("attributes");
        ArrayNode matched = Json.arr(); boolean withData = false, limited = false;
        String[] names = {"httpQueryParameters", "httpRequestBody"};
        for (int index = 0; index < names.length; index++) {
            String field = names[index], raw = Json.text(attributes, field);
            if (!"captured".equals(Json.text(attributes, field + "Status")) || raw == null || raw.isEmpty()) continue;
            // Do not scan arbitrarily large values from an invalid/old export.
            // Make this omission visible instead of silently truncating a match.
            if (raw.length() > MAX_FIELD_LENGTH) { limited = true; continue; }
            withData = true;
            if (containsValue(raw, keyword, index == 0 || "form".equals(Json.text(attributes, "httpRequestBodyFormat"))))
                matched.add(index == 0 ? "query" : "body");
        }
        return Json.obj("withData", withData, "fields", matched, "limited", limited);
    }
    private static boolean containsValue(String raw, String keyword, boolean form) {
        // Streaming tokens decode escaped strings/field names while retaining the
        // original number token (including large IDs and decimal precision).
        try (JsonParser parser = Json.MAPPER.getFactory().createParser(raw)) {
            boolean matched = false; JsonToken token;
            while ((token = parser.nextToken()) != null)
                if (token == JsonToken.FIELD_NAME || token.isScalarValue())
                    matched |= parser.getText().toLowerCase(Locale.ROOT).contains(keyword);
            return matched;
        } catch (IOException | RuntimeException invalidJson) {
            // Older captures may contain encoded form/query text rather than a
            // JSON field map. This remains captured evidence, never a live request.
            if (form) try { return URLDecoder.decode(raw, "UTF-8").toLowerCase(Locale.ROOT).contains(keyword); }
                catch (IllegalArgumentException | IOException invalidEncoding) { /* Search literal captured text below. */ }
            return raw.toLowerCase(Locale.ROOT).contains(keyword);
        }
    }
    private static final class Read {
        final ObjectNode trace; final int bytes;
        Read(ObjectNode trace, int bytes) { this.trace = trace; this.bytes = bytes; }
    }
}
