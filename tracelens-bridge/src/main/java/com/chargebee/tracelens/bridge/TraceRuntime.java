package com.chargebee.tracelens.bridge;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The static API called from woven bytecode. Lives in the bootstrap classloader so
 * every instrumented classloader can reach it.
 *
 * Events flow: woven bytecode -> TraceRuntime.onX -> registered core Sink, routed
 * by SEGMENT id. The owner can be the standalone agent or a legacy app adapter.
 * Without a sink/context, events are counted and dropped. Observer failures are
 * contained here so woven callbacks cannot replace the application's outcome.
 *
 * Lifecycle hooks (queueman consumer, @Scheduled jobs) go through the registered
 * LifecycleListener; absent one, they are no-ops. Lifecycle and metadata-boundary
 * entry points explicitly contain tracing failures.
 */
public final class TraceRuntime {

    /** Implemented by core; registered by the selected standalone/adapter runtime owner. */
    public interface Sink {
        void onLine(String segmentId, String fqcn, int line);

        void onMethodEnter(String segmentId, String fqcn, String method);

        void onMethodExit(String segmentId, String fqcn, String method);

        void onException(String segmentId, String fqcn, String method, String exceptionType, String message);

        default void onMethodEnter(String segmentId, String fqcn, String method, String descriptor) {
            onMethodEnter(segmentId, fqcn, method);
        }

        default void onMethodExit(String segmentId, String fqcn, String method, String descriptor) {
            onMethodExit(segmentId, fqcn, method);
        }

        default void onException(String segmentId, String fqcn, String method, String descriptor,
                                 String exceptionType, String message) {
            onException(segmentId, fqcn, method, exceptionType, message);
        }

        /** The routing datasource picked a pool (live/test x master/slave x RLS). */
        default void onDbRoute(String segmentId, String routeKey) {
        }

        /** Configured wrapper-entry marker; not proof that a network call occurred. */
        default void onExternalCall(String segmentId, String system, String detail) {
        }

        /** Metadata-only observed client boundary; no message payload or arbitrary headers. */
        default void onBoundary(String segmentId, Map<String, String> attributes) {
        }
    }

    /**
     * Entry/exit lifecycle for agent-driven trace boundaries (message consumption,
     * scheduled jobs). Implemented by core in the selected runtime. begin() activates the
     * context on the calling thread and returns the new segment id; end() completes
     * the segment and restores the previous context. Same-thread begin/end pairing.
     */
    public interface LifecycleListener {
        /** Returns the new segment id, or null when the store rejects recording. */
        String begin(String traceId, String lensId, String user, String entryChannel,
                     Map<String, String> attributes);

        void end(String segmentId);

        /** Optional completion metadata; older listeners retain their original behavior. */
        default void end(String segmentId, Map<String, String> attributes) {
            end(segmentId);
        }
    }

    private static volatile Sink sink;
    private static volatile LifecycleListener lifecycleListener;

    private static final AtomicLong EMITTED = new AtomicLong();
    private static final AtomicLong DROPPED_NO_SINK = new AtomicLong();
    private static final AtomicLong DROPPED_NO_TRACE = new AtomicLong();
    private static final AtomicLong WRAPPED_TASKS = new AtomicLong();
    private static final AtomicLong OBSERVER_FAILURES = new AtomicLong();
    private static final AtomicLong SKIPPED_MESSAGE_PROPAGATIONS = new AtomicLong();

    private TraceRuntime() {
    }

    // ---- woven-code entry points (called from instrumented bytecode) ----

    public static void onLine(String fqcn, int line) {
        try {
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
        } catch (Throwable ignored) { OBSERVER_FAILURES.incrementAndGet(); }
    }

    public static void onMethodEnter(String fqcn, String method) {
        onMethodEnter(fqcn, method, null);
    }

    public static void onMethodEnter(String fqcn, String method, String descriptor) {
        try {
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
            s.onMethodEnter(segmentId, fqcn, method, descriptor);
            EMITTED.incrementAndGet();
        } catch (Throwable ignored) { OBSERVER_FAILURES.incrementAndGet(); }
    }

