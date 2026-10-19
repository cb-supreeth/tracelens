package com.chargebee.tracelens.bridge;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Bounded W3C version-00 carrier. It contains correlation only, never user identity. */
public final class W3CTraceContext {
    private final String traceId;
    private final String spanId;
    private final String flags;
    private final String tracestate;

    private W3CTraceContext(String traceId, String spanId, String flags, String tracestate) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.flags = flags;
        this.tracestate = tracestate;
    }

    /** Unsupported versions and ambiguous/malformed values become a new local root. */
    public static W3CTraceContext parse(String parent, String state) {
        if (parent == null || parent.length() != 55 || !parent.startsWith("00-")
                || parent.charAt(35) != '-' || parent.charAt(52) != '-') return null;
        String trace = parent.substring(3, 35);
        String span = parent.substring(36, 52);
        String flags = parent.substring(53);
        if (!isValidTraceId(trace) || !isValidSpanId(span) || !hex(flags, 2, false)) return null;
        return new W3CTraceContext(trace, span, flags, validTracestate(state));
    }

    public static W3CTraceContext fresh() {
        // Line recording is independent of the upstream tracing vendor's sampled bit.
        return new W3CTraceContext(randomId(), randomId().substring(0, 16), "00", null);
    }

    public W3CTraceContext child() {
        return new W3CTraceContext(traceId, randomId().substring(0, 16), flags, tracestate);
    }

    public static W3CTraceContext fromSnapshot(TraceSnapshot snapshot) {
        if (snapshot == null) return null;
        String span = snapshot.getAttributes().get("spanId");
        String flags = snapshot.getAttributes().get("traceFlags");
        return parse("00-" + snapshot.getTraceId() + "-" + span + "-" + (flags == null ? "00" : flags),
                snapshot.getAttributes().get("tracestate"));
    }

    public String getTraceId() { return traceId; }
    public String getSpanId() { return spanId; }
    public String getFlags() { return flags; }
    public String getTracestate() { return tracestate; }
    public String header() { return "00-" + traceId + "-" + spanId + "-" + flags; }
    public static boolean isValidTraceId(String value) { return hex(value, 32, true); }
    public static boolean isValidSpanId(String value) { return hex(value, 16, true); }

    private static String randomId() { return UUID.randomUUID().toString().replace("-", ""); }

    private static boolean hex(String value, int length, boolean nonzero) {
        if (value == null || value.length() != length) return false;
        boolean any = false;
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
            any |= c != '0';
        }
        return !nonzero || any;
    }

    private static String validTracestate(String state) {
        if (state == null || state.isEmpty() || state.length() > 512) return null;
        String[] entries = state.split(",", -1);
        if (entries.length > 32) return null;
        Set<String> keys = new HashSet<>();
        StringBuilder normalized = new StringBuilder();
        for (String entry : entries) {
            // Only HTTP optional whitespace is accepted, never other control characters.
            entry = entry.replaceAll("^[ \\t]+|[ \\t]+$", "");
            int equals = entry.indexOf('=');
            if (equals < 1 || equals > 256) return null;
            String key = entry.substring(0, equals);
            String value = entry.substring(equals + 1);
            if (!(key.matches("[a-z][a-z0-9_*/-]{0,255}")
                    || key.matches("[a-z0-9][a-z0-9_*/-]{0,240}@[a-z][a-z0-9_*/-]{0,13}"))
                    || !keys.add(key) || value.isEmpty() || value.length() > 256) return null;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c < 32 || c > 126 || c == ',' || c == '=') return null;
            }
            if (normalized.length() > 0) normalized.append(',');
            normalized.append(key).append('=').append(value);
        }
        return normalized.toString();
    }
}
