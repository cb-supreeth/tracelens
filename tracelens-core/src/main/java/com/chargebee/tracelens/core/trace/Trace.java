package com.chargebee.tracelens.core.trace;

import com.chargebee.tracelens.core.event.TraceEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One retained execution flow: an ordered timeline of events plus identity/metadata.
 *
 * Threading: events are appended from every thread the work hops across (servlet
 * threads, executor pools, queueman consumer). Appends are serialized per trace;
 * metadata reads are lock-free. Ordering across threads is by the per-trace sequence
 * number, assigned at append time.
 */
public final class Trace {

    /** Hard per-trace cap so one runaway loop can't eat the whole store budget. */
    public static final int DEFAULT_MAX_EVENTS_PER_TRACE = 50_000;

    private final String traceId;
    private final String lensId;
    private final String user;
    private final EntryChannel entryChannel;
    private final long startedAtMillis;
    private final long startNanos;
    private final int maxEventsPerTrace;

    private final List<TraceEvent> events = new ArrayList<>(256);
    private final AtomicLong seq = new AtomicLong();
    private final Map<String, String> attributes = new ConcurrentHashMap<>();

    private volatile long lastActivityNanos;
    private volatile long lastActivityMillis;
    private volatile TraceStatus status = TraceStatus.ACTIVE;
    private volatile Long endedAtMillis;
    private volatile boolean truncated;

    public Trace(String lensId, String user, EntryChannel entryChannel, long nowMillis, long nowNanos) {
        this(UUID.randomUUID().toString(), lensId, user, entryChannel, nowMillis, nowNanos,
                DEFAULT_MAX_EVENTS_PER_TRACE);
    }

    public Trace(String traceId, String lensId, String user, EntryChannel entryChannel,
                 long nowMillis, long nowNanos, int maxEventsPerTrace) {
        this.traceId = traceId;
        this.lensId = lensId;
        this.user = user;
        this.entryChannel = entryChannel;
        this.startedAtMillis = nowMillis;
        this.startNanos = nowNanos;
        this.lastActivityNanos = nowNanos;
        this.lastActivityMillis = nowMillis;
        this.maxEventsPerTrace = maxEventsPerTrace;
    }

    /**
     * Append an event. The event's seq/offset fields are (re)assigned here so callers
     * never have to coordinate ordering. Returns false when the per-trace cap has been
     * reached (trace is marked truncated and further events are dropped).
     */
    public boolean addEvent(TraceEvent event) {
        synchronized (events) {
            if (truncated) {
                return false;
            }
            if (events.size() >= maxEventsPerTrace) {
                truncated = true;
                return false;
            }
            long eventSeq = seq.incrementAndGet();
            TraceEvent.Builder ordered = TraceEvent.builder(event.getType())
                    .seq(eventSeq)
                    .epochMillis(event.getEpochMillis())
                    .offsetNanos(event.getOffsetNanos())
                    .thread(event.getThread())
                    .className(event.getClassName())
                    .methodName(event.getMethodName())
                    .line(event.getLine())
                    .message(event.getMessage());
            // preserve any extra attributes (exceptionType, external call details, ...)
            event.getAttributes().forEach(ordered::attr);
            events.add(ordered.build());
            lastActivityNanos = System.nanoTime();
            lastActivityMillis = event.getEpochMillis();
            return true;
        }
    }

    /** Snapshot of the timeline for export/rendering. */
    public List<TraceEvent> events() {
        synchronized (events) {
            return Collections.unmodifiableList(new ArrayList<>(events));
        }
    }

    public int eventCount() {
        synchronized (events) {
            return events.size();
        }
    }

    public void complete(long endedAtMillis) {
        if (status == TraceStatus.ACTIVE) {
            this.endedAtMillis = endedAtMillis;
            this.status = TraceStatus.COMPLETED;
        }
    }

    public void markExported() {
        this.status = TraceStatus.EXPORTED;
    }

    public void putAttribute(String key, String value) {
        if (value != null) {
            attributes.put(key, value);
        }
    }

    public void touch(long epochMillis, long nanos) {
        this.lastActivityMillis = epochMillis;
        this.lastActivityNanos = nanos;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getLensId() {
        return lensId;
    }

    public String getUser() {
        return user;
    }

    public EntryChannel getEntryChannel() {
        return entryChannel;
    }

    public long getStartedAtMillis() {
        return startedAtMillis;
    }

    public long getStartNanos() {
        return startNanos;
    }

    public long getLastActivityNanos() {
        return lastActivityNanos;
    }

    public long getLastActivityMillis() {
        return lastActivityMillis;
    }

    public TraceStatus getStatus() {
        return status;
    }

    public Long getEndedAtMillis() {
        return endedAtMillis;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public Map<String, String> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }

    public Long durationMillis() {
        Long end = endedAtMillis;
        return end == null ? null : end - startedAtMillis;
    }

    /** Rough heap estimate for the summary endpoint (~120B per event + overhead). */
    public long approxSizeBytes() {
        return 2_000L + eventCount() * 120L;
    }

    @Override
    public String toString() {
        return "Trace{" + traceId + " lens=" + lensId + " channel=" + entryChannel
                + " status=" + status + " events=" + eventCount() + '}';
    }
}
