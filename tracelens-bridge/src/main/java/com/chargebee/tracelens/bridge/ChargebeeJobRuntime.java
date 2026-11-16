package com.chargebee.tracelens.bridge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Optional Chargebee scheduler adapter. Only explicit job-type names are recorded.
 * No application dependency, job-body reads, database writes, or request correlation.
 * One same-thread dispatcher invocation owns one independent root and its audit evidence.
 */
public final class ChargebeeJobRuntime {
    private static volatile Set<String> jobTypes = Collections.emptySet();
    private static final ThreadLocal<Run> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> CALLBACK = new ThreadLocal<>();

    private ChargebeeJobRuntime() { }

    public static void configure(List<String> types) {
        Set<String> accepted = new LinkedHashSet<>();
        if (types != null && types.size() <= 32) {
            for (String type : types) {
                if (type == null || !type.matches("[A-Za-z0-9_.-]{1,128}") || !accepted.add(type)) {
                    accepted.clear();
                    break;
                }
            }
        }
        jobTypes = Collections.unmodifiableSet(accepted);
    }

    public static Object enter(Object job) {
        if (jobTypes.isEmpty() || Boolean.TRUE.equals(CALLBACK.get())) return null;
        // Isolate even excluded/rejected jobs so a nested dispatcher cannot append
        // its methods or audit status to a selected job or an ambient HTTP request.
        Run run = new Run(job, CURRENT.get(), TraceContext.activate(null));
        CURRENT.set(run);
        CALLBACK.set(true);
        try {
            Object typeValue = read(read(job, "jobType_e"), "getName");
            if (!(typeValue instanceof String) || !jobTypes.contains(typeValue)) return run;
            String type = (String) typeValue;
            run.fields.put("scheduler", "chargebee");
            run.fields.put("jobType", type);
            run.fields.put("jobOutcome", "RUNNING");
            run.fields.put("jobCaptureScope", "dispatcher");
            run.fields.put("jobCorrelation", "independent-root");
            putId(run.fields, "jobId", read(job, "id"));
            putId(run.fields, "siteId", read(job, "siteId"));
            run.segmentId = TraceRuntime.beginLifecycle(null, type, "scheduler", "SCHEDULER", run.fields);
            if (run.segmentId == null) TraceContext.clear();
        } catch (Throwable ignored) {
            // Missing/changed framework accessors degrade to no capture, never a failed job.
        } finally {
            CALLBACK.remove();
        }
        return run;
    }

    /** Observe the status passed to audit; this does not claim the audit DB write succeeded. */
    public static void audit(Object job, Object status, Throwable failure) {
        Run run = CURRENT.get();
        if (run == null || run.segmentId == null || run.job != job || run.closed
                || !(status instanceof Enum) || Boolean.TRUE.equals(CALLBACK.get())) return;
        CALLBACK.set(true);
        try {
            String name = ((Enum<?>) status).name();
            String outcome;
            switch (name) {
                case "SUCCEEDED": outcome = "SUCCEEDED"; break;
                case "IGNORED": outcome = "SKIPPED"; break;
                case "FAILED":
                case "RESCHEDULED":
                case "RESCHEDULE_FAILED": outcome = "FAILED"; break;
                default: outcome = "UNKNOWN";
            }
            // A later bookkeeping report must not hide a failed attempt.
            if (!"FAILED".equals(run.fields.get("jobOutcome"))) {
                run.fields.put("jobAuditStatus", name);
                run.fields.put("jobOutcome", outcome);
                if (failure != null) run.fields.put("jobFailureType", failure.getClass().getName());
            }
        } catch (Throwable ignored) {
        } finally {
            CALLBACK.remove();
        }
    }

    public static void exit(Object token, Throwable failure) {
        if (!(token instanceof Run)) return;
        Run run = (Run) token;
        if (run.closed || CURRENT.get() != run) return;
        run.closed = true;
        try {
            if (run.segmentId != null) {
                if (failure != null) {
                    run.fields.put("jobOutcome", ignoredJob(failure) && !"FAILED".equals(run.fields.get("jobOutcome"))
                            ? "SKIPPED" : "FAILED");
                    run.fields.put("jobFailureType", failure.getClass().getName());
                } else if ("RUNNING".equals(run.fields.get("jobOutcome"))) {
                    // The dispatcher catches business failures. Normal return is not success.
                    run.fields.put("jobOutcome", "UNKNOWN");
                }
                TraceRuntime.endLifecycle(run.segmentId, run.fields);
            }
        } catch (Throwable ignored) {
        } finally {
            if (run.previous == null) CURRENT.remove(); else CURRENT.set(run.previous);
            // Also restore context if a missing/broken lifecycle listener failed to do so.
            run.isolation.close();
        }
    }

    private static boolean ignoredJob(Throwable failure) {
        for (Class<?> type = failure.getClass(); type != null; type = type.getSuperclass()) {
            if ("com.chargebee.app.scheduler.IgnoreJobSignal".equals(type.getName())) return true;
        }
        return false;
    }

    private static Object read(Object value, String accessor) {
        if (value == null) return null;
        try { return value.getClass().getMethod(accessor).invoke(value); }
        catch (Throwable ignored) { return null; }
    }

    private static void putId(Map<String, String> fields, String key, Object value) {
        // Do not stringify arbitrary application objects or context properties.
        if (value instanceof Long || value instanceof Integer) fields.put(key, String.valueOf(value));
    }

    private static final class Run {
        final Object job;
        final Run previous;
        final TraceScope isolation;
        final Map<String, String> fields = new LinkedHashMap<>();
        String segmentId;
        boolean closed;
        Run(Object job, Run previous, TraceScope isolation) {
            this.job = job;
            this.previous = previous;
            this.isolation = isolation;
        }
    }
}
