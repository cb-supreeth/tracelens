package com.chargebee.tracelens.core.record;

import com.chargebee.tracelens.bridge.TraceContext;
import com.chargebee.tracelens.bridge.TraceScope;
import com.chargebee.tracelens.bridge.TraceSnapshot;
import com.chargebee.tracelens.core.store.TraceStore;
import com.chargebee.tracelens.core.trace.EntryChannel;
import com.chargebee.tracelens.core.trace.Trace;

import java.util.Map;
import java.util.Optional;

/**
 * High-level lifecycle API used by the starter's entry points (servlet filter,
 * queueman hook, scheduled-job wrapper): begin a trace (register + activate context),
 * end it (complete + restore context).
 */
public final class Tracer {

    private final TraceStore store;

    public Tracer(TraceStore store) {
        this.store = store;
    }

    /** Begin a fresh trace. Empty when the store rejects registration (capacity/off). */
    public Optional<ActiveTrace> begin(String lensId, String user, EntryChannel channel,
                                       Map<String, String> attributes) {
        Trace trace = new Trace(lensId, user, channel, System.currentTimeMillis(), System.nanoTime());
        if (attributes != null) {
            attributes.forEach(trace::putAttribute);
        }
        if (!store.register(trace)) {
            return Optional.empty();
        }
        TraceSnapshot snapshot = new TraceSnapshot(trace.getTraceId(), lensId, user, attributes);
        return Optional.of(new ActiveTrace(trace, TraceContext.activate(snapshot)));
    }

    /**
     * Continue a trace received over the wire (SQS consumer side). Joins by traceId —
     * the same id is registered on this side too, mirroring FlowLens's cross-pod join.
     * NOTE: single-JVM producer+consumer (local dev) collides on traceId today;
     * segment support lands with async correlation in Phase 5.
     */
    public Optional<ActiveTrace> begin(TraceSnapshot snapshot, EntryChannel channel) {
        Trace trace = new Trace(snapshot.getTraceId(), snapshot.getLensId(), snapshot.getUser(),
                channel, System.currentTimeMillis(), System.nanoTime(), Trace.DEFAULT_MAX_EVENTS_PER_TRACE);
        snapshot.getAttributes().forEach(trace::putAttribute);
        if (!store.register(trace)) {
            return Optional.empty();
        }
        return Optional.of(new ActiveTrace(trace, TraceContext.activate(snapshot)));
    }

    public Optional<Trace> currentTrace() {
        TraceSnapshot snapshot = TraceContext.current();
        return snapshot == null ? Optional.empty() : store.get(snapshot.getTraceId());
    }

    /** A live trace bound to a context scope; close() completes and restores. */
    public static final class ActiveTrace implements AutoCloseable {

        private final Trace trace;
        private final TraceScope scope;
        private boolean closed;

        private ActiveTrace(Trace trace, TraceScope scope) {
            this.trace = trace;
            this.scope = scope;
        }

        public Trace trace() {
            return trace;
        }

        public String traceId() {
            return trace.getTraceId();
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                trace.complete(System.currentTimeMillis());
                scope.close();
            }
        }
    }
}
