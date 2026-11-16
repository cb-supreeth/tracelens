package com.chargebee.tracelens.core.trace;

import com.chargebee.tracelens.core.event.TraceEvent;
import com.chargebee.tracelens.core.event.EventType;
import com.chargebee.tracelens.core.event.RuntimeThreadIdentity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One retained execution flow SEGMENT: an ordered timeline of events plus
 * identity/metadata.
 *
 * traceId is the shared join key across segments/pods (an HTTP request and the SQS
 * consumer it triggered share one traceId). segmentId is unique per segment and is
 * the store's key. For a root segment, segmentId == traceId.
 *
 * Threading: events are appended from every thread the work hops across (servlet
 * threads, executor pools, queueman consumer). Appends are serialized per segment;
 * metadata reads are lock-free. Ordering across threads is by the per-segment
 * sequence number, assigned at append time.
 */
public final class Trace {

    /** Hard per-segment cap so one runaway loop can't eat the whole store budget. */
    public static final int DEFAULT_MAX_EVENTS_PER_TRACE = 50_000;
    /** Metadata stays available when detail fills; included in the store byte reservation. */
    public static final long METADATA_RESERVATION_BYTES = 524288;

    private final String traceId;
    private final String segmentId;
    private final String parentSegmentId;
    private final String lensId;
    /**
     * Usually supplied at trace entry. HTTP traces may replace the auto-generated
     * local value after the security filter resolves the authenticated principal.
     */
    private volatile String user;
    private final EntryChannel entryChannel;
    private final long startedAtMillis;
    private final long startNanos;
    private final int maxEventsPerTrace;

    private final List<TraceEvent> events = new ArrayList<>(256);
    private boolean strongThreadIdentityObserved;
    private final Map<String, Map<String, String>> httpCalls = new java.util.LinkedHashMap<>();
    private volatile boolean httpCallsTruncated;
    private final AtomicLong seq = new AtomicLong();
    private final Map<String, String> attributes = new ConcurrentHashMap<>();

    private volatile long lastActivityNanos;
    private volatile long lastActivityMillis;
    private volatile TraceStatus status = TraceStatus.ACTIVE;
    private volatile Long endedAtMillis;
    private volatile boolean truncated;
    private volatile String captureMode = "detailed";
    private volatile long detailedUntil;
    private volatile long maxSegmentBytes = 32L * 1024 * 1024;
    private long retainedEventBytes;
    private long attributeBytes;
    private long httpCallBytes;
    private boolean serviceInstanceBound;
    private final Map<String, Long> dropped = new java.util.LinkedHashMap<>();
    private long omittedDetailCallbacks;
    private boolean detailGap;

    /** Root segment: fresh traceId, segmentId == traceId. */
    public Trace(String lensId, String user, EntryChannel entryChannel, long nowMillis, long nowNanos) {
        this(UUID.randomUUID().toString(), null, null, lensId, user, entryChannel, nowMillis, nowNanos,
                DEFAULT_MAX_EVENTS_PER_TRACE);
    }

    public Trace(String traceId, String lensId, String user, EntryChannel entryChannel,
                 long nowMillis, long nowNanos, int maxEventsPerTrace) {
        this(traceId, null, null, lensId, user, entryChannel, nowMillis, nowNanos, maxEventsPerTrace);
    }

    /**
     * @param traceId         join key (shared across segments)
     * @param segmentId       unique per segment; null -> root segment (segmentId = traceId)
     * @param parentSegmentId the segment that produced the hand-off message, if any
     */
    public Trace(String traceId, String segmentId, String parentSegmentId, String lensId, String user,
                 EntryChannel entryChannel, long nowMillis, long nowNanos, int maxEventsPerTrace) {
        if (traceId == null || traceId.length() > 128 || segmentId != null && segmentId.length() > 128
                || parentSegmentId != null && parentSegmentId.length() > 128) throw new IllegalArgumentException("Trace identities must be bounded");
        this.traceId = traceId;
        this.segmentId = segmentId == null ? traceId : segmentId;
        this.parentSegmentId = parentSegmentId;
        this.lensId = lensId != null && lensId.length() > 2048 ? null : lensId;
        this.user = user != null && user.length() > 512 ? null : user;
        this.entryChannel = entryChannel;
        this.startedAtMillis = nowMillis;
        this.startNanos = nowNanos;
        this.lastActivityNanos = nowNanos;
        this.lastActivityMillis = nowMillis;
        this.maxEventsPerTrace = maxEventsPerTrace;
        if (this.lensId != lensId || this.user != user) drop("identity-display-field-limit");
    }

