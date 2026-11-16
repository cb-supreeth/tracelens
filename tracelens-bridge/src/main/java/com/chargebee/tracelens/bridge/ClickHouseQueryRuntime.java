package com.chargebee.tracelens.bridge;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;

/** Opt-in native ClickHouse query evidence. No SDK, response, settings or result introspection. */
public final class ClickHouseQueryRuntime {
    private static final int MAX_SEGMENTS = 4096, MAX_PENDING = 1024;
    private static final int MAX_PARAMETERS = 128, MAX_VALUE = 512, MAX_PARAMETERS_JSON = 8192;
    private static final long RETENTION = 30L * 60 * 1000000000L;
    private static final Map<String, Quota> QUOTAS = new LinkedHashMap<>();
    private static final Map<Long, Call> PENDING = new LinkedHashMap<>();
    private static final AtomicLong IDS = new AtomicLong();
    private static final ThreadLocal<Call> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> CALLBACK = new ThreadLocal<>();
    private static volatile boolean enabled, parametersEnabled;
    private static volatile int sqlLimit = 4096, queryLimit = 100;

    private ClickHouseQueryRuntime() { }

    public static void configure(boolean capture, int maxSqlLength, int maxQueriesPerRequest, boolean parameters) {
        enabled = false;
        synchronized (QUOTAS) { QUOTAS.clear(); }
        synchronized (PENDING) { PENDING.clear(); }
        CURRENT.remove();
        sqlLimit = Math.max(1, Math.min(16384, maxSqlLength));
        queryLimit = Math.max(1, Math.min(1000, maxQueriesPerRequest));
        parametersEnabled = capture && parameters;
        enabled = capture;
    }

    public static boolean isEnabled() { return enabled; }

    /** Only the canonical Client.query(String, Map, QuerySettings) overload calls this. */
    public static Object enter(String sql, Map<?, ?> parameters) {
        try {
            if (!enabled || Boolean.TRUE.equals(CALLBACK.get())) return null;
            Call nested = CURRENT.get();
            if (nested != null) { nested.depth++; return nested; }
            TraceSnapshot snapshot = TraceContext.current();
            if (snapshot == null || !TraceRuntime.hasSink() || snapshot.getAttributes().containsKey("captureSkipped")) return null;
            DataStoreBoundaryRuntime.nativeClickHouseQueryObserved();
            int reservation = reserve(snapshot);
            Call call = new Call(snapshot.getSegmentId(), reservation > 0);
            CURRENT.set(call);
            if (reservation < 0) {
                Map<String, String> marker = fields("clickhouse-query-limit", "TRUNCATED");
                marker.put("operation", "query-limit");
                marker.put("queryLimit", String.valueOf(queryLimit));
                emit(call.segment, marker);
            }
            if (!call.record) return call;
            if (sql != null) {
                call.fields.put("sql", sql.substring(0, Math.min(sqlLimit, sql.length())));
                call.fields.put("sqlTruncated", String.valueOf(sql.length() > sqlLimit));
            }
            call.fields.put("sqlSource", sql == null ? "unavailable" : "native-query");
            if (parametersEnabled) captureParameters(call.fields, parameters);
            return call;
        } catch (Throwable ignored) { return null; }
    }

    /** Observe completion without waiting, consuming results, or changing the application's Future identity. */
    public static void exit(Object token, Object response, Throwable failure) {
        if (!(token instanceof Call)) return;
        Call call = (Call) token;
        try {
            if (call.finished || --call.depth > 0) return;
            call.finished = true;
            DataStoreBoundaryRuntime.nativeClickHouseQueryObserved();
            if (!call.record) return;
            if (failure != null) { complete(call, failure, "immediate-error"); return; }
            if (!(response instanceof CompletionStage)) {
                call.fields.put("status", "SUBMITTED");
                call.fields.put("completionKind", "unavailable");
                emit(call.segment, call.fields);
                return;
            }
            final long id = IDS.incrementAndGet();
            synchronized (PENDING) {
                // An abandoned SDK future must not retain unbounded SQL or whole trace snapshots.
                Iterator<Call> entries = PENDING.values().iterator();
                long now = System.nanoTime();
                while (entries.hasNext()) if (now - entries.next().started > RETENTION) entries.remove();
                if (PENDING.size() >= MAX_PENDING) {
                    call.fields.put("status", "SUBMITTED");
                    call.fields.put("completionKind", "capture-capacity");
                    emit(call.segment, call.fields);
                    return;
                }
                PENDING.put(id, call);
            }
            try {
                // The callback captures an ID, not SQL, a client, response, request context or parameters.
                ((CompletionStage<?>) response).whenComplete((ignored, error) -> finish(id, error));
            } catch (Throwable unavailable) {
                synchronized (PENDING) { PENDING.remove(id); }
                call.fields.put("status", "SUBMITTED");
                call.fields.put("completionKind", "observer-unavailable");
                emit(call.segment, call.fields);
            }
        } catch (Throwable ignored) {
            // SQL observation must not change the client's return or exception.
        } finally {
            if (call.depth <= 0 && CURRENT.get() == call) CURRENT.remove();
        }
    }

    private static void finish(long id, Throwable failure) {
        try {
            Call call;
            synchronized (PENDING) { call = PENDING.remove(id); }
            if (call != null) complete(call, failure, "response-complete");
        } catch (Throwable ignored) { }
    }

