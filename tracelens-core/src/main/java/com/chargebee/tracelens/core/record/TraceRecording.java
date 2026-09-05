package com.chargebee.tracelens.core.record;

import com.chargebee.tracelens.bridge.TraceRuntime;
import com.chargebee.tracelens.core.store.TraceStore;

/**
 * Installs/uninstalls the core sink into the bridge. Called by the starter at
 * context startup (and by tests / dev harnesses directly).
 */
public final class TraceRecording {

    private TraceRecording() {
    }

    public static synchronized void install(TraceStore store) {
        TraceRuntime.registerSink(new TraceSink(store));
    }

    public static synchronized void uninstall() {
        TraceRuntime.unregisterSink();
    }

    public static boolean isInstalled() {
        return TraceRuntime.hasSink();
    }
}