    public static void onMethodExit(String fqcn, String method) {
        onMethodExit(fqcn, method, null);
    }

    public static void onMethodExit(String fqcn, String method, String descriptor) {
        try {
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
            s.onMethodExit(segmentId, fqcn, method, descriptor);
            EMITTED.incrementAndGet();
        } catch (Throwable ignored) { OBSERVER_FAILURES.incrementAndGet(); }
    }

    public static void onException(String fqcn, String method, String exceptionType, String message) {
        onException(fqcn, method, null, exceptionType, message);
    }

    public static void onException(String fqcn, String method, String descriptor, String exceptionType, String message) {
        try {
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
            s.onException(segmentId, fqcn, method, descriptor, exceptionType, message);
            EMITTED.incrementAndGet();
        } catch (Throwable ignored) { OBSERVER_FAILURES.incrementAndGet(); }
    }

    /** DB_ROUTE event from the RoutingDataSource advice. */
    public static void onDbRoute(String routeKey) {
        try {
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
            s.onDbRoute(segmentId, routeKey);
            EMITTED.incrementAndGet();
        } catch (Throwable ignored) { OBSERVER_FAILURES.incrementAndGet(); }
    }

    /** EXTERNAL_CALL event from external-system wrapper advice. */
    public static void onExternalCall(String system, String detail) {
        try {
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
            s.onExternalCall(segmentId, system, detail);
            EMITTED.incrementAndGet();
        } catch (Throwable ignored) { OBSERVER_FAILURES.incrementAndGet(); }
    }

