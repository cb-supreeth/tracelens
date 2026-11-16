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
    private final Long durationMs;
    private final boolean truncated;
    private final String actionId;
    private final String actionName;
    private final String sessionId;
    private final String parentSegmentId;
    private final String spanId;
    private final String parentSpanId;
    private final String httpMethod;
    private final String path;
    private final String httpStatus;
    private final String siteId;
    private final String routeTemplate;
    private final String filterException;
    private final String asyncTimeout;
    private final String serviceInstanceId;
    private final String jobType;
    private final String jobId;
    private final String jobOutcome;
    private final String jobAuditStatus;
    private final String jobFailureType;
    private final String scheduler;
    private final int httpCallCount;
    private final boolean httpCallsTruncated;
    private final String captureMode;
    private final long estimatedRetainedBytes;
    private final java.util.Map<String, Long> dropReasons;

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
        this.durationMs = trace.durationMillis();
        this.truncated = trace.isTruncated();
        this.actionId = trace.getAttributes().get("actionId");
        this.actionName = trace.getAttributes().get("actionName");
        this.sessionId = trace.getAttributes().get("sessionId");
        this.parentSegmentId = trace.getParentSegmentId();
        this.spanId = trace.getAttributes().get("spanId");
        this.parentSpanId = trace.getAttributes().get("parentSpanId");
        this.httpMethod = trace.getAttributes().get("httpMethod");
        this.path = trace.getAttributes().get("path");
        this.httpStatus = trace.getAttributes().get("httpStatus");
        this.siteId = trace.getAttributes().get("siteId");
        this.routeTemplate = trace.getAttributes().get("route");
        // Request-boundary evidence only. Counting arbitrary EXCEPTION line
        // events here would be expensive and would misclassify caught failures.
        this.filterException = trace.getAttributes().get("filterException");
        this.asyncTimeout = trace.getAttributes().get("asyncTimeout");
        this.serviceInstanceId = trace.getAttributes().get("serviceInstanceId");
        this.jobType = trace.getAttributes().get("jobType");
        this.jobId = trace.getAttributes().get("jobId");
        this.jobOutcome = trace.getAttributes().get("jobOutcome");
        this.jobAuditStatus = trace.getAttributes().get("jobAuditStatus");
        this.jobFailureType = trace.getAttributes().get("jobFailureType");
        this.scheduler = trace.getAttributes().get("scheduler");
        this.httpCallCount = trace.httpCalls().size();
        this.httpCallsTruncated = trace.isHttpCallsTruncated();
        this.captureMode = trace.captureMode();
        this.estimatedRetainedBytes = trace.retainedBytes();
        this.dropReasons = trace.dropReasons();
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

    public Long getDurationMs() {
        return durationMs;
    }

    public boolean isTruncated() {
        return truncated;
    }

    /** Stable id tying all HTTP requests caused by one user action together. */
    public String getActionId() {
        return actionId;
    }

    public String getActionName() {
        return actionName;
    }

    public String getSessionId() {
        return sessionId;
    }
    public String getParentSegmentId() { return parentSegmentId; }
    public String getSpanId() { return spanId; }
    public String getParentSpanId() { return parentSpanId; }
    public String getHttpMethod() { return httpMethod; }
    public String getPath() { return path; }
    public String getHttpStatus() { return httpStatus; }
    public String getSiteId() { return siteId; }
    public String getRouteTemplate() { return routeTemplate; }
    public String getFilterException() { return filterException; }
    public String getAsyncTimeout() { return asyncTimeout; }
    public String getServiceInstanceId() { return serviceInstanceId; }
    public String getJobType() { return jobType; }
    public String getJobId() { return jobId; }
    /** Business outcome; independent of the segment's ACTIVE/COMPLETED lifecycle status. */
    public String getJobOutcome() { return jobOutcome; }
    public String getJobAuditStatus() { return jobAuditStatus; }
    public String getJobFailureType() { return jobFailureType; }
    public String getScheduler() { return scheduler; }
    public int getHttpCallCount() { return httpCallCount; }
    public boolean isHttpCallsTruncated() { return httpCallsTruncated; }
    public String getCaptureMode() { return captureMode; }
    public long getEstimatedRetainedBytes() { return estimatedRetainedBytes; }
    public java.util.Map<String, Long> getDropReasons() { return dropReasons; }
}
