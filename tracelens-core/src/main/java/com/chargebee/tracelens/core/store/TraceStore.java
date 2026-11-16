package com.chargebee.tracelens.core.store;

import com.chargebee.tracelens.core.config.TraceLensConfig;
import com.chargebee.tracelens.core.event.TraceEvent;
import com.chargebee.tracelens.core.event.EventType;
import com.chargebee.tracelens.core.trace.EntryChannel;
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
 *  - maxTrackedRequests: global segment cap, with borrowable HTTP/background shares
 *  - maxTrackedEvents:   global event ceiling, partitioned into fixed channel budgets
 *  - httpRetentionPercent: HTTP reservation; 0 restores shared least-activity eviction
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
    private final long maxTrackedBytes;
    private final TraceLensConfig captureConfig;
    private final String serviceInstanceId = java.util.UUID.randomUUID().toString();
    private long retainedBytes;
    private long byteDroppedEvents;
    private final long idleTtlNanos;
    private final boolean evictOnExport;
    private final int httpRetentionPercent;
    private final int httpRequestReserve;
    private final long httpEventBudget;
    private final long backgroundEventBudget;
    private final LongSupplier nanoClock;
    private final LongSupplier millisClock;

    /** Insertion-ordered storage; eviction explicitly compares segment activity. */
    private final Map<String, Trace> traces = new LinkedHashMap<>();
    private final AtomicLong totalEvents = new AtomicLong();
    private final AtomicBoolean recordingEnabled = new AtomicBoolean(true);
    // Guarded by traces. Updating these does not allocate or scan on line-event appends.
    private int httpRequests;
    private long httpEvents;
    private long capacityEvictions;
    private long idleEvictions;
    private long budgetDroppedEvents;

    private ScheduledExecutorService sweeper;

    public TraceStore(TraceLensConfig config) {
        this(config, System::nanoTime, System::currentTimeMillis);
    }

    /** Test-friendly constructor with injectable clocks. */
    public TraceStore(TraceLensConfig config, LongSupplier nanoClock, LongSupplier millisClock) {
        this.maxTrackedRequests = config.getMaxTrackedRequests();
        this.maxTrackedEvents = config.getMaxTrackedEvents();
        this.maxTrackedBytes = config.getMaxTrackedBytes();
        this.captureConfig = new TraceLensConfig().setCaptureMode(config.getCaptureMode())
                .setDetailedCapturePaths(config.getDetailedCapturePaths())
                .setDetailedCaptureUntilEpochMillis(config.getDetailedCaptureUntilEpochMillis())
                .setMaxSegmentBytes(config.getMaxSegmentBytes());
        this.idleTtlNanos = TimeUnit.MINUTES.toNanos(config.getIdleTtlMinutes());
        this.evictOnExport = config.isEvictOnExport();
        this.httpRetentionPercent = config.getHttpRetentionPercent();
        this.httpRequestReserve = (int) reservedShare(maxTrackedRequests, httpRetentionPercent);
        this.httpEventBudget = reservedShare(maxTrackedEvents, httpRetentionPercent);
        this.backgroundEventBudget = maxTrackedEvents - httpEventBudget;
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
            Trace existing = traces.get(trace.getSegmentId());
            if (existing != null) return existing == trace; // Never replace retained events silently.
            trace.configureCapture(captureConfig.effectiveCaptureMode(trace.getAttributes().get("path"), millisClock.getAsLong()),
                    captureConfig.getDetailedCaptureUntilEpochMillis(), captureConfig.getMaxSegmentBytes());
            boolean http = isHttp(trace);
            long budget = eventBudget(http);
            int initialEvents = trace.eventCount();
            if (initialEvents > budget || budget == 0) return false;
            while (traces.size() >= maxTrackedRequests) {
                Boolean victimChannel = null;
                if (httpRetentionPercent > 0) {
                    int ownCount = http ? httpRequests : traces.size() - httpRequests;
                    int ownReserve = http ? httpRequestReserve : maxTrackedRequests - httpRequestReserve;
                    int otherCount = traces.size() - ownCount;
                    // A channel reclaims borrowed slots only until it reaches its share.
                    victimChannel = otherCount > 0 && ownCount < ownReserve ? !http : http;
                }
                if (!removeEldest(victimChannel)) return false;
            }
            // Registration is the slow path. Only the incoming channel's older evidence
            // is expendable for its event budget, never the other channel's reservation.
            long required = Math.max(1, initialEvents);
            while (channelEvents(http) > budget - required) {
                if (!removeEldest(httpRetentionPercent > 0 ? http : null)) return false;
            }
            if (trace.retainedBytes() > maxTrackedBytes) return false;
            while (retainedBytes > maxTrackedBytes - trace.retainedBytes()) {
                if (!removeEldest(httpRetentionPercent > 0 ? http : null)) return false;
            }
            trace.bindServiceInstance(serviceInstanceId);
            traces.put(trace.getSegmentId(), trace);
            retainedBytes += trace.retainedBytes();
            totalEvents.addAndGet(initialEvents);
            if (http) {
                httpRequests++;
                httpEvents += initialEvents;
            }
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
            boolean http = isHttp(trace);
            boolean critical = Trace.isCritical(event);
            if (!critical && !trace.acceptsDetail(millisClock.getAsLong(), event.getType())) { trace.noteOmittedDetail(event.getType()); return false; }
            long eventReserve = critical ? 0 : Math.min(4096, eventBudget(http) / 10);
            if (totalEvents.get() >= maxTrackedEvents || channelEvents(http) >= eventBudget(http) - eventReserve) {
                budgetDroppedEvents++;
                trace.noteOmittedEvent(!critical);
                trace.drop("store-event-budget");
                return false;
            }
            long bytes = Trace.estimatedEventBytes(event), byteReserve = critical ? 0 : Math.min(8L * 1024 * 1024, maxTrackedBytes / 10);
            if (retainedBytes + bytes > maxTrackedBytes - byteReserve) {
                byteDroppedEvents++;
                trace.noteOmittedEvent(!critical);
                trace.drop("store-byte-budget");
                return false;
            }
            boolean added = trace.addEvent(event);
            if (added) {
                totalEvents.incrementAndGet();
                retainedBytes += bytes;
                if (http) httpEvents++;
            }
            return added;
        }
    }

    public Optional<Trace> get(String traceId) {
        synchronized (traces) {
            return Optional.ofNullable(traces.get(traceId));
        }
    }

    /** Check before allocating line/method events in overview-only or exhausted captures. */
    public boolean acceptsDetail(String segmentId) {
        return acceptsDetail(segmentId, EventType.METHOD_ENTER);
    }
    public boolean acceptsDetail(String segmentId, EventType type) {
        synchronized (traces) {
            Trace trace = traces.get(segmentId);
            return trace != null && trace.acceptsDetail(millisClock.getAsLong(), type);
        }
    }
    public void recordDetailOmission(String segmentId) {
        recordDetailOmission(segmentId, EventType.METHOD_ENTER);
    }
    public void recordDetailOmission(String segmentId, EventType type) {
        synchronized (traces) {
            Trace trace = traces.get(segmentId);
            if (trace != null) trace.noteOmittedDetail(type);
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
            retainedBytes = 0;
            httpRequests = 0;
            httpEvents = 0;
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

    /** Small diagnostic snapshot; counters are lifetime totals, not reset by clearing traces. */
    public Map<String, Object> retentionSummary() {
        synchronized (traces) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("policy", httpRetentionPercent == 0 ? "shared" : "http-reserved");
            out.put("httpRetentionPercent", httpRetentionPercent);
            out.put("httpRequestReserve", httpRequestReserve);
            out.put("backgroundRequestReserve", httpRetentionPercent == 0 ? 0 : maxTrackedRequests - httpRequestReserve);
            out.put("httpEventBudget", eventBudget(true));
            out.put("backgroundEventBudget", eventBudget(false));
            out.put("httpRequests", httpRequests);
            out.put("backgroundRequests", traces.size() - httpRequests);
            out.put("httpEvents", httpEvents);
            out.put("backgroundEvents", totalEvents.get() - httpEvents);
            out.put("capacityEvictions", capacityEvictions);
            out.put("idleEvictions", idleEvictions);
            out.put("budgetDroppedEvents", budgetDroppedEvents);
            out.put("estimatedRetainedBytes", retainedBytes);
            out.put("maxTrackedBytes", maxTrackedBytes);
            out.put("byteDroppedEvents", byteDroppedEvents);
            out.put("httpCriticalEventReserve", Math.min(4096, eventBudget(true) / 10));
            out.put("backgroundCriticalEventReserve", Math.min(4096, eventBudget(false) / 10));
            out.put("criticalByteReserve", Math.min(8L * 1024 * 1024, maxTrackedBytes / 10));
            out.put("metadataReservationPerSegment", Trace.METADATA_RESERVATION_BYTES);
            out.put("byteAccounting", "conservative event estimate plus bounded metadata reservation; not exact JVM heap");
            return out;
        }
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
            idleEvictions += idle.size();
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

    private static boolean isHttp(Trace trace) {
        return trace.getEntryChannel() == EntryChannel.HTTP;
    }

    private long eventBudget(boolean http) {
        return httpRetentionPercent == 0 ? maxTrackedEvents : http ? httpEventBudget : backgroundEventBudget;
    }

    private long channelEvents(boolean http) {
        return httpRetentionPercent == 0 ? totalEvents.get() : http ? httpEvents : totalEvents.get() - httpEvents;
    }

    private static long reservedShare(long capacity, int percent) {
        if (percent == 0) return 0;
        if (capacity <= 1) return capacity;
        // Avoid overflow even for a very large configured event budget.
        long share = (capacity / 100) * percent + ((capacity % 100) * percent) / 100;
        return Math.max(1, Math.min(capacity - 1, share));
    }

    /** null selects all channels (legacy); otherwise select only HTTP/background. */
    private boolean removeEldest(Boolean http) {
        Trace eldest = null;
        for (Trace candidate : traces.values()) {
            if (http != null && isHttp(candidate) != http) continue;
            if (eldest == null || candidate.getLastActivityNanos() < eldest.getLastActivityNanos()) {
                eldest = candidate;
            }
        }
        if (eldest == null) return false;
        remove(eldest.getSegmentId());
        capacityEvictions++;
        return true;
    }

    private Trace remove(String traceId) {
        Trace removed = traces.remove(traceId);
        if (removed != null) {
            totalEvents.addAndGet(-removed.eventCount());
            retainedBytes -= removed.retainedBytes();
            if (isHttp(removed)) {
                httpRequests--;
                httpEvents -= removed.eventCount();
            }
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
