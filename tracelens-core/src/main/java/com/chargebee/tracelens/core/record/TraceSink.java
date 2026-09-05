package com.chargebee.tracelens.core.record;

import com.chargebee.tracelens.bridge.TraceRuntime;
import com.chargebee.tracelens.core.event.TraceEvent;
import com.chargebee.tracelens.core.store.TraceStore;

/**
 * App-side sink: receives events from the bridge (woven bytecode) and records them
 * into the store. Sits on the hottest path in the process — one TraceEvent
 * allocation per line executed — so keep it dumb and fast.
 */
public final class TraceSink implements TraceRuntime.Sink {

    private final TraceStore store;

    public TraceSink(TraceStore store) {
        this.store = store;
    }

    @Override
    public void onLine(String traceId, String fqcn, int line) {
        store.appendEvent(traceId, TraceEvent.line(0, System.currentTimeMillis(), System.nanoTime(),
                Thread.currentThread().getName(), fqcn, line));
    }

    @Override
    public void onMethodEnter(String traceId, String fqcn, String method) {
        store.appendEvent(traceId, TraceEvent.method(0, System.currentTimeMillis(), System.nanoTime(),
                Thread.currentThread().getName(), com.chargebee.tracelens.core.event.EventType.METHOD_ENTER,
                fqcn, method));
    }

    @Override
    public void onMethodExit(String traceId, String fqcn, String method) {
        store.appendEvent(traceId, TraceEvent.method(0, System.currentTimeMillis(), System.nanoTime(),
                Thread.currentThread().getName(), com.chargebee.tracelens.core.event.EventType.METHOD_EXIT,
                fqcn, method));
    }

    @Override
    public void onException(String traceId, String fqcn, String method, String exceptionType, String message) {
        store.appendEvent(traceId, TraceEvent.exception(0, System.currentTimeMillis(), System.nanoTime(),
                Thread.currentThread().getName(), fqcn, method, exceptionType, message));
    }
}
