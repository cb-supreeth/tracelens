package com.chargebee.tracelens.bridge;

/**
 * AutoCloseable scope returned by {@link TraceContext#activate}. Restores the
 * previous context on close, so nested executor hops can never leak a trace onto
 * a pooled thread:

 *   try (TraceScope ignored = TraceContext.activate(snapshot)) {
 *       ... work ...
 *   }
 */
public final class TraceScope implements AutoCloseable {

    private final TraceSnapshot previous;
    private boolean closed;

    TraceScope(TraceSnapshot previous) {
        this.previous = previous;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            TraceContext.restore(previous);
        }
    }
}
