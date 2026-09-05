package com.chargebee.tracelens.core.record;

import com.chargebee.tracelens.bridge.TraceContext;
import com.chargebee.tracelens.bridge.TraceScope;
import com.chargebee.tracelens.bridge.TraceSnapshot;
import com.chargebee.tracelens.core.store.TraceStore;
import com.chargebee.tracelens.core.trace.EntryChannel;
import com.chargebee.tracelens.core.trace.Trace;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * High-level lifecycle API used by the starter's entry points (servlet filter,
 * queueman hook, scheduled-job wrapper): begin a trace segment (register + activate
 * context), end it (complete + restore context).
 *
 * Segment model: traceId is the join key across segments/pods; segmentId routes
 * events within this JVM. Root segments have segmentId == traceId.
 */
public final class Tracer {

    private final TraceStore store;

    public Tracer(TraceStore store) {
        this.store = store;
    }

    /** Begin a root segment (fresh traceId). Empty when the store rejects recording. */
    public Optional<ActiveTrace> begin(String lensId, String user, EntryChannel channel,
                                       Map<String, String> attributes) {
        Trace trace = new Trace(lensId, user, channel, System.currentTimeMillis(), System.nanoTime());
        if (attributes != null) {
            attributes.forEach(trace::putAttribute);
        }
        if (!store.register(trace)) {
            return Optional.empty();
        }
        TraceSnapshot snapshot = new TraceSnapshot(trace.getTraceId(), trace.getSegmentId(),
                lensId, user, attributes);
        return Optional.of(new ActiveTrace(trace, TraceContext.activate(snapshot)));
    }

    /**
     * Continue a trace received over the wire (SQS consumer side): same traceId
     * (join key), fresh segmentId, parentSegmentId = the producing segment.
     */
    public Optional<ActiveTrace> beginSegment(TraceSnapshot snapshot, EntryChannel channel) {
        Trace trace = new Trace(snapshot.getTraceId(), UUID.randomUUID().toString(),
                snapshot.getSegmentId(), snapshot.getLensId(), snapshot.getUser(),
                channel, System.currentTimeMillis(), System.nanoTime(), Trace.DEFAULT_MAX_EVENTS_PER_TRACE);
        snapshot.getAttributes().forEach(trace::putAttribute);
        if (!store.register(trace)) {
            return Optional.empty();
        }
        TraceSnapshot segmentSnapshot = new TraceSnapshot(trace.getTraceId(), trace.getSegmentId(),
                snapshot.getLensId(), snapshot.getUser(), snapshot.getAttributes());
        return Optional.of(new ActiveTrace(trace, TraceContext.activate(segmentSnapshot)));
    }

    public Optional<Trace> currentTrace() {
        TraceSnapshot snapshot = TraceContext.current();
        return snapshot == null ? Optional.empty() : store.get(snapshot.getSegmentId());
    }

    /** A live segment bound to a context scope; close() completes and restores. */
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

        /** The store key (export by this id). */
        public String segmentId() {
            return trace.getSegmentId();
        }

        /** The join key shared across segments. */
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
