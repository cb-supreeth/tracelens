package com.chargebee.tracelens.servlet;

import com.chargebee.tracelens.bridge.TraceContext;
import com.chargebee.tracelens.bridge.TraceRuntime;
import com.chargebee.tracelens.bridge.TraceScope;
import com.chargebee.tracelens.bridge.TraceSnapshot;
import com.chargebee.tracelens.core.config.TraceLensConfig;
import com.chargebee.tracelens.core.config.TraceLensConfigLoader;
import com.chargebee.tracelens.core.export.TraceExporter;
import com.chargebee.tracelens.core.record.TraceLifecycle;
import com.chargebee.tracelens.core.record.TraceRecording;
import com.chargebee.tracelens.core.record.Tracer;
import com.chargebee.tracelens.core.store.TraceStore;
import com.chargebee.tracelens.core.trace.EntryChannel;
import com.chargebee.tracelens.core.trace.Trace;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.security.Principal;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Opt-in Servlet 3.0 adapter. Register BEFORE application router filters in web.xml.
 * One enabled web application per JVM: the agent's bootstrap sink is process-wide.
 * Authentication belongs to the host app; this adapter has no Chargebee dependencies.
 */
public final class TraceLensFilter implements Filter {
    private static final String STATE_KEY = TraceLensFilter.class.getName() + ".state";
    private TraceLensConfig config;
    private TraceStore store;
    private TraceLensDebugApi debug;
    private String[] includePaths;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        if (Boolean.getBoolean("tracelens.standalone") || !Boolean.getBoolean("tracelens.enabled")) {
            return;
        }
        try {
            if (TraceRuntime.hasSink()) {
                throw new IllegalStateException("TraceLens supports one enabled webapp per JVM");
            }
            config = new TraceLensConfigLoader().load();
            store = new TraceStore(config);
            debug = new TraceLensDebugApi(store, new TraceExporter(config, true), config);
            includePaths = System.getProperty("tracelens.http.includePaths", "/").split(",");
            TraceRecording.install(store);
            TraceRuntime.registerLifecycleListener(new TraceLifecycle(new Tracer(store)));
            store.startSweeper();
            filterConfig.getServletContext().log("TraceLens javax adapter enabled for "
                    + config.getApplicationId());
        } catch (RuntimeException failure) {
            // Failed init is not guaranteed a destroy callback from the container.
            // Release our bootstrap references so a reload can try again.
            destroy();
            throw new ServletException("Cannot initialize TraceLens javax adapter", failure);
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (store == null || !(request instanceof HttpServletRequest)
                || !(response instanceof HttpServletResponse)) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        String path = req.getRequestURI().substring(req.getContextPath().length());
        if (path.equals("/tracelens") || path.startsWith("/tracelens/")) {
            // The debug API bypasses the application router, so guard it here. Never
            // trust forwarded address headers. No cross-origin browser access.
            if (!isLocalDebugRequest(req)) {
                res.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
            String suffix = path.substring("/tracelens".length());
            try {
                suffix = URLDecoder.decode(suffix.replace("+", "%2B"), "UTF-8");
            } catch (IllegalArgumentException invalid) {
                res.sendError(HttpServletResponse.SC_BAD_REQUEST);
                return;
            }
            debug.handle(suffix, req, res);
            return;
        }

        RequestTrace state = (RequestTrace) req.getAttribute(STATE_KEY);
        if (state != null) {
            // ASYNC / ERROR redispatch uses the same segment, never a second trace.
            dispatch(req, res, chain, state);
            return;
        }
        if (!store.isRecordingEnabled() || !included(path) || ignored(path)) {
            chain.doFilter(request, response);
            return;
        }
        String label = header(req, TraceSnapshot.LENS_ID_KEY);
        String user = header(req, TraceSnapshot.USER_KEY);
        if (config.isRequireLabels() && (label == null || user == null)) {
            chain.doFilter(request, response);
            return;
        }
        if (label == null) label = "auto:" + req.getMethod() + " " + path;
        if (user == null) user = principal(req);
        if (user == null) user = "anonymous";

        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("httpMethod", req.getMethod());
        attrs.put("path", path);
        // Intentionally never read the body, cookies, auth headers or raw query values.
        put(attrs, "contentType", safe(req.getContentType()));
        if (req.getContentLength() >= 0) attrs.put("requestSizeBytes", String.valueOf(req.getContentLength()));
        put(attrs, "actionId", header(req, TraceSnapshot.ACTION_ID_KEY));
        put(attrs, "actionName", header(req, TraceSnapshot.ACTION_NAME_KEY));
        put(attrs, "sessionId", header(req, TraceSnapshot.SESSION_ID_KEY));

        Trace trace = new Trace(label, user, EntryChannel.HTTP, System.currentTimeMillis(), System.nanoTime());
        attrs.forEach(trace::putAttribute);
        if (!store.register(trace)) {
            chain.doFilter(request, response);
            return;
        }
        state = new RequestTrace(trace, new TraceSnapshot(trace.getTraceId(), trace.getSegmentId(),
                label, user, attrs));
        req.setAttribute(STATE_KEY, state);
        res.setHeader("X-TraceLens-Id", trace.getSegmentId());
        if (attrs.containsKey("actionId")) res.setHeader("X-TraceLens-Action-Id", attrs.get("actionId"));
        if (attrs.containsKey("actionName")) res.setHeader("X-TraceLens-Action-Name", attrs.get("actionName"));
        dispatch(req, res, chain, state);
    }

    private void dispatch(HttpServletRequest req, HttpServletResponse res, FilterChain chain,
                          RequestTrace state) throws IOException, ServletException {
        // Restore the servlet thread immediately, even when the async response is
        // still in flight. Completion callbacks must not close another thread's scope.
        try (TraceScope ignored = TraceContext.activate(state.snapshot)) {
            boolean failed = false;
            try {
                chain.doFilter(req, res);
            } catch (IOException | ServletException | RuntimeException | Error failure) {
                failed = true;
                state.trace.putAttribute("filterException", failure.getClass().getName());
                throw failure;
            } finally {
                if ("anonymous".equals(state.trace.getUser())) {
                    state.trace.setUser(principal(req));
                }
                if (req.isAsyncStarted()) {
                    if (!state.listening) {
                        state.listening = true;
                        try {
                            req.getAsyncContext().addListener(state.listener(res));
                        } catch (IllegalStateException alreadyComplete) {
                            state.finish(res);
                        }
                    }
                } else {
                    state.finish(res, failed);
                }
            }
        }
    }

    private boolean included(String path) {
        for (String prefix : includePaths) {
            prefix = prefix.trim();
            if (prefix.equals("/") || path.equals(prefix) || path.startsWith(prefix + "/")) return true;
        }
        return false;
    }

    private static boolean ignored(String path) {
        return path.equals("/admin/status") || path.equals("/favicon.ico")
                || path.startsWith("/assets/") || path.startsWith("/static/");
    }

    private static boolean isLocalDebugRequest(HttpServletRequest req) {
        String remote = req.getRemoteAddr();
        String host = req.getServerName();
        boolean loopback = "127.0.0.1".equals(remote) || "::1".equals(remote)
                || "0:0:0:0:0:0:0:1".equals(remote);
        boolean localHost = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "[::1]".equals(host);
        return loopback && localHost && req.getHeader("Origin") == null
                && req.getHeader("Forwarded") == null && req.getHeader("X-Forwarded-For") == null;
    }

    private static String principal(HttpServletRequest req) {
        Principal principal = req.getUserPrincipal();
        return safe(principal == null ? req.getRemoteUser() : principal.getName());
    }

    private static String header(HttpServletRequest req, String name) { return safe(req.getHeader(name)); }
    private static String safe(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        value = value.trim();
        // Printable ASCII headers only; no multiline response header injection.
        if (!value.matches("[\\x20-\\x7E]+") || value.length() > 256) return null;
        return value;
    }
    private static void put(Map<String, String> attrs, String key, String value) {
        if (value != null) attrs.put(key, value);
    }

    @Override
    public void destroy() {
        if (store != null) {
            TraceRuntime.unregisterLifecycleListener();
            TraceRecording.uninstall();
            store.close();
            store = null;
        }
    }

    private static final class RequestTrace {
        final Trace trace;
        final TraceSnapshot snapshot;
        boolean listening;
        RequestTrace(Trace trace, TraceSnapshot snapshot) { this.trace = trace; this.snapshot = snapshot; }
        void finish(HttpServletResponse res) {
            finish(res, false);
        }
        void finish(HttpServletResponse res, boolean failed) {
            // Tomcat sets 500 after an escaping exception leaves this filter.
            int status = failed && res.getStatus() < 400 ? 500 : res.getStatus();
            trace.putAttribute("httpStatus", String.valueOf(status));
            trace.complete(System.currentTimeMillis());
        }
        AsyncListener listener(final HttpServletResponse res) {
            return new AsyncListener() {
                public void onComplete(AsyncEvent event) { finish(res); }
                public void onTimeout(AsyncEvent event) { trace.putAttribute("asyncTimeout", "true"); }
                public void onError(AsyncEvent event) { trace.putAttribute("asyncError", "true"); }
                public void onStartAsync(AsyncEvent event) { event.getAsyncContext().addListener(this); }
            };
        }
    }
}
