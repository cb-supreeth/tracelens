package com.chargebee.tracelens.core.record;

import com.chargebee.tracelens.bridge.TraceRuntime;
import com.chargebee.tracelens.core.event.EventType;
import com.chargebee.tracelens.core.event.TraceEvent;
import com.chargebee.tracelens.core.event.RuntimeThreadIdentity;
import com.chargebee.tracelens.core.store.TraceStore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * App-side sink: receives events from the bridge (woven bytecode) and records them
 * into the store. Sits on the hottest path in the process — one TraceEvent
 * allocation per line executed — so keep it dumb and fast.
 *
 * DB_ROUTE events are change-detected per segment: determineCurrentLookupKey fires
 * per connection acquisition (potentially hundreds per request), but the useful
 * signal is the route CHANGE.
 */
public final class TraceSink implements TraceRuntime.Sink {

    /** segmentId -> last route key; bounded, best-effort (stale entries are harmless). */
    private static final int MAX_ROUTE_ENTRIES = 4096;

    private final TraceStore store;
    private final Map<String, String> lastDbRoute = new ConcurrentHashMap<>();

    public TraceSink(TraceStore store) {
        this.store = store;
    }

    @Override
    public void onLine(String traceId, String fqcn, int line) {
        if (!store.acceptsDetail(traceId, EventType.LINE)) { store.recordDetailOmission(traceId, EventType.LINE); return; }
        store.appendEvent(traceId, event(EventType.LINE).className(fqcn).line(line).build());
    }

    @Override
    public void onMethodEnter(String traceId, String fqcn, String method) {
        onMethodEnter(traceId, fqcn, method, null);
    }

    @Override
    public void onMethodEnter(String traceId, String fqcn, String method, String descriptor) {
        if (!store.acceptsDetail(traceId)) { store.recordDetailOmission(traceId); return; }
        store.appendEvent(traceId, event(EventType.METHOD_ENTER).className(fqcn).methodName(method)
                .methodDescriptor(descriptor).build());
    }

    @Override
    public void onMethodExit(String traceId, String fqcn, String method) {
        onMethodExit(traceId, fqcn, method, null);
    }

    @Override
    public void onMethodExit(String traceId, String fqcn, String method, String descriptor) {
        if (!store.acceptsDetail(traceId)) { store.recordDetailOmission(traceId); return; }
        store.appendEvent(traceId, event(EventType.METHOD_EXIT).className(fqcn).methodName(method)
                .methodDescriptor(descriptor).build());
    }

    @Override
    public void onException(String traceId, String fqcn, String method, String exceptionType, String message) {
        onException(traceId, fqcn, method, null, exceptionType, message);
    }

    @Override
    public void onException(String traceId, String fqcn, String method, String descriptor, String exceptionType, String message) {
        store.appendEvent(traceId, event(EventType.EXCEPTION).className(fqcn).methodName(method)
                .methodDescriptor(descriptor).message(message).attr("exceptionType", exceptionType).build());
    }

    private static TraceEvent.Builder event(EventType type) {
        return TraceEvent.builder(type).epochMillis(System.currentTimeMillis()).nanoTime(System.nanoTime())
                .thread(Thread.currentThread().getName()).threadId(RuntimeThreadIdentity.current());
    }

    @Override
    public void onDbRoute(String segmentId, String routeKey) {
        if (lastDbRoute.size() < MAX_ROUTE_ENTRIES) {
            String previous = lastDbRoute.put(segmentId, routeKey);
            if (routeKey.equals(previous)) {
                return; // same pool as last time on this segment — the common case
            }
        }
        store.appendEvent(segmentId, event(EventType.DB_ROUTE)
                .message("route -> " + routeKey)
                .attr("route", routeKey)
                .build());
    }

    @Override
    public void onExternalCall(String segmentId, String system, String detail) {
        store.appendEvent(segmentId, event(EventType.EXTERNAL_CALL)
                .message(system + "." + detail)
                .attr("system", system)
                .attr("detail", detail)
                .build());
    }

    @Override
    public void onBoundary(String segmentId, Map<String, String> attributes) {
        TraceEvent.Builder event = event(EventType.EXTERNAL_CALL)
                .message(attributes.get("system") + " " + attributes.get("direction")
                        + " " + attributes.get("destination"));
        attributes.forEach(event::attr);
        store.appendEvent(segmentId, event.build());
    }
}
