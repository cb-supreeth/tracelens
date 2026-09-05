package com.chargebee.tracelens.bridge;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The static API called from woven bytecode. Lives in the bootstrap classloader so
 * every instrumented classloader can reach it.
 *
 * Events flow: woven bytecode -> TraceRuntime.onX -> registered Sink (app-side core).
 * Without a registered sink (agent-only mode, or app not ready yet) events are
 * counted and dropped — never an error, never an exception into app code.
 *
 * All methods on the woven path are static, allocation-free on the drop paths, and
 * swallow nothing — they simply must not throw.
 */
public final class TraceRuntime {

    /** Implemented by the app-side core; registered once the tracing stack is ready. */
    public interface Sink {
        void onLine(String traceId, String fqcn, int line);

        void onMethodEnter(String traceId, String fqcn, String method);

        void onMethodExit(String traceId, String fqcn, String method);

        void onException(String traceId, String fqcn, String method, String exceptionType, String message);
    }

    private static volatile Sink sink;

    private static final AtomicLong EMITTED = new AtomicLong();
    private static final AtomicLong DROPPED_NO_SINK = new AtomicLong();
    private static final AtomicLong DROPPED_NO_TRACE = new AtomicLong();
    private static final AtomicLong WRAPPED_TASKS = new AtomicLong();

    /** Called by TraceContext.wrap when a task is actually wrapped (diagnostics). */
    public static void noteWrappedTask() {
        WRAPPED_TASKS.incrementAndGet();
    }

    public static long wrappedTaskCount() {
        return WRAPPED_TASKS.get();
    }

    private TraceRuntime() {
    }

    // ---- woven-code entry points (called from instrumented bytecode) ----

    public static void onLine(String fqcn, int line) {
        Sink s = sink;
        if (s == null) {
            DROPPED_NO_SINK.incrementAndGet();
            return;
        }
        String traceId = TraceContext.currentTraceId();
        if (traceId == null) {
            DROPPED_NO_TRACE.incrementAndGet();
            return;
        }
        s.onLine(traceId, fqcn, line);
        EMITTED.incrementAndGet();
    }

    public static void onMethodEnter(String fqcn, String method) {
        Sink s = sink;
        if (s == null) {
            DROPPED_NO_SINK.incrementAndGet();
            return;
        }
        String traceId = TraceContext.currentTraceId();
        if (traceId == null) {
            DROPPED_NO_TRACE.incrementAndGet();
            return;
        }
        s.onMethodEnter(traceId, fqcn, method);
        EMITTED.incrementAndGet();
    }

    public static void onMethodExit(String fqcn, String method) {
        Sink s = sink;
        if (s == null) {
            DROPPED_NO_SINK.incrementAndGet();
            return;
        }
        String traceId = TraceContext.currentTraceId();
        if (traceId == null) {
            DROPPED_NO_TRACE.incrementAndGet();
            return;
        }
        s.onMethodExit(traceId, fqcn, method);
        EMITTED.incrementAndGet();
    }

    public static void onException(String fqcn, String method, String exceptionType, String message) {
        Sink s = sink;
        if (s == null) {
            DROPPED_NO_SINK.incrementAndGet();
            return;
        }
        String traceId = TraceContext.currentTraceId();
        if (traceId == null) {
            DROPPED_NO_TRACE.incrementAndGet();
            return;
        }
        s.onException(traceId, fqcn, method, exceptionType, message);
        EMITTED.incrementAndGet();
    }

    // ---- app-side wiring ----

    public static void registerSink(Sink newSink) {
        sink = newSink;
    }

    public static void unregisterSink() {
        sink = null;
    }

    public static boolean hasSink() {
        return sink != null;
    }

    // ---- diagnostics (debug API / shutdown summary) ----

    public static long emittedCount() {
        return EMITTED.get();
    }

    public static long droppedNoSinkCount() {
        return DROPPED_NO_SINK.get();
    }

    public static long droppedNoTraceCount() {
        return DROPPED_NO_TRACE.get();
    }
}
