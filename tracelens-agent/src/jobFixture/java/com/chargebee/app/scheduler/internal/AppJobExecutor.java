package com.chargebee.app.scheduler.internal;

import com.chargebee.app.models.SchJob;
import com.chargebee.app.models.SchJobRegion;
import com.chargebee.app.models.base.SchJobAuditBase.Status;
import com.chargebee.app.scheduler.IgnoreJobSignal;
import com.chargebee.app.scheduler.JobContext;
import com.chargebee.tracelens.bridge.TraceContext;
import com.chargebee.tracelens.bridge.TraceRuntime;

/** Models swallowed dispatch failures and exact real framework descriptors, without DB/services. */
public class AppJobExecutor {
    public String observedSegment;
    public String restoredSegment;
    public int auditCalls;
    public Throwable escaped;

    public void exec(SchJob job, Long timeMachineJobId) throws Exception {
        observedSegment = TraceContext.currentSegmentId();
        TraceRuntime.onLine("com.chargebee.app.billing_metrics.jobs.BillingMetricsComputeJob", 76);
        if (new com.chargebee.app.billing_metrics.jobs.BillingMetricsComputeJob().execute() != 12) {
            throw new AssertionError("job result changed");
        }
        if ("nested".equals(job.behavior)) {
            new AppJobExecutor().exec(new SchJob("unselected", "failed"), null);
            restoredSegment = TraceContext.currentSegmentId();
        }
        if ("nested-selected".equals(job.behavior)) {
            new AppJobExecutor().exec(new SchJob("compute_billing_metrics", "ignored"), null);
            restoredSegment = TraceContext.currentSegmentId();
        }
        if ("unknown".equals(job.behavior)) return;
        if ("escape".equals(job.behavior)) {
            escaped = new IllegalStateException("original failure");
            throw (IllegalStateException) escaped;
        }
        if ("escape-ignored".equals(job.behavior)) throw new IgnoreJobSignal("expected skip");
        try {
            if ("init-failed".equals(job.behavior)) throw new IllegalStateException("initialize failed");
            if ("precheck-failed".equals(job.behavior)) throw new IllegalArgumentException("precheck failed");
            if ("ignored".equals(job.behavior) || "reschedule-failed".equals(job.behavior)) throw new IgnoreJobSignal("disabled");
            if ("failed".equals(job.behavior) || "retry".equals(job.behavior)) throw new IllegalStateException("execute failed");
            if ("foreign-audit".equals(job.behavior)) audit(new SchJob("compute_billing_metrics", "failed"), null, null, Status.FAILED, new Exception());
            audit(job, null, null, Status.SUCCEEDED, null);
        } catch (IgnoreJobSignal skip) {
            audit(job, null, null, Status.IGNORED, skip);
            if ("reschedule-failed".equals(job.behavior)) audit(job, null, null, Status.RESCHEDULE_FAILED, new IllegalStateException());
        } catch (Exception failure) {
            audit(job, null, null, "retry".equals(job.behavior) ? Status.RESCHEDULED : Status.FAILED, failure);
        }
    }

    protected void audit(SchJob job, SchJobRegion region, JobContext context, Status status, Throwable failure) {
        auditCalls++;
    }

    // Deliberate lookalike overload: must not be woven.
    public void exec(Object notAJob, Long ignored) { observedSegment = TraceContext.currentSegmentId(); }
}
