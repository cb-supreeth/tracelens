package com.chargebee.tracelens.bridge;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The static API called from woven bytecode. Lives in the bootstrap classloader so
 * every instrumented classloader can reach it.
 *
 * Events flow: woven bytecode -> TraceRuntime.onX -> registered Sink (app-side core),
 * routed by the current SEGMENT id. Without a registered sink (agent-only mode, or
 * app not ready yet) events are counted and dropped — never an error into app code.
 *
 * Lifecycle hooks (queueman consumer, @Scheduled jobs) go through the registered
 * LifecycleListener (app-side core); absent one, they are no-ops.
 */
public final class TraceRuntime {

    /** Implemented by the app-side core; registered once the tracing stack is ready. */
    public interface Sink {
        void onLine(String segmentId, String fqcn, int line);

        void onMethodEnter(String segmentId, String fqcn, String method);

        void onMethodExit(String segmentId, String fqcn, String method);

        void onException(String segmentId, String fqcn, String method, String exceptionType, String message);
    }

    /**
     * Entry/exit lifecycle for agent-driven trace boundaries (message consumption,
     * scheduled jobs). Implemented by the app-side core. begin() activates the
     * context on the calling thread and returns the new segment id; end() completes
     * the segment and restores the previous context. Same-thread begin/end pairing.
     */
    public interface LifecycleListener {
        /** Returns the new segment id, or null when the store rejects recording. */
        String begin(String traceId, String lensId, String user, String entryChannel,
                     Map<String, String> attributes);

        void end(String segmentId);
    }

    private static volatile Sink sink;
    private static volatile LifecycleListener lifecycleListener;

    private static final AtomicLong EMITTED = new AtomicLong();
    private static final AtomicLong DROPPED_NO_SINK = new AtomicLong();
    private static final AtomicLong DROPPED_NO_TRACE = new AtomicLong();
    private static final AtomicLong WRAPPED_TASKS = new AtomicLong();

    private TraceRuntime() {
    }

    // ---- woven-code entry points (called from instrumented bytecode) ----

    public static void onLine(String fqcn, int line) {
        Sink s = sink;
        if (s == null) {
            DROPPED_NO_SINK.incrementAndGet();
            return;
        }
        String segmentId = TraceContext.currentSegmentId();
        if (segmentId == null) {
            DROPPED_NO_TRACE.incrementAndGet();
            return;
        }
        s.onLine(segmentId, fqcn, line);
        EMITTED.incrementAndGet();
    }

    public static void onMethodEnter(String fqcn, String method) {
        Sink s = sink;
        if (s == null) {
            DROPPED_NO_SINK.incrementAndGet();
            return;
        }
        String segmentId = TraceContext.currentSegmentId();
        if (segmentId == null) {
            DROPPED_NO_TRACE.incrementAndGet();
            return;
        }
        s.onMethodEnter(segmentId, fqcn, method);
        EMITTED.incrementAndGet();
    }

    public static void onMethodExit(String fqcn, String method) {
        Sink s = sink;
        if (s == null) {
            DROPPED_NO_SINK.incrementAndGet();
            return;
        }
        String segmentId = TraceContext.currentSegmentId();
        if (segmentId == null) {
            DROPPED_NO_TRACE.incrementAndGet();
            return;
        }
        s.onMethodExit(segmentId, fqcn, method);
        EMITTED.incrementAndGet();
    }

    public static void onException(String fqcn, String method, String exceptionType, String message) {
        Sink s = sink;
        if (s == null) {
            DROPPED_NO_SINK.incrementAndGet();
            return;
        }
        String segmentId = TraceContext.currentSegmentId();
        if (segmentId == null) {
            DROPPED_NO_TRACE.incrementAndGet();
            return;
        }
        s.onException(segmentId, fqcn, method, exceptionType, message);
        EMITTED.incrementAndGet();
    }

    // ---- lifecycle hooks (called from agent advice for queueman/@Scheduled seams) ----

    /** Begin a segment via the registered listener; null when recording is off/absent. */
    public static String beginLifecycle(String traceId, String lensId, String user,
                                        String entryChannel, Map<String, String> attributes) {
        LifecycleListener l = lifecycleListener;
        if (l == null) {
            return null;
        }
        try {
            return l.begin(traceId, lensId, user, entryChannel, attributes);
        } catch (Throwable t) {
            return null; // tracing must never break the host code path
        }
    }

    public static void endLifecycle(String segmentId) {
        LifecycleListener l = lifecycleListener;
        if (l == null || segmentId == null) {
            return;
        }
        try {
            l.end(segmentId);
        } catch (Throwable ignored) {
            // tracing must never break the host code path
        }
    }

    // ---- SQS producer injection (reflection-based; the bridge stays SDK-free) ----

    /**
     * Inject the current trace identity as SQS message attributes on an AWS SDK v1
     * SendMessageRequest. Called from agent advice; reflection keeps the bridge free
     * of SDK types (the advice never references SDK classes either). No-op without an
     * active trace or when the request shape is unfamiliar.
     */
    public static void injectMessageAttributes(Object sendMessageRequest) {
        TraceSnapshot snapshot = TraceContext.current();
        if (snapshot == null || sendMessageRequest == null) {
            return;
        }
        try {
            ClassLoader cl = sendMessageRequest.getClass().getClassLoader();
            Class<?> attrValue = Class.forName("com.amazonaws.services.sqs.model.MessageAttributeValue",
                    false, cl);
            Method addEntry = sendMessageRequest.getClass()
                    .getMethod("addMessageAttributesEntry", String.class, attrValue);
            for (Map.Entry<String, String> e : snapshot.toMap().entrySet()) {
                Object value = attrValue.getDeclaredConstructor().newInstance();
                attrValue.getMethod("withDataType", String.class).invoke(value, "String");
                attrValue.getMethod("withStringValue", String.class).invoke(value, e.getValue());
                addEntry.invoke(sendMessageRequest, e.getKey(), value);
            }
        } catch (Throwable ignored) {
            // attribute injection is best-effort; never break the send
        }
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

    public static void registerLifecycleListener(LifecycleListener listener) {
        lifecycleListener = listener;
    }

    public static void unregisterLifecycleListener() {
        lifecycleListener = null;
    }

    /** Called by TraceContext.wrap when a task is actually wrapped (diagnostics). */
    public static void noteWrappedTask() {
        WRAPPED_TASKS.incrementAndGet();
    }

    public static long wrappedTaskCount() {
        return WRAPPED_TASKS.get();
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
