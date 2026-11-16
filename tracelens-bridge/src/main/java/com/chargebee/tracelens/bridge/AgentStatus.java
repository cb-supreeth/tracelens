package com.chargebee.tracelens.bridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the agent reports about itself, readable by the app (debug API) via the
 * bootstrap. Counts describe the main application transformer, not every HTTP or
 * messaging hook. Verify intended coverage with actual captured work as well.
 */
public final class AgentStatus {

    /** Cap on retained class names (diagnostic list endpoint); counting continues regardless. */
    private static final int MAX_RETAINED_NAMES = 5_000;

    private static final AtomicLong INSTRUMENTED = new AtomicLong();
    private static final AtomicLong FAILED = new AtomicLong();
    private static final ConcurrentLinkedQueue<String> NAMES = new ConcurrentLinkedQueue<>();
    private static volatile String scopeDescription = "";
    private static volatile long premainMillis = -1;
    private static final Map<String, Integration> INTEGRATIONS = new ConcurrentHashMap<>();
    private static final List<String> INTEGRATION_IDS = java.util.Arrays.asList("application-code", "jdbc", "clickhouse", "data-store");
    private static volatile String buildId = "unreported";

    private static final class Integration {
        volatile boolean configured, installed;
        final AtomicLong transformed = new AtomicLong(), failures = new AtomicLong();
    }

    private static Integration integration(String id) {
        if (!INTEGRATION_IDS.contains(id)) return null;
        return INTEGRATIONS.computeIfAbsent(id, key -> new Integration());
    }

    public static void configureIntegration(String id, boolean configured) {
        Integration entry = integration(id); if (entry != null) entry.configured = configured;
    }

    public static void integrationInstalled(String id) {
        Integration entry = integration(id); if (entry != null) entry.installed = true;
    }

    public static void integrationTransformed(String id) {
        Integration entry = integration(id); if (entry != null) entry.transformed.incrementAndGet();
    }

    public static void integrationFailed(String id) {
        Integration entry = integration(id); if (entry != null) entry.failures.incrementAndGet();
    }

    public static Map<String, Map<String, Object>> integrations() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (String id : INTEGRATION_IDS) {
            Integration entry = INTEGRATIONS.get(id); Map<String, Object> row = new LinkedHashMap<>();
            row.put("supported", true);
            row.put("configured", entry == null ? null : entry.configured);
            row.put("transformerInstalled", entry != null && entry.installed);
            row.put("transformedClasses", entry == null ? 0 : entry.transformed.get());
            row.put("failures", entry == null ? 0 : entry.failures.get());
            row.put("state", entry == null ? "unreported" : !entry.configured ? "disabled"
                    : entry.failures.get() > 0 ? "degraded" : entry.transformed.get() > 0 ? "transformation-observed"
                    : entry.installed ? "awaiting-class-load" : "not-installed");
            row.put("note", "Class transformation is not proof of executed hooks or complete request coverage.");
            result.put(id, row);
        }
        return result;
    }

    public static void reportBuildId(String value) {
        if (value != null && value.matches("[0-9a-f]{64}")) buildId = value;
    }

    public static String buildId() { return buildId; }

    private AgentStatus() {
    }

    /** Called by the main application transformer for each successful transformation. */
    public static void noteInstrumentedClass(String fqcn) {
        INSTRUMENTED.incrementAndGet();
        if (NAMES.size() < MAX_RETAINED_NAMES) {
            NAMES.add(fqcn);
        }
    }

    public static void noteFailedClass() {
        FAILED.incrementAndGet();
    }

    public static void reportPremain(long millis, String scope) {
        premainMillis = millis;
        scopeDescription = scope;
    }

    public static long instrumentedCount() {
        return INSTRUMENTED.get();
    }

    public static long failedCount() {
        return FAILED.get();
    }

    public static List<String> instrumentedClassNames() {
        return Collections.unmodifiableList(new ArrayList<>(NAMES));
    }

    public static String scopeDescription() {
        return scopeDescription;
    }

    public static long premainMillis() {
        return premainMillis;
    }

    /** True after premain completion was reported; zero application classes is possible. */
    public static boolean agentActive() {
        return premainMillis >= 0;
    }
}
