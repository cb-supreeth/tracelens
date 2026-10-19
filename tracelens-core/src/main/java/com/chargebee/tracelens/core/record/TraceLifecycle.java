package com.chargebee.tracelens.core.record;

import com.chargebee.tracelens.bridge.TraceRuntime;
import com.chargebee.tracelens.bridge.TraceSnapshot;
import com.chargebee.tracelens.core.trace.EntryChannel;

import java.util.Map;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges the agent's lifecycle hooks (queueman consumer, @Scheduled jobs) to the
 * core: implements the bridge's LifecycleListener SPI. begin/end are same-thread
 * paired by the calling advice; the map retains scopes until end closes them.
 * It does not automatically clean up a missing end call.
 */
public final class TraceLifecycle implements TraceRuntime.LifecycleListener {

    private final Tracer tracer;
    private final Map<String, Tracer.ActiveTrace> active = new ConcurrentHashMap<>();

    public TraceLifecycle(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public String begin(String traceId, String lensId, String user, String entryChannel,
                        Map<String, String> attributes) {
        EntryChannel channel = parseChannel(entryChannel);
        Optional<Tracer.ActiveTrace> begun;
        if (traceId == null) {
            begun = tracer.begin(lensId, user, channel, attributes);
        } else {
            // continued segment: the wire attributes carry the parent's ids
            // (tracelens-segment-id = producing segment); rebuild the wire snapshot
            Map<String, String> wire = new java.util.LinkedHashMap<>(
                    attributes == null ? Collections.emptyMap() : attributes);
            wire.put(TraceSnapshot.TRACE_ID_KEY, traceId);
            if (lensId != null) {
                wire.put(TraceSnapshot.LENS_ID_KEY, lensId);
            }
            if (user != null) {
                wire.put(TraceSnapshot.USER_KEY, user);
            }
            begun = tracer.beginSegment(TraceSnapshot.fromMap(wire), channel);
        }
        begun.ifPresent(t -> active.put(t.segmentId(), t));
        return begun.map(Tracer.ActiveTrace::segmentId).orElse(null);
    }

    @Override
    public void end(String segmentId) {
        Tracer.ActiveTrace trace = active.remove(segmentId);
        if (trace != null) {
            trace.close();
        }
    }

    private static EntryChannel parseChannel(String entryChannel) {
        if (entryChannel == null) {
            return EntryChannel.OTHER;
        }
        try {
            return EntryChannel.valueOf(entryChannel);
        } catch (IllegalArgumentException e) {
            return EntryChannel.OTHER;
        }
    }
}