    /** A boundary is routed to the segment active at entry, even if a callback changes context. */
    public static void onBoundary(String segmentId, Map<String, String> attributes) {
        try {
            Sink s = sink;
            if (s == null) {
                DROPPED_NO_SINK.incrementAndGet();
            } else if (segmentId == null) {
                DROPPED_NO_TRACE.incrementAndGet();
            } else {
                s.onBoundary(segmentId, java.util.Collections.unmodifiableMap(
                        new java.util.LinkedHashMap<>(attributes)));
                EMITTED.incrementAndGet();
            }
        } catch (Throwable ignored) {
            // Observation must never affect a producer, consumer, or its callback.
            OBSERVER_FAILURES.incrementAndGet();
        }
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
            OBSERVER_FAILURES.incrementAndGet();
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
            OBSERVER_FAILURES.incrementAndGet();
        }
    }

    /** Finish an observed lifecycle with bounded, integration-owned metadata. */
    public static void endLifecycle(String segmentId, Map<String, String> attributes) {
        LifecycleListener l = lifecycleListener;
        if (l == null || segmentId == null) return;
        try {
            l.end(segmentId, java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(attributes == null
                            ? java.util.Collections.<String, String>emptyMap() : attributes)));
        } catch (Throwable ignored) {
            // Lifecycle advice must never change the application's outcome.
            OBSERVER_FAILURES.incrementAndGet();
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
        beginMessageAttributes(sendMessageRequest);
    }

    /** Synchronous producer advice restores only its own unchanged injection on exit. */
    public static Object beginMessageAttributes(Object sendMessageRequest) {
        TraceSnapshot snapshot = TraceContext.current();
        if (snapshot == null || sendMessageRequest == null) {
            return null;
        }
        try {
            if (!sendMessageRequest.getClass().getName().equals("com.amazonaws.services.sqs.model.SendMessageRequest")) {
                SKIPPED_MESSAGE_PROPAGATIONS.incrementAndGet();
                return null;
            }
            ClassLoader cl = sendMessageRequest.getClass().getClassLoader();
            Class<?> attrValue = Class.forName("com.amazonaws.services.sqs.model.MessageAttributeValue",
                    false, cl);
            Method getter = sendMessageRequest.getClass().getMethod("getMessageAttributes");
            Method setter = sendMessageRequest.getClass().getMethod("setMessageAttributes", Map.class);
            Map<String, String> carrier = snapshot.toPropagationMap();
            Object existing = getter.invoke(sendMessageRequest);
            if (carrier.isEmpty() || existing != null && !(existing instanceof Map)) {
                SKIPPED_MESSAGE_PROPAGATIONS.incrementAndGet();
                return null;
            }
            Map<?, ?> original = existing == null ? java.util.Collections.emptyMap() : (Map<?, ?>) existing;
            if (original.size() > 10 - carrier.size()) {
                SKIPPED_MESSAGE_PROPAGATIONS.incrementAndGet();
                return null;
            }
            for (Object key : original.keySet()) {
                if (key instanceof String && (((String) key).startsWith("tracelens-")
                        || "siteId".equals(key) || "spanId".equals(key)
                        || "traceFlags".equals(key) || "tracestate".equals(key))) {
                    SKIPPED_MESSAGE_PROPAGATIONS.incrementAndGet();
                    return null;
                }
            }
            Map<Object, Object> merged = new java.util.LinkedHashMap<>(original);
            for (Map.Entry<String, String> e : carrier.entrySet()) {
                Object value = attrValue.getDeclaredConstructor().newInstance();
                attrValue.getMethod("withDataType", String.class).invoke(value, "String");
                attrValue.getMethod("withStringValue", String.class).invoke(value, e.getValue());
                merged.put(e.getKey(), value);
            }
            // The SDK setter replaces the map once; reflection/construction failures
            // above cannot leave a partially injected request.
            MessagePropagation propagation = new MessagePropagation(sendMessageRequest, getter, setter, original, merged);
            setter.invoke(sendMessageRequest, merged);
            propagation.injected = getter.invoke(sendMessageRequest);
            return propagation;
        } catch (Throwable ignored) {
            // attribute injection is best-effort; never break the send
            SKIPPED_MESSAGE_PROPAGATIONS.incrementAndGet();
            return null;
        }
    }

    public static void endMessageAttributes(Object token) {
        if (!(token instanceof MessagePropagation)) return;
        MessagePropagation propagation = (MessagePropagation) token;
        try {
            Object current = propagation.getter.invoke(propagation.request);
            if (current != propagation.injected || !(current instanceof Map)) return;
            Map<?, ?> values = (Map<?, ?>) current;
            if (values.size() != propagation.expected.size()) return;
            for (Map.Entry<Object, Object> entry : propagation.expected.entrySet()) {
                if (values.get(entry.getKey()) != entry.getValue()) return;
            }
            propagation.setter.invoke(propagation.request, propagation.original);
        } catch (Throwable ignored) { OBSERVER_FAILURES.incrementAndGet(); }
    }

    private static final class MessagePropagation {
        final Object request;
        Object injected;
        final Method getter, setter;
        final Map<?, ?> original;
        final Map<Object, Object> expected;
        MessagePropagation(Object request, Method getter, Method setter, Map<?, ?> original,
                           Map<Object, Object> expected) {
            this.request = request;
            this.getter = getter;
            this.setter = setter;
            this.original = original;
            this.expected = expected;
        }
    }

    // ---- app-side wiring ----

    public static synchronized void registerSink(Sink newSink) {
        sink = newSink;
    }

    public static synchronized void unregisterSink() {
        sink = null;
    }

    /** Remove only the caller's registration (safe when another owner has replaced it). */
    public static synchronized void unregisterSink(Sink expected) {
        if (sink == expected) sink = null;
    }

    public static boolean hasSink() {
        return sink != null;
    }

    public static synchronized void registerLifecycleListener(LifecycleListener listener) {
        lifecycleListener = listener;
    }

    public static synchronized void unregisterLifecycleListener() {
        lifecycleListener = null;
    }

    public static synchronized void unregisterLifecycleListener(LifecycleListener expected) {
        if (lifecycleListener == expected) lifecycleListener = null;
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

    public static long observerFailureCount() { return OBSERVER_FAILURES.get(); }

    public static long skippedMessagePropagationCount() { return SKIPPED_MESSAGE_PROPAGATIONS.get(); }
}
