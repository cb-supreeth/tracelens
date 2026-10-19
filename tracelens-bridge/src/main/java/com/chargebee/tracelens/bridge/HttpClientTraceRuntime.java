package com.chargebee.tracelens.bridge;

import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Bootstrap-safe, bounded state for the JDK URLConnection client. Never reads bodies. */
public final class HttpClientTraceRuntime {
    public interface Listener { void record(String segmentId, Map<String, String> call); }

    private static final int MAX_CONNECTIONS = 4096;
    private static final Map<HttpURLConnection, Call> CALLS = new WeakHashMap<>();
    private static final ThreadLocal<Boolean> CALLBACK = new ThreadLocal<>();
    private static volatile Set<String> destinations = Collections.emptySet();
    private static volatile boolean enabled;
    private static volatile boolean propagationEnabled;
    private static volatile boolean redirectGuardReady;
    private static volatile Listener listener;
    private static volatile Method removeHeader;
    private static volatile Method findHeader;
    private static final ClassValue<Boolean> SUPPORTED = new ClassValue<Boolean>() {
        @Override protected Boolean computeValue(Class<?> type) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                if ("sun.net.www.protocol.http.HttpURLConnection".equals(current.getName())) return true;
            }
            return false;
        }
    };

    private HttpClientTraceRuntime() { }

    /** Compatibility entry point: propagation used to be the only capture switch. */
    public static void configure(boolean capture, List<String> allowedDestinations) {
        configure(capture, capture, allowedDestinations);
    }

    /** Observation never implies permission to send correlation headers. */
    public static void configure(boolean capture, boolean propagate, List<String> allowedDestinations) {
        Set<String> next = new HashSet<>();
        if (allowedDestinations != null) for (String value : allowedDestinations) {
            if (value != null) next.add(value.toLowerCase(Locale.ROOT));
        }
        synchronized (CALLS) {
            destinations = Collections.unmodifiableSet(next);
            enabled = capture;
            propagationEnabled = propagate;
            // Do not discard live connection states: their injected headers must still
            // be scrubbed if a redirect happens after recording has been disabled.
        }
    }

    public static void registerListener(Listener value) { listener = value; }
    public static void unregisterListener(Listener value) { if (listener == value) listener = null; }

    /** Called only after the JDK writeRequests guard has actually been woven. */
    public static void enableRedirectGuard() throws Exception {
        Class<?> headers = Class.forName("sun.net.www.MessageHeader", false, null);
        Method remove = headers.getMethod("remove", String.class);
        Method find = headers.getMethod("findValue", String.class);
        remove.setAccessible(true);
        find.setAccessible(true);
        Object probe = headers.getConstructor().newInstance();
        Method set = headers.getMethod("set", String.class, String.class);
        set.setAccessible(true);
        set.invoke(probe, "traceparent", "tracelens-guard-probe");
        if (!"tracelens-guard-probe".equals(find.invoke(probe, "traceparent"))) {
            throw new IllegalStateException("JDK header lookup preflight failed");
        }
        remove.invoke(probe, "traceparent");
        if (find.invoke(probe, "traceparent") != null) throw new IllegalStateException("JDK header removal preflight failed");
        removeHeader = remove;
        findHeader = find;
        redirectGuardReady = true;
    }

    public static boolean isRedirectGuardReady() { return redirectGuardReady; }
    public static void disableRedirectGuard() { redirectGuardReady = false; }

    /** First connect/output/input boundary injects before a body or headers are sent. */
    public static Object enter(Object value) {
        if (!(value instanceof HttpURLConnection) || !SUPPORTED.get(value.getClass())
                || Boolean.TRUE.equals(CALLBACK.get())) return null;
        HttpURLConnection connection = (HttpURLConnection) value;
        try {
            Call call;
            synchronized (CALLS) {
                call = CALLS.get(connection);
                if (call == null) {
                    if (!enabled || listener == null || CALLS.size() >= MAX_CONNECTIONS) return null;
                    TraceSnapshot snapshot = TraceContext.current();
                    if (snapshot == null || !http(connection.getURL())) return null;
                    W3CTraceContext parent = W3CTraceContext.fromSnapshot(snapshot);
                    call = new Call(snapshot, parent, connection);
                    CALLS.put(connection, call);
                    inject(connection, call);
                    emit(call);
                }
                if (call.finished) return null;
                call.depth++;
            }
            return call;
        } catch (Throwable ignored) { return null; }
    }

    /** responseCode is an existing field read by woven bytecode, never a network call. */
    public static void exit(Object token, String operation, int responseCode, Throwable failure) {
        if (!(token instanceof Call)) return;
        Call call = (Call) token;
        try {
            synchronized (CALLS) {
                if (call.depth > 0) call.depth--;
                if (call.finished || call.depth > 0) return;
                boolean response = "getResponseCode".equals(operation) || "getInputStream".equals(operation);
                if (failure == null && !response && !"disconnect".equals(operation)) return;
                call.finished = true;
                call.fields.put("endedAt", Long.toString(System.currentTimeMillis()));
                call.fields.put("durationNanos", Long.toString(Math.max(0, System.nanoTime() - call.startedNanos)));
                if (responseCode >= 100) call.fields.put("httpStatus", Integer.toString(responseCode));
                boolean failed = responseCode >= 400 || failure != null;
                call.fields.put("status", failed ? "ERROR" : "COMPLETED");
                // HTTP error streams may throw internally and then be recovered by
                // getResponseCode. Don't misrepresent that as a transport exception.
                if (failure != null && responseCode < 400) call.fields.put("errorType", failure.getClass().getName());
                emit(call);
            }
        } catch (Throwable ignored) { }
    }

    /**
     * Runs immediately before JDK header serialization, including redirects.
     * Removes only headers this observer injected; leaves other tracers untouched.
     * Both maps must be scrubbed: JDK authentication/retry paths can reuse userHeaders.
     */
    public static boolean beforeWrite(Object value, Object requests, Object userHeaders) {
        if (!(value instanceof HttpURLConnection)) return true;
        boolean ownsHeaders = false;
        try {
            HttpURLConnection connection = (HttpURLConnection) value;
            synchronized (CALLS) {
                Call call = CALLS.get(connection);
                if (call == null) return true;
                call.fields.put("httpMethod", connection.getRequestMethod());
                if (call.injectedParent == null) return true;
                ownsHeaders = true;
                if (enabled && propagationEnabled && allowed(connection.getURL())) return true;
                removeOwned(requests, call);
                removeOwned(userHeaders, call);
                call.fields.put("propagationStatus", "REDIRECT_BLOCKED");
                emit(call);
                return true;
            }
        } catch (Throwable ignored) {
            // Once a header was injected we cannot silently continue an unsafe
            // redirect if scrubbing fails. The advice aborts only that operation.
            return !ownsHeaders;
        }
    }

    private static void removeOwned(Object headers, Call call) throws Exception {
        if (headers == null) return;
        Object current = findHeader.invoke(headers, "traceparent");
        if (call.injectedParent.equals(current)) {
            removeHeader.invoke(headers, "traceparent");
            if (call.injectedState != null
                    && call.injectedState.equals(findHeader.invoke(headers, "tracestate"))) {
                removeHeader.invoke(headers, "tracestate");
            }
        }
    }

    private static void inject(HttpURLConnection connection, Call call) {
        try {
            if (!propagationEnabled) {
                call.fields.put("propagationStatus", "DISABLED");
                return;
            }
            if (!allowed(connection.getURL())) {
                call.fields.put("propagationStatus", "NOT_ALLOWLISTED");
                return;
            }
            if (call.context == null) {
                call.fields.put("propagationStatus", "NO_CONTEXT");
                return;
            }
            String existing = connection.getRequestProperty("traceparent");
            if (existing != null || connection.getRequestProperty("tracestate") != null) {
                call.fields.put("propagationStatus", "CONFLICT");
                return;
            }
            // The default JDK redirect policy is enabled. Never inject without a
            // verified serialization guard; changing that policy would alter the app.
            if (!redirectGuardReady) {
                call.fields.put("propagationStatus", "GUARD_UNAVAILABLE");
                return;
            }
            call.injectedParent = call.context.header();
            connection.setRequestProperty("traceparent", call.injectedParent);
            if (call.context.getTracestate() != null) {
                call.injectedState = call.context.getTracestate();
                connection.setRequestProperty("tracestate", call.injectedState);
            }
            call.fields.put("propagationStatus", "INJECTED");
        } catch (Throwable ignored) {
            call.fields.put("propagationStatus", "UNAVAILABLE");
        }
    }

    private static boolean allowed(URL url) {
        return http(url)
                && (destinations.contains(authority(url))
                || (url.getPort() == -1 || url.getPort() == url.getDefaultPort())
                && destinations.contains(url.getHost().toLowerCase(Locale.ROOT) + ":" + url.getDefaultPort()));
    }

    private static boolean http(URL url) {
        return url != null && ("http".equals(url.getProtocol()) || "https".equals(url.getProtocol()));
    }

    private static String authority(URL url) {
        String host = url.getHost().toLowerCase(Locale.ROOT);
        // Host-only entries mean the protocol's default port, not arbitrary ports.
        int port = url.getPort();
        return port == -1 || port == url.getDefaultPort() ? host : host + ":" + port;
    }

    private static void emit(Call call) {
        Listener sink = listener;
        if (sink == null || Boolean.TRUE.equals(CALLBACK.get())) return;
        CALLBACK.set(Boolean.TRUE);
        try { sink.record(call.segmentId, Collections.unmodifiableMap(new LinkedHashMap<>(call.fields))); }
        catch (Throwable ignored) { }
        finally { CALLBACK.remove(); }
    }

    private static final class Call {
        final String segmentId;
        final W3CTraceContext context;
        final long startedNanos = System.nanoTime();
        final Map<String, String> fields = new LinkedHashMap<>();
        String injectedParent;
        String injectedState;
        int depth;
        boolean finished;

        Call(TraceSnapshot snapshot, W3CTraceContext parent, HttpURLConnection connection) {
            segmentId = snapshot.getSegmentId();
            context = parent == null ? null : parent.child();
            // Local/legacy traces may have UUID trace IDs and no W3C parent. Keep
            // their identity and mint only a local call ID, never a propagated root.
            fields.put("traceId", snapshot.getTraceId());
            fields.put("spanId", context == null ? W3CTraceContext.fresh().getSpanId() : context.getSpanId());
            if (parent != null) fields.put("parentSpanId", parent.getSpanId());
            fields.put("httpMethod", connection.getRequestMethod());
            String path = connection.getURL().getPath();
            // Servlet URL rewriting can embed session identifiers in path params.
            if (path != null) path = path.replaceAll(";[^/]*", "");
            fields.put("path", path == null || path.isEmpty() ? "/" : path.substring(0, Math.min(2048, path.length())));
            fields.put("peer", authority(connection.getURL()));
            fields.put("startedAt", Long.toString(System.currentTimeMillis()));
            fields.put("status", "ACTIVE");
            fields.put("timingBoundary", "response-headers");
        }
    }
}
