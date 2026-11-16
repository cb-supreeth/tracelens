package com.chargebee.tracelens.bridge;

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

    /** Hot-path accessor used by {@link TraceRuntime}: current trace id or null. */
    public static String currentTraceId() {
        TraceSnapshot snapshot = CURRENT.get();
        return snapshot == null ? null : snapshot.getTraceId();
    }

    /** Event routing key: the current segment id (events belong to one segment). */
    public static String currentSegmentId() {
        TraceSnapshot snapshot = CURRENT.get();
        return snapshot == null ? null : snapshot.getSegmentId();
    }

    /** Activate a snapshot; returns a scope that restores the previous one on close. */
    public static TraceScope activate(TraceSnapshot snapshot) {
        TraceSnapshot previous = CURRENT.get();
        TraceScope scope = new TraceScope(previous);
        CURRENT.set(snapshot);
        return scope;
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

    /**
     * Capture the current snapshot into a task for executor hops. Used by the
     * starter's explicit TaskDecorators. Automatic executor advice associates
     * context without replacing the application's task identity.
     * Already-wrapped tasks (TracedTask) and no-trace tasks pass through untouched.
     */
    public static Runnable wrap(Runnable task) {
        TraceSnapshot snapshot = CURRENT.get();
        if (task == null || snapshot == null || task instanceof TracedTask) {
            return task;
        }
        TraceRuntime.noteWrappedTask();
        class Wrapped implements Runnable, TracedTask {
            @Override
            public void run() {
                try (TraceScope ignored = activate(snapshot)) {
                    task.run();
                }
            }
        }
        return new Wrapped();
    }

    public static <T> java.util.concurrent.Callable<T> wrap(java.util.concurrent.Callable<T> task) {
        TraceSnapshot snapshot = CURRENT.get();
        if (task == null || snapshot == null || task instanceof TracedTask) {
            return task;
        }
        TraceRuntime.noteWrappedTask();
        class Wrapped implements java.util.concurrent.Callable<T>, TracedTask {
            @Override
            public T call() throws Exception {
                try (TraceScope ignored = activate(snapshot)) {
                    return task.call();
                }
            }
        }
        return new Wrapped();
    }
}
