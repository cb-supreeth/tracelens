package com.chargebee.tracelens.core.store;

import com.chargebee.tracelens.core.config.TraceLensConfig;
import com.chargebee.tracelens.core.event.TraceEvent;
import com.chargebee.tracelens.core.trace.Trace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * In-memory segment retention:
 *
 *  - maxTrackedRequests: cap on retained segments, evicting least recent activity
 *  - maxTrackedEvents:   global event ceiling; registration can evict older segments;
 *                        appends at capacity are dropped without setting truncated
 *  - idleTtlMinutes:     sweeper evicts segments with no event/HTTP activity this long
 *  - evictOnExport:      configurable destructive export (unless explicitly overridden)
 *
 * Map operations are synchronized. Eviction selects the oldest lastActivityNanos;
 * this is not access-order LRU, and inspection reads do not refresh activity/TTL.
 * Per-segment event-cap truncation is handled separately by Trace.addEvent.
 */
public final class TraceStore implements AutoCloseable {

    private final int maxTrackedRequests;
    private final long maxTrackedEvents;
    private final long idleTtlNanos;
    private final boolean evictOnExport;
    private final LongSupplier nanoClock;
    private final LongSupplier millisClock;

    /** Insertion-ordered storage; eviction explicitly compares segment activity. */
    private final Map<String, Trace> traces = new LinkedHashMap<>();
    private final AtomicLong totalEvents = new AtomicLong();
    private final AtomicBoolean recordingEnabled = new AtomicBoolean(true);

    private ScheduledExecutorService sweeper;

    public TraceStore(TraceLensConfig config) {
        this(config, System::nanoTime, System::currentTimeMillis);
    }

    /** Test-friendly constructor with injectable clocks. */
    public TraceStore(TraceLensConfig config, LongSupplier nanoClock, LongSupplier millisClock) {
        this.maxTrackedRequests = config.getMaxTrackedRequests();
        this.maxTrackedEvents = config.getMaxTrackedEvents();
        this.idleTtlNanos = TimeUnit.MINUTES.toNanos(config.getIdleTtlMinutes());
        this.evictOnExport = config.isEvictOnExport();
        this.nanoClock = nanoClock;
        this.millisClock = millisClock;
    }

    /**
     * Register a new trace. Returns false when recording is disabled or the store is
     * at capacity even after evicting older-activity entries (capacity protects the service;
     * a rejected registration must never break the request).
     */
    public boolean register(Trace trace) {
        if (!recordingEnabled.get()) {
            return false;
        }
        synchronized (traces) {
            evictLruUntilUnderRequestCap(maxTrackedRequests - 1);
            if (totalEvents.get() >= maxTrackedEvents) {
                evictLruUntilUnderEventBudget();
            }
            if (totalEvents.get() >= maxTrackedEvents) {
                return false;
            }
            traces.put(trace.getSegmentId(), trace);
            return true;
        }
    }

    /**
     * Append an event to a trace. Hot path — called per executed line once the agent
     * is wired in. Returns false when the trace is unknown/full or the store is at
     * its global event ceiling.
     */
    public boolean appendEvent(String traceId, TraceEvent event) {
        synchronized (traces) {
            Trace trace = traces.get(traceId); // Lookup itself does not refresh activity.
            if (trace == null) {
                return false;
            }
            if (totalEvents.get() >= maxTrackedEvents) {
                return false;
            }
            boolean added = trace.addEvent(event);
            if (added) {
                totalEvents.incrementAndGet();
            }
            return added;
        }
    }

    public Optional<Trace> get(String traceId) {
        synchronized (traces) {
            return Optional.ofNullable(traces.get(traceId));
        }
    }

    /** Find retained traces by their human/test label (labels may repeat). */
    public List<Trace> findByLensId(String lensId) {
        synchronized (traces) {
            List<Trace> matches = new ArrayList<>();
            for (Trace trace : traces.values()) {
                if (lensId.equals(trace.getLensId())) {
                    matches.add(trace);
                }
            }
            return matches;
        }
    }

