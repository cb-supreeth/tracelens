package com.chargebee.tracelens.spring;

import com.chargebee.tracelens.bridge.TraceSnapshot;
import com.chargebee.tracelens.core.record.Tracer;
import com.chargebee.tracelens.core.store.TraceStore;
import com.chargebee.tracelens.core.trace.EntryChannel;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP entry point for traces. Plain jakarta.servlet.Filter (not a Spring
 * HandlerInterceptor) on purpose: the same class works on embedded Tomcat today and
 * on cb-tomcat/cb-framework after the migration.
 *
 * Label semantics (FlowLens parity): with requireLabels=true only requests carrying
 * BOTH tracelens-id and tracelens-user are traced; otherwise labels are auto-minted
 * (local dev convenience). The trace id is echoed back as the X-TraceLens-Id
 * response header.
 *
 * Async (Spring MVC Callable controllers — cb-ems uses them everywhere): the filter
 * does not complete the trace when the initial dispatch returns with async started;
 * an AsyncListener completes it when the async cycle finishes. The executor hop of
 * the Callable itself is covered by the agent's task wrapping.
 */
public final class TraceLensFilter implements Filter {

    /** Request attribute marking an in-flight trace (guards async redispatch). */
    static final String TRACE_ID_ATTRIBUTE = "tracelens.traceId";

    /** Response header echoing the trace id. */
    static final String TRACE_ID_HEADER = "X-TraceLens-Id";

    private final Tracer tracer;
    private final TraceStore store;
    private final boolean requireLabels;
    private final java.util.List<String> ignoredPaths;

    public TraceLensFilter(Tracer tracer, TraceStore store, boolean requireLabels,
                           java.util.List<String> ignoredPaths) {
        this.tracer = tracer;
        this.store = store;
        this.requireLabels = requireLabels;
        this.ignoredPaths = ignoredPaths;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest req) || !(response instanceof HttpServletResponse res)) {
            chain.doFilter(request, response);
            return;
        }

        String path = req.getRequestURI();
        if (!store.isRecordingEnabled() || isIgnored(path) || req.getAttribute(TRACE_ID_ATTRIBUTE) != null) {
            chain.doFilter(request, response);
            return;
        }

        String lensId = req.getHeader(TraceSnapshot.LENS_ID_KEY);
        String user = req.getHeader(TraceSnapshot.USER_KEY);
        if (requireLabels && (lensId == null || user == null)) {
            chain.doFilter(request, response);
            return;
        }
        if (lensId == null) {
            lensId = "auto:" + req.getMethod() + " " + path;
        }
        if (user == null) {
            user = "anonymous";
        }

        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("httpMethod", req.getMethod());
        attributes.put("path", path);
        if (req.getQueryString() != null) {
            attributes.put("query", req.getQueryString());
        }

        Optional<Tracer.ActiveTrace> maybeTrace = tracer.begin(lensId, user, EntryChannel.HTTP, attributes);
        if (maybeTrace.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        Tracer.ActiveTrace trace = maybeTrace.get();
        req.setAttribute(TRACE_ID_ATTRIBUTE, trace.segmentId());
        res.setHeader(TRACE_ID_HEADER, trace.segmentId());
        try {
            chain.doFilter(request, response);
        } catch (Throwable t) {
            trace.trace().putAttribute("filterException", t.getClass().getName());
            throw t;
        } finally {
            if (req.isAsyncStarted()) {
                // Callable-style MVC async: complete the trace when the async cycle ends.
                Tracer.ActiveTrace toComplete = trace;
                HttpServletResponse httpRes = res;
                req.getAsyncContext().addListener(new AsyncListener() {
                    @Override
                    public void onComplete(AsyncEvent event) {
                        toComplete.trace().putAttribute("httpStatus",
                                String.valueOf(httpRes.getStatus()));
                        toComplete.close();
                    }

                    @Override
                    public void onTimeout(AsyncEvent event) {
                        toComplete.trace().putAttribute("asyncTimeout", "true");
                        toComplete.close();
                    }

                    @Override
                    public void onError(AsyncEvent event) {
                        toComplete.trace().putAttribute("asyncError", "true");
                        toComplete.close();
                    }

                    @Override
                    public void onStartAsync(AsyncEvent event) {
                    }
                });
            } else {
                trace.trace().putAttribute("httpStatus", String.valueOf(res.getStatus()));
                trace.close();
            }
        }
    }

    private boolean isIgnored(String path) {
        if (path == null) {
            return false;
        }
        // the debug API must never trace itself (recursion + noise)
        if (path.startsWith("/tracelens/") || path.equals("/tracelens")) {
            return true;
        }
        for (String ignored : ignoredPaths) {
            if (path.equals(ignored) || path.startsWith(ignored + "/")) {
                return true;
            }
        }
        return false;
    }
}
