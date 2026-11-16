package com.chargebee.tracelens.bridge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable carrier for trace identity across threads (executor tasks) and processes
 * (SQS message attributes). This is the OTel-free correlation seam: anything that can
 * carry a Map<String,String> can carry a trace.
 *
 * Two ids: traceId is the shared join key across segments/pods (FlowLens's flowlens
 * join semantics); segmentId routes events to one concrete retained trace within a
 * JVM. The legacy root convenience constructor uses segmentId == traceId;
 * distributed HTTP roots use a W3C trace id and a separate segment UUID.
 *
 * Diagnostic labels use tracelens-* names; HTTP W3C propagation has its own carrier
 * and does not automatically transmit this entire attribute map.
 */
public final class TraceSnapshot {

    public static final String LENS_ID_KEY = "tracelens-id";
    public static final String USER_KEY = "tracelens-user";
    public static final String TRACE_ID_KEY = "tracelens-trace-id";
    public static final String SEGMENT_ID_KEY = "tracelens-segment-id";
    /** Browser/user-intent id shared by all requests started by one UI action. */
    public static final String ACTION_ID_KEY = "tracelens-action-id";
    /** Human-readable name for the browser/user action, e.g. "update-user-licence". */
    public static final String ACTION_NAME_KEY = "tracelens-action-name";
    /** Opaque browser-session identifier; never use a cookie or other secret here. */
    public static final String SESSION_ID_KEY = "tracelens-session-id";

    private final String traceId;
    private final String segmentId;
    private final String lensId;
    private final String user;
    private final Map<String, String> attributes;

    /** Root-segment convenience: segmentId == traceId. */
    public TraceSnapshot(String traceId, String lensId, String user, Map<String, String> attributes) {
        this(traceId, traceId, lensId, user, attributes);
    }

    public TraceSnapshot(String traceId, String segmentId, String lensId, String user,
                         Map<String, String> attributes) {
        this.traceId = Objects.requireNonNull(traceId, "traceId");
        this.segmentId = Objects.requireNonNull(segmentId, "segmentId");
        this.lensId = lensId;
        this.user = user;
        this.attributes = attributes == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSegmentId() {
        return segmentId;
    }

    public String getLensId() {
        return lensId;
    }

    public String getUser() {
        return user;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    /** Flatten to message attributes / headers for transport (SQS, HTTP, ...). */
    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>(attributes);
        map.put(TRACE_ID_KEY, traceId);
        map.put(SEGMENT_ID_KEY, segmentId);
        if (lensId != null) {
            map.put(LENS_ID_KEY, lensId);
        }
        if (user != null) {
            map.put(USER_KEY, user);
        }
        return map;
    }

    /** Explicit messaging carrier: local request metadata must never become message attributes. */
    public Map<String, String> toPropagationMap() {
        Map<String, String> map = new LinkedHashMap<>();
        if (!putPropagation(map, TRACE_ID_KEY, traceId, 128)
                || !putPropagation(map, SEGMENT_ID_KEY, segmentId, 128)) return Collections.emptyMap();
        putPropagation(map, LENS_ID_KEY, lensId, 256);
        putPropagation(map, USER_KEY, user, 256);
        putPropagation(map, ACTION_ID_KEY, propagationAttribute(ACTION_ID_KEY, "actionId"), 256);
        putPropagation(map, ACTION_NAME_KEY, propagationAttribute(ACTION_NAME_KEY, "actionName"), 256);
        putPropagation(map, SESSION_ID_KEY, propagationAttribute(SESSION_ID_KEY, "sessionId"), 256);
        putPropagation(map, "siteId", attributes.get("siteId"), 256);
        W3CTraceContext context = W3CTraceContext.fromSnapshot(this);
        if (context != null) {
            map.put("spanId", context.getSpanId());
            map.put("traceFlags", context.getFlags());
            if (map.size() < 10) putPropagation(map, "tracestate", context.getTracestate(), 512);
        }
        // Prefer core correlation when the ten-slot ceiling excludes tracestate.
        return map;
    }

    private String propagationAttribute(String wireKey, String localKey) {
        String wire = attributes.get(wireKey), local = attributes.get(localKey);
        return wire != null && local != null && !wire.equals(local) ? null : wire != null ? wire : local;
    }

    private static boolean putPropagation(Map<String, String> map, String key, String value, int limit) {
        if (value == null || value.isEmpty() || value.length() > limit) return false;
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return false;
        map.put(key, value);
        return true;
    }

    /** Rebuild from transport attributes; null when no trace id is present. */
    public static TraceSnapshot fromMap(Map<String, String> map) {
        if (map == null) {
            return null;
        }
        String traceId = map.get(TRACE_ID_KEY);
        if (traceId == null || traceId.trim().isEmpty()) {
            return null;
        }
        Map<String, String> rest = new LinkedHashMap<>(map);
        rest.remove(TRACE_ID_KEY);
        rest.remove(SEGMENT_ID_KEY);
        rest.remove(LENS_ID_KEY);
        rest.remove(USER_KEY);
        restorePropagationAttribute(rest, ACTION_ID_KEY, "actionId");
        restorePropagationAttribute(rest, ACTION_NAME_KEY, "actionName");
        restorePropagationAttribute(rest, SESSION_ID_KEY, "sessionId");
        String segmentId = map.get(SEGMENT_ID_KEY);
        return new TraceSnapshot(traceId, segmentId == null ? traceId : segmentId,
                map.get(LENS_ID_KEY), map.get(USER_KEY), rest);
    }

    private static void restorePropagationAttribute(Map<String, String> attributes, String wireKey, String localKey) {
        String value = attributes.remove(wireKey);
        if (value != null) attributes.put(localKey, value);
    }

    @Override
    public String toString() {
        return "TraceSnapshot{trace=" + traceId + " segment=" + segmentId
                + " lens=" + lensId + " user=" + user + '}';
    }
}
