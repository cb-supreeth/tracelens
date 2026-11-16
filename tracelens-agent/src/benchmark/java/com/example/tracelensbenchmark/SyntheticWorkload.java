package com.example.tracelensbenchmark;

import com.chargebee.tracelens.bridge.AgentStatus;
import com.chargebee.tracelens.bridge.TraceRuntime;
import com.chargebee.tracelens.bridge.TraceContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** CPU work plus a synthetic boundary; no database, network or application data. */
public final class SyntheticWorkload {
    private static final Map<String, String> BOUNDARY;
    static {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("boundaryKind", "database"); fields.put("system", "benchmark");
        fields.put("direction", "outbound"); fields.put("operation", "synthetic");
        fields.put("status", "OBSERVED"); fields.put("evidenceKind", "client-boundary");
        BOUNDARY = Collections.unmodifiableMap(fields);
    }
    public static long request(long value) {
        for (int i = 0; i < 20; i++) {
            value = calculate(value, i);
        }
        if (AgentStatus.agentActive()) TraceRuntime.onBoundary(TraceContext.current().getSegmentId(), BOUNDARY);
        return value;
    }
    private static long calculate(long value, int offset) {
        long mixed = value ^ (offset + 0x9e3779b97f4a7c15L);
        mixed = Long.rotateLeft(mixed, 17);
        return mixed * 31 + offset;
    }
}
