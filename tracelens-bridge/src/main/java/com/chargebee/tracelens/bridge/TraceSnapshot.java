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
 * JVM. For a root segment, segmentId == traceId.
 *
 * Header/attribute names follow FlowLens's flowlens-id / flowlens-user convention.
 */
public final class TraceSnapshot {

    public static final String LENS_ID_KEY = "tracelens-id";
    public static final String USER_KEY = "tracelens-user";
    public static final String TRACE_ID_KEY = "tracelens-trace-id";
    public static final String SEGMENT_ID_KEY = "tracelens-segment-id";

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

    /** Rebuild from transport attributes; null when no trace id is present. */
    public static TraceSnapshot fromMap(Map<String, String> map) {
        if (map == null) {
            return null;
        }
        String traceId = map.get(TRACE_ID_KEY);
        if (traceId == null || traceId.isBlank()) {
            return null;
        }
        Map<String, String> rest = new LinkedHashMap<>(map);
        rest.remove(TRACE_ID_KEY);
        rest.remove(SEGMENT_ID_KEY);
        rest.remove(LENS_ID_KEY);
        rest.remove(USER_KEY);
        String segmentId = map.get(SEGMENT_ID_KEY);
        return new TraceSnapshot(traceId, segmentId == null ? traceId : segmentId,
                map.get(LENS_ID_KEY), map.get(USER_KEY), rest);
    }

    @Override
    public String toString() {
        return "TraceSnapshot{trace=" + traceId + " segment=" + segmentId
                + " lens=" + lensId + " user=" + user + '}';
    }
}
