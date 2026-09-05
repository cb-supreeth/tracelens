package com.chargebee.tracelens.core.context;

/**
 * Thread-local holder for the current trace identity. The agent's woven bytecode and
 * the starter's filters/decorators both go through here — it is the single seam that
 * makes "which trace is this line running for" answerable anywhere in the process.
 *
 * Plain ThreadLocal (not InheritableThreadLocal): inheritance is unpredictable with
 * pooled threads; propagation across pools is explicit via snapshots.
 */
public final class TraceContext {

    private static final ThreadLocal<TraceSnapshot> CURRENT = new ThreadLocal<>();

    private TraceContext() {
    }

    public static TraceSnapshot current() {
        return CURRENT.get();
    }

    /** Activate a snapshot; returns a scope that restores the previous one on close. */
    public static TraceScope activate(TraceSnapshot snapshot) {
        TraceSnapshot previous = CURRENT.get();
        CURRENT.set(snapshot);
        return new TraceScope(previous);
    }

    static void restore(TraceSnapshot previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }
}