    /** All retained segments of one logical trace (the join key), oldest first. */
    public List<Trace> findByTraceId(String traceId) {
        synchronized (traces) {
            List<Trace> matches = new ArrayList<>();
            for (Trace trace : traces.values()) {
                if (traceId.equals(trace.getTraceId())) {
                    matches.add(trace);
                }
            }
            matches.sort(Comparator.comparingLong(Trace::getStartedAtMillis));
            return matches;
        }
    }

    /**
     * Export semantics: marks the trace EXPORTED and, when evictOnExport is set,
     * removes it (destructive read — FlowLens default). Returns the trace either way.
     */
    public Optional<Trace> export(String segmentId) {
        return export(segmentId, null);
    }

    /**
     * Export with a per-call eviction override (null = use the configured default).
     * MCP/CLI inspection passes evict=false so reading a trace doesn't remove it.
     */
    public Optional<Trace> export(String segmentId, Boolean evictOverride) {
        boolean evict = evictOverride == null ? evictOnExport : evictOverride;
        synchronized (traces) {
            Trace trace = traces.get(segmentId);
            if (trace == null) {
                return Optional.empty();
            }
            trace.markExported();
            if (evict) {
                remove(segmentId);
            }
            return Optional.of(trace);
        }
    }

    public boolean reset(String traceId) {
        synchronized (traces) {
            return remove(traceId) != null;
        }
    }

    public int resetAll() {
        synchronized (traces) {
            int n = traces.size();
            traces.clear();
            totalEvents.set(0);
            return n;
        }
    }

    public List<TraceSummary> summaries() {
        synchronized (traces) {
            List<TraceSummary> out = new ArrayList<>(traces.size());
            for (Trace trace : traces.values()) {
                out.add(new TraceSummary(trace));
            }
            return out;
        }
    }

    public int size() {
        synchronized (traces) {
            return traces.size();
        }
    }

    public long totalEvents() {
        return totalEvents.get();
    }

    public boolean isRecordingEnabled() {
        return recordingEnabled.get();
    }

    public void setRecordingEnabled(boolean enabled) {
        recordingEnabled.set(enabled);
    }

    /** Evict traces idle longer than the TTL. Also callable directly (tests). */
    public int sweepIdle() {
        long now = nanoClock.getAsLong();
        synchronized (traces) {
            List<String> idle = new ArrayList<>();
            for (Trace trace : traces.values()) {
                if (now - trace.getLastActivityNanos() > idleTtlNanos) {
                    idle.add(trace.getSegmentId());
                }
            }
            idle.forEach(this::remove);
            return idle.size();
        }
    }

    /** Start the background sweeper (daemon thread; period = max(1 min, ttl/2)). */
    public void startSweeper() {
        if (sweeper != null) {
            return;
        }
        long periodMinutes = Math.max(1, TimeUnit.NANOSECONDS.toMinutes(idleTtlNanos) / 2);
        sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tracelens-sweeper");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleWithFixedDelay(this::sweepQuietly, periodMinutes, periodMinutes, TimeUnit.MINUTES);
    }

    private void sweepQuietly() {
        try {
            sweepIdle();
        } catch (Throwable ignored) {
            // a sweeper must never take the service down
        }
    }

    private void evictLruUntilUnderRequestCap(int cap) {
        while (traces.size() > cap) {
            removeEldest();
        }
    }

    private void evictLruUntilUnderEventBudget() {
        while (totalEvents.get() >= maxTrackedEvents && !traces.isEmpty()) {
            removeEldest();
        }
    }

    private void removeEldest() {
        traces.values().stream()
                .min(Comparator.comparingLong(Trace::getLastActivityNanos))
                .ifPresent(eldest -> remove(eldest.getSegmentId()));
    }

    private Trace remove(String traceId) {
        Trace removed = traces.remove(traceId);
        if (removed != null) {
            totalEvents.addAndGet(-removed.eventCount());
        }
        return removed;
    }

    @Override
    public void close() {
        if (sweeper != null) {
            sweeper.shutdownNow();
            sweeper = null;
        }
    }
}
