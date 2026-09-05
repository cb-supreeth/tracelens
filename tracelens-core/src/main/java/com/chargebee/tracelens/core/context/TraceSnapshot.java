package com.chargebee.tracelens.core.context;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable carrier for trace identity across threads (executor tasks) and processes
 * (SQS message attributes). This is the OTel-free correlation seam: anything that can
 * carry a Map<String,String> can carry a trace.
 *
 * Header/attribute names follow FlowLens's flowlens-id / flowlens-user convention.
 */
public final class TraceSnapshot {

    public static final String LENS_ID_KEY = "tracelens-id";
    public static final String USER_KEY = "tracelens-user";
    public static final String TRACE_ID_KEY = "tracelens-trace-id";

    private final String traceId;
    private final String lensId;
    private final String user;
    private final Map<String, String> attributes;

    public TraceSnapshot(String traceId, String lensId, String user, Map<String, String> attributes) {
        this.traceId = Objects.requireNonNull(traceId, "traceId");
        this.lensId = lensId;
        this.user = user;
        this.attributes = attributes == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public String getTraceId() {
        return traceId;
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
        rest.remove(LENS_ID_KEY);
        rest.remove(USER_KEY);
        return new TraceSnapshot(traceId, map.get(LENS_ID_KEY), map.get(USER_KEY), rest);
    }

    @Override
    public String toString() {
        return "TraceSnapshot{" + traceId + " lens=" + lensId + " user=" + user + '}';
    }
}
