package com.chargebee.tracelens.spring;

import com.chargebee.tracelens.core.trace.Trace;

import java.lang.reflect.Method;

/**
 * Datadog correlation (Phase 6): when the dd-agent is attached, its OpenTracing API
 * (dd-trace-api, bootstrap-visible) lets us tag the active server span with
 * tracelens.id / tracelens.user / tracelens.trace_id and read back the Datadog
 * trace_id into our trace metadata — so a flow composes with the Datadog MCP in
 * Cursor and the Datadog UI. Fully reflective and a no-op without the agent.
 */
final class DatadogCorrelation {

    private static final Class<?> GLOBAL_TRACER = tryLoad("io.opentracing.util.GlobalTracer");
    private static final Class<?> SPAN = tryLoad("io.opentracing.Span");

    private DatadogCorrelation() {
    }

    static void correlate(String lensId, String user, String traceId, Trace trace) {
        if (GLOBAL_TRACER == null || SPAN == null) {
            return;
        }
        try {
            Object tracer = GLOBAL_TRACER.getMethod("get").invoke(null);
            Object span = tracer.getClass().getMethod("activeSpan").invoke(tracer);
            if (span == null) {
                return;
            }
            Method setTag = SPAN.getMethod("setTag", String.class, String.class);
            setTag.invoke(span, "tracelens.id", lensId);
            setTag.invoke(span, "tracelens.user", user);
            setTag.invoke(span, "tracelens.trace_id", traceId);
            Object spanContext = SPAN.getMethod("context").invoke(span);
            Object ddTraceId = spanContext.getClass().getMethod("toTraceId").invoke(spanContext);
            if (ddTraceId != null) {
                trace.putAttribute("datadogTraceId", String.valueOf(ddTraceId));
            }
        } catch (Throwable ignored) {
            // correlation is best-effort; never break the request
        }
    }

    private static Class<?> tryLoad(String fqcn) {
        try {
            return Class.forName(fqcn);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
