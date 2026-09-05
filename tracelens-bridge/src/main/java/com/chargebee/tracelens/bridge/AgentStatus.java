package com.chargebee.tracelens.bridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the agent reports about itself, readable by the app (debug API) via the
 * bootstrap. Powers the FlowLens-style verification sequence: "summary shows non-zero
 * instrumented-class count" proves the agent is alive and scoped correctly.
 */
public final class AgentStatus {

    /** Cap on retained class names (diagnostic list endpoint); counting continues regardless. */
    private static final int MAX_RETAINED_NAMES = 5_000;

    private static final AtomicLong INSTRUMENTED = new AtomicLong();
    private static final AtomicLong FAILED = new AtomicLong();
    private static final ConcurrentLinkedQueue<String> NAMES = new ConcurrentLinkedQueue<>();
    private static volatile String scopeDescription = "";
    private static volatile long premainMillis = -1;

    private AgentStatus() {
    }

    /** Called by the agent for every successfully transformed class (boot-time only). */
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

    /** True when the agent is attached and has woven at least one class. */
    public static boolean agentActive() {
        return premainMillis >= 0;
    }
}