    private static void complete(Call call, Throwable failure, String completion) {
        call.fields.put("status", failure == null ? "OBSERVED" : "ERROR");
        call.fields.put("completionKind", completion);
        call.fields.put("durationMs", String.valueOf(Math.max(0, System.nanoTime() - call.started) / 1000000.0));
        if (failure != null) {
            String type = failure.getClass().getName();
            call.fields.put("errorType", type.substring(0, Math.min(256, type.length())));
        }
        emit(call.segment, call.fields);
    }

    private static void emit(String segment, Map<String, String> fields) {
        CALLBACK.set(Boolean.TRUE);
        try { TraceRuntime.onBoundary(segment, fields); } finally { CALLBACK.remove(); }
    }

    private static Map<String, String> fields(String evidence, String status) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("boundaryKind", "database"); fields.put("system", "clickhouse");
        fields.put("direction", "outbound"); fields.put("operation", "query");
        fields.put("evidenceKind", evidence); fields.put("status", status);
        return fields;
    }

    private static int reserve(TraceSnapshot snapshot) {
        synchronized (QUOTAS) {
            long now = System.nanoTime();
            Quota quota = QUOTAS.get(snapshot.getSegmentId());
            if (quota == null) {
                if (QUOTAS.size() % 128 == 0 || QUOTAS.size() >= MAX_SEGMENTS) {
                    Iterator<Quota> entries = QUOTAS.values().iterator();
                    while (entries.hasNext()) {
                        Quota old = entries.next();
                        if (old.snapshots.isEmpty() && now - old.lastSeen > RETENTION) entries.remove();
                    }
                }
                if (QUOTAS.size() >= MAX_SEGMENTS) return 0;
                quota = new Quota(); QUOTAS.put(snapshot.getSegmentId(), quota);
            }
            if (quota.snapshots.size() < 64) quota.snapshots.put(snapshot, Boolean.TRUE);
            quota.lastSeen = now;
            if (quota.count < queryLimit) { quota.count++; return 1; }
            if (!quota.limitEmitted) { quota.limitEmitted = true; return -1; }
            return 0;
        }
    }

    private static void captureParameters(Map<String, String> fields, Map<?, ?> parameters) {
        if (parameters == null) { fields.put("sqlParametersStatus", "not-applicable"); return; }
        StringBuilder json = new StringBuilder("[");
        boolean truncated = false;
        int count = 0;
        try {
            for (Map.Entry<?, ?> entry : parameters.entrySet()) {
                if (++count > MAX_PARAMETERS) { truncated = true; break; }
                Object key = entry.getKey();
                if (!(key instanceof String) || ((String) key).length() > 128
                        || !((String) key).matches("[A-Za-z_][A-Za-z0-9_]*")) { truncated = true; continue; }
                Object value = entry.getValue();
                String text = scalar(value), type = value == null ? "Null" : text == null ? "Object" : value.getClass().getSimpleName();
                if ("Integer".equals(type)) type = "Int";
                String status = value == null ? "null" : text == null ? "unsupported" : text.length() > MAX_VALUE ? "truncated" : "captured";
                if (text != null && text.length() > MAX_VALUE) text = text.substring(0, MAX_VALUE);
                String item = "{\"name\":" + json((String) key) + ",\"type\":" + json(type)
                        + ",\"value\":" + json(text) + ",\"status\":" + json(status) + "}";
                if (json.length() + item.length() + 2 > MAX_PARAMETERS_JSON) { truncated = true; break; }
                if (json.length() > 1) json.append(',');
                json.append(item); truncated |= "truncated".equals(status);
            }
            fields.put("sqlParametersStatus", "captured");
        } catch (Throwable unavailable) {
            fields.put("sqlParametersStatus", "unavailable");
            truncated = true;
        }
        fields.put("sqlParameters", json.append(']').toString());
        fields.put("sqlParametersTruncated", String.valueOf(truncated));
    }

    private static String scalar(Object value) {
        if (value == null) return null;
        Class<?> type = value.getClass();
        if (type == String.class) return (String) value;
        if (type == Boolean.class || type == Byte.class || type == Short.class || type == Integer.class
                || type == Long.class || type == Float.class || type == Double.class || type == Character.class
                || type == java.util.UUID.class || type == java.sql.Date.class || type == java.sql.Time.class
                || type == java.sql.Timestamp.class || type == java.time.LocalDate.class
                || type == java.time.LocalTime.class || type == java.time.LocalDateTime.class
                || type == java.time.OffsetTime.class || type == java.time.OffsetDateTime.class
                || type == java.time.Instant.class) return value.toString();
        if (type == java.math.BigDecimal.class && ((java.math.BigDecimal) value).precision() <= MAX_VALUE) return value.toString();
        if (type == java.math.BigInteger.class && ((java.math.BigInteger) value).bitLength() <= 1700) return value.toString();
        return null; // No byte arrays, streams, collections, arbitrary SDK objects or their toString().
    }

    private static String json(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20 || Character.isSurrogate(c)) {
                out.append("\\u"); String hex = Integer.toHexString(c);
                for (int n = hex.length(); n < 4; n++) out.append('0');
                out.append(hex);
            } else out.append(c);
        }
        return out.append('"').toString();
    }

    private static final class Call {
        final String segment;
        final boolean record;
        final long started = System.nanoTime();
        final Map<String, String> fields = fields("clickhouse-query", "SUBMITTED");
        int depth = 1;
        boolean finished;
        Call(String segment, boolean record) {
            this.segment = segment; this.record = record;
            String thread = Thread.currentThread().getName();
            fields.put("clientThread", thread.substring(0, Math.min(256, thread.length())));
        }
    }
    private static final class Quota {
        final WeakHashMap<TraceSnapshot, Boolean> snapshots = new WeakHashMap<>();
        int count;
        boolean limitEmitted;
        long lastSeen;
    }
}
