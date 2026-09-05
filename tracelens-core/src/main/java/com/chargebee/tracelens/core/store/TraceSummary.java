package com.chargebee.tracelens.core.store;

import com.chargebee.tracelens.core.trace.EntryChannel;
import com.chargebee.tracelens.core.trace.Trace;
import com.chargebee.tracelens.core.trace.TraceStatus;

/** Lightweight view of a retained trace for the list/summary endpoints. */
public final class TraceSummary {

    private final String traceId;
    private final String segmentId;
    private final String lensId;
    private final String user;
    private final EntryChannel entryChannel;
    private final TraceStatus status;
    private final long startedAtMillis;
    private final long lastActivityMillis;
    private final int eventCount;
    private final long approxSizeBytes;
    private final boolean truncated;

    public TraceSummary(Trace trace) {
        this.traceId = trace.getTraceId();
        this.segmentId = trace.getSegmentId();
        this.lensId = trace.getLensId();
        this.user = trace.getUser();
        this.entryChannel = trace.getEntryChannel();
        this.status = trace.getStatus();
        this.startedAtMillis = trace.getStartedAtMillis();
        this.lastActivityMillis = trace.getLastActivityMillis();
        this.eventCount = trace.eventCount();
        this.approxSizeBytes = trace.approxSizeBytes();
        this.truncated = trace.isTruncated();
    }

    public String getTraceId() {
        return traceId;
    }

    /** The store key — export by this id. */
    public String getSegmentId() {
        return segmentId;
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

    public TraceStatus getStatus() {
        return status;
    }

    public long getStartedAtMillis() {
        return startedAtMillis;
    }

    public long getLastActivityMillis() {
        return lastActivityMillis;
    }

    public int getEventCount() {
        return eventCount;
    }

    public long getApproxSizeBytes() {
        return approxSizeBytes;
    }

    public boolean isTruncated() {
        return truncated;
    }
}