    /**
     * Append an event. The event's seq/offset fields are (re)assigned here so callers
     * never have to coordinate ordering. Returns false when the per-trace cap has been
     * reached (trace is marked truncated and further events are dropped).
     */
    public boolean addEvent(TraceEvent event) {
        synchronized (events) {
            boolean critical = isCritical(event);
            if (!critical && (!capturesDetail(event.getType()) || truncated)) {
                noteOmittedDetail(event.getType());
                return false;
            }
            int reserved = Math.min(512, maxEventsPerTrace / 10);
            if (events.size() >= maxEventsPerTrace - (critical ? 0 : reserved)) {
                noteOmittedEvent(!critical);
                drop("segment-event-budget");
                return false;
            }
            long bytes = estimatedEventBytes(event);
            long reserve = critical ? 0 : Math.min(1024 * 1024, maxSegmentBytes / 10);
            if (retainedEventBytes + bytes + METADATA_RESERVATION_BYTES > maxSegmentBytes - reserve) {
                noteOmittedEvent(!critical);
                drop("segment-byte-budget");
                return false;
            }
            long eventSeq = seq.incrementAndGet();
            TraceEvent.Builder ordered = TraceEvent.builder(event.getType())
                    .seq(eventSeq)
                    .epochMillis(event.getEpochMillis())
                    .nanoTime(event.getNanoTime())
                    .thread(event.getThread())
                    .threadId(event.getThreadId())
                    .className(event.getClassName())
                    .methodName(event.getMethodName())
                    .methodDescriptor(event.getMethodDescriptor())
                    .line(event.getLine())
                    .message(event.getMessage()).fieldsTruncated(event.isFieldsTruncated())
                    .detailGapBefore(detailGap || event.isDetailGapBefore());
            // preserve any extra attributes (exceptionType, external call details, ...)
            event.getAttributes().forEach(ordered::attr);
            events.add(ordered.build());
            strongThreadIdentityObserved |= event.getThreadId() != null;
            retainedEventBytes += bytes;
            if (event.isFieldsTruncated()) drop("event-field-limit");
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

    public void configureCapture(boolean detailed, long until, long byteBudget) {
        configureCapture(detailed ? "detailed" : "overview", until, byteBudget);
    }

    public void configureCapture(String mode, long until, long byteBudget) {
        captureMode = mode;
        detailedUntil = until;
        maxSegmentBytes = byteBudget;
        if ("overview".equals(mode)) drop("capture-mode-overview");
    }

    public boolean acceptsDetail(long now) {
        return acceptsDetail(now, EventType.METHOD_ENTER);
    }

    public boolean acceptsDetail(long now, EventType type) {
        synchronized (events) {
            if ("overview".equals(captureMode)) return false;
            if (detailedUntil > 0 && now > detailedUntil) {
                captureMode = "overview";
                drop("detailed-capture-window-ended");
                return false;
            }
            return !truncated && capturesDetail(type);
        }
    }

    private boolean capturesDetail(EventType type) {
        return "detailed".equals(captureMode) || "methods".equals(captureMode) && type != EventType.LINE;
    }

    public void drop(String reason) {
        synchronized (events) {
            truncated = true;
            dropped.put(reason, dropped.getOrDefault(reason, 0L) + 1);
        }
    }

    public Map<String, Long> dropReasons() { synchronized (events) { return new java.util.LinkedHashMap<>(dropped); } }
    public void noteOmittedDetail() { noteOmittedEvent(true); }
    public void noteOmittedDetail(EventType type) {
        synchronized (events) {
            // A methods-only timeline is complete without line callbacks.
            if (type != EventType.LINE || !"methods".equals(captureMode)) noteOmittedDetail();
        }
    }
    public void noteOmittedEvent(boolean detail) { synchronized (events) { if (detail) omittedDetailCallbacks++; detailGap = true; } }
    public long omittedDetailCallbacks() { synchronized (events) { return omittedDetailCallbacks; } }
    public String captureMode() { return captureMode; }
    public long retainedBytes() { synchronized (events) { return METADATA_RESERVATION_BYTES + retainedEventBytes; } }
    public long lastSequence() { return seq.get(); }
    public static boolean isCritical(TraceEvent event) {
        return event.getType() != EventType.LINE && event.getType() != EventType.METHOD_ENTER && event.getType() != EventType.METHOD_EXIT;
    }
    public static long estimatedEventBytes(TraceEvent event) {
        long bytes = 280 + textBytes(event.getThread()) + textBytes(event.getClassName()) + textBytes(event.getMethodName())
                + textBytes(event.getMethodDescriptor()) + textBytes(event.getMessage());
        for (Map.Entry<String, String> entry : event.getAttributes().entrySet()) bytes += 64 + textBytes(entry.getKey()) + textBytes(entry.getValue());
        return bytes;
    }
    private static long textBytes(String value) { return value == null ? 0 : 40L + 2L * value.length(); }

    /** Copy only a bounded slice; readers never materialize the entire retained timeline. */
    public List<TraceEvent> eventSlice(long after, long through, int limit, String kind) {
        List<TraceEvent> result = new ArrayList<>();
        synchronized (events) {
            int start = (int) Math.min(events.size(), after);
            for (int i = start; i < events.size() && result.size() < limit; i++) {
                TraceEvent event = events.get(i);
                if (event.getSeq() > through) break;
                if ("methods".equals(kind) && event.getType() == EventType.LINE) continue;
                if ("boundaries".equals(kind) && !isCritical(event)) continue;
                result.add(event);
            }
        }
        return result;
    }

    public int eventCount() {
        synchronized (events) {
            return events.size();
        }
    }

    /**
     * Bounded upsert: client calls are metadata, not additional line events.
     * The first ACTIVE record is emitted synchronously at the client boundary.
     * Keep its timeline anchor separate from completion metadata: a completion
     * can run on a different thread and cannot establish where a call began.
     */
    public void recordHttpCall(Map<String, String> call) {
        if (call == null || call.get("spanId") == null) return;
        synchronized (httpCalls) {
            String key = call.get("spanId");
            Map<String, String> previous = httpCalls.get(key);
            if (previous == null && httpCalls.size() >= 64) {
                httpCallsTruncated = true;
                return;
            }
            Map<String, String> bounded = new java.util.LinkedHashMap<>();
            if (previous == null) {
                captureHttpCallingContext(call, bounded);
            } else {
                // These fields belong to the recorder, not the supplied client map.
                copyCallingContext(previous, bounded);
            }
            for (Map.Entry<String, String> entry : call.entrySet()) {
                if (bounded.size() >= 24) break;
                if (isCallingContextField(entry.getKey())) continue;
                String value = entry.getValue();
                if (entry.getKey() != null && entry.getKey().length() <= 64 && value != null) {
                    bounded.put(entry.getKey(), value.substring(0, Math.min(value.length(), 512)));
                }
            }
            long size = mapBytes(bounded), previousSize = previous == null ? 0 : mapBytes(previous);
            if (httpCallBytes - previousSize + size > 393216) { httpCallsTruncated = true; return; }
            httpCallBytes += size - previousSize;
            httpCalls.put(key, Collections.unmodifiableMap(bounded));
        }
        touch(System.currentTimeMillis(), System.nanoTime());
    }

    private void captureHttpCallingContext(Map<String, String> call, Map<String, String> target) {
        if (!"ACTIVE".equals(call.get("status"))) {
            target.put("callingContextStatus", "start-not-observed");
            return;
        }
        target.put("callingContextStatus", "source-not-observed");
        String thread = Thread.currentThread().getName();
        Long threadId = RuntimeThreadIdentity.current();
        synchronized (events) {
            // A dropped exit/line could make an older frame look like the caller.
            // Do not infer a start anchor across a known retention gap.
            if (truncated) return;
            // Legacy captures can only use names. Never mix that fallback with runtime IDs.
            if (!strongThreadIdentityObserved && (thread == null || thread.isEmpty() || thread.length() > 512)) return;
            for (int i = events.size() - 1; i >= 0; i--) {
                TraceEvent event = events.get(i);
                if (strongThreadIdentityObserved ? threadId.equals(event.getThreadId()) : thread.equals(event.getThread())) {
                    target.put("callingContextStatus", "recorded");
                    target.put("callingEventSeq", Long.toString(event.getSeq()));
                    if (strongThreadIdentityObserved) target.put("callingThreadId", threadId.toString());
                    if (event.getThread() != null) target.put("callingThread", event.getThread());
                    return;
                }
            }
        }
    }

    private static void copyCallingContext(Map<String, String> source, Map<String, String> target) {
        for (String key : new String[]{"callingContextStatus", "callingEventSeq", "callingThread", "callingThreadId"}) {
            String value = source.get(key);
            if (value != null) target.put(key, value);
        }
    }

    private static boolean isCallingContextField(String key) {
        return "callingContextStatus".equals(key) || "callingEventSeq".equals(key)
                || "callingThread".equals(key) || "callingThreadId".equals(key);
    }

    public List<Map<String, String>> httpCalls() {
        synchronized (httpCalls) { return new ArrayList<>(httpCalls.values()); }
    }

    public boolean isHttpCallsTruncated() { return httpCallsTruncated; }

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
        if (value != null) java.util.Objects.requireNonNull(key, "attribute key");
        if (key != null && key.length() <= 128 && value != null) {
            synchronized (attributes) {
                boolean instance = "serviceInstanceId".equals(key);
                if (instance && (value.trim().isEmpty() || value.length() > 128
                        || serviceInstanceBound && !value.equals(attributes.get(key)))) {
                    drop("service-instance-change-rejected");
                    return;
                }
                String bounded = value.substring(0, Math.min(value.length(), 16384));
                String previous = attributes.get(key);
                long difference = textBytes(bounded) - textBytes(previous) + (previous == null ? textBytes(key) + 64 : 0);
                int slots = instance || attributes.containsKey("serviceInstanceId") ? 64 : 63;
                long bytes = instance || attributes.containsKey("serviceInstanceId") ? 65536 : 65024;
                if ((previous == null && attributes.size() >= slots) || attributeBytes + difference > bytes) {
                    drop("metadata-byte-budget");
                    return;
                }
                attributes.put(key, bounded);
                attributeBytes += difference;
                if (bounded.length() != value.length()) drop("metadata-field-limit");
            }
        }
    }

    /** Paging identity has reserved capacity and cannot change after retention admission. */
    public void bindServiceInstance(String fallback) {
        if (fallback == null || fallback.trim().isEmpty() || fallback.length() > 128)
            throw new IllegalArgumentException("Service instance identity must be bounded");
        synchronized (attributes) {
            if (!attributes.containsKey("serviceInstanceId")) putAttribute("serviceInstanceId", fallback);
            serviceInstanceBound = true;
        }
    }

    private static long mapBytes(Map<String, String> map) {
        long result = 64;
        for (Map.Entry<String, String> entry : map.entrySet()) result += 64 + textBytes(entry.getKey()) + textBytes(entry.getValue());
        return result;
    }

    public void touch(long epochMillis, long nanos) {
        this.lastActivityMillis = epochMillis;
        this.lastActivityNanos = nanos;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSegmentId() {
        return segmentId;
    }

    public String getParentSegmentId() {
        return parentSegmentId;
    }

    public String getLensId() {
        return lensId;
    }

    public String getUser() {
        return user;
    }

    /**
     * Refines a display identity discovered after trace creation (for example,
     * cb-ms authentication runs after TraceLens's early servlet filter). This is
     * metadata only; it never changes trace routing or propagation identity.
     */
    public void setUser(String user) {
        if (user != null && !user.trim().isEmpty() && user.length() <= 512) {
            this.user = user;
        }
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
        synchronized (httpCalls) { return 2_000L + eventCount() * 120L + httpCalls.size() * 2_000L; }
    }

    @Override
    public String toString() {
        return "Trace{" + traceId + " lens=" + lensId + " channel=" + entryChannel
                + " status=" + status + " events=" + eventCount() + '}';
    }
}
