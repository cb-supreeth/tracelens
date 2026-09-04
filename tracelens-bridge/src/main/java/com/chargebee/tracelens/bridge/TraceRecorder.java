package com.chargebee.tracelens.bridge;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Bridge between woven bytecode and the agent. This class is injected into the
 * bootstrap classloader by the agent (appendToBootstrapClassLoaderSearch) so that
 * instrumented application classes in any classloader can call it.
 *
 * Spike version: count line events and keep a small sample for verification.
 * Allocation-free after the sample fills up — this sits on the hottest path
 * imaginable (every executed line), so it must stay trivial.
 */
public final class TraceRecorder {

    public static final int SAMPLE_CAP = 512;

    private static final AtomicLong lineEvents = new AtomicLong();
    private static final String[] sample = new String[SAMPLE_CAP];

    private TraceRecorder() {
    }

    /** Called from woven bytecode at every executed line. Keep this method tiny. */
    public static void onLine(String fqcn, int line) {
        long n = lineEvents.incrementAndGet();
        if (n <= SAMPLE_CAP) {
            sample[(int) (n - 1)] = fqcn + ":" + line;
        }
    }

    public static long lineEventCount() {
        return lineEvents.get();
    }

    public static String[] sample() {
        return sample;
    }
}
