package com.chargebee.tracelens.bridge;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bootstrap-safe synchronous Apache 4 observer. Does not read headers other than correlation, bodies or credentials. */
public final class ApacheHttpClientTraceRuntime {
    private static final int MAX_OWNED_REQUESTS = 128;
    private static final ThreadLocal<Call> ACTIVE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> CALLBACK = new ThreadLocal<>();
    private static volatile boolean enabled;
    private static volatile boolean propagationEnabled;
    private static volatile Set<String> destinations = Collections.emptySet();
    private static volatile HttpClientTraceRuntime.Listener listener;

    private ApacheHttpClientTraceRuntime() { }

    public static void configure(boolean capture, boolean propagate, List<String> allowedDestinations) {
        Set<String> next = new HashSet<>();
        if (allowedDestinations != null) for (String value : allowedDestinations) {
            if (value != null) next.add(value.toLowerCase(Locale.ROOT));
        }
        destinations = Collections.unmodifiableSet(next);
        propagationEnabled = propagate;
        enabled = capture;
    }

    public static void registerListener(HttpClientTraceRuntime.Listener value) { listener = value; }
    public static void unregisterListener(HttpClientTraceRuntime.Listener value) {
        if (listener == value) listener = null;
    }

    /** doExecute is the common boundary beneath all synchronous public execute overloads. */
    public static Object enter(Object host, Object request) {
        if (Boolean.TRUE.equals(CALLBACK.get())) return null;
        Call active = ACTIVE.get();
        if (active != null) { active.depth++; return active; }
        if (!enabled || listener == null) return null;
        try {
            TraceSnapshot snapshot = TraceContext.current();
            if (snapshot == null) return null;
            Object line = invoke(request, "getRequestLine");
            URI uri = new URI((String) invoke(line, "getUri"));
            URI destination = destination(host, uri);
            if (destination == null) return null;
            Call call = new Call(snapshot, destination, (String) invoke(line, "getMethod"), uri);
            ACTIVE.set(call);
            emit(call);
            return call;
        } catch (Throwable ignored) { return null; }
    }

    public static void exit(Object token, Object response, Throwable failure) {
        if (!(token instanceof Call)) return;
        Call call = (Call) token;
        if (--call.depth > 0) return;
        ACTIVE.remove();
        try {
            int status = 0;
            if (response != null) {
                Object statusLine = invoke(response, "getStatusLine");
                Object value = invoke(statusLine, "getStatusCode");
                if (value instanceof Number) status = ((Number) value).intValue();
            }
            call.fields.put("endedAt", Long.toString(System.currentTimeMillis()));
            call.fields.put("durationNanos", Long.toString(Math.max(0, System.nanoTime() - call.startedNanos)));
            if (status >= 100) call.fields.put("httpStatus", Integer.toString(status));
            call.fields.put("status", failure != null || status >= 400 ? "ERROR" : "COMPLETED");
            if (failure != null) call.fields.put("errorType", failure.getClass().getName());
            emit(call);
        } catch (Throwable ignored) { }
        finally {
            // Only transport wrappers were changed, never the caller's original request.
            for (Object request : new ArrayList<>(call.owned.keySet())) {
                try { removeOwned(request, call); } catch (Throwable ignored) { }
            }
            call.owned.clear();
        }
    }

    /** Invoked per route, including redirects/retries, immediately before Apache's transport execution. */
    public static boolean beforeDispatch(Object route, Object request) {
        Call call = ACTIVE.get();
        if (call == null) return true;
        try {
            Object host = invoke(route, "getTargetHost");
            URI destination = destination(host, null);
            boolean allowed = destination != null && allowed(destination);
            if (!enabled || !propagationEnabled || !allowed) {
                boolean hadOwned = call.injectedParent != null;
                removeOwned(request, call);
                String reason = !enabled || !propagationEnabled ? "DISABLED" : "NOT_ALLOWLISTED";
                call.fields.put("propagationStatus", hadOwned ? "REDIRECT_BLOCKED" : reason);
                return true;
            }
            if (call.context == null) {
                call.fields.put("propagationStatus", "NO_CONTEXT");
                return true;
            }
            String existingParent = header(request, "traceparent");
            String existingState = header(request, "tracestate");
            if (call.injectedParent != null && call.injectedParent.equals(existingParent)) {
                if (call.owned.size() >= MAX_OWNED_REQUESTS && !call.owned.containsKey(request)) {
                    removeOwned(request, call);
                    call.fields.put("propagationStatus", "UNAVAILABLE");
                    return true;
                }
                call.owned.put(request, Boolean.TRUE);
                return true;
            }
            if (existingParent != null || existingState != null) {
                call.fields.put("propagationStatus", "CONFLICT");
                return true;
            }
            if (call.owned.size() >= MAX_OWNED_REQUESTS) {
                call.fields.put("propagationStatus", "UNAVAILABLE");
                return true;
            }
            call.injectedParent = call.context.header();
            call.injectedState = call.context.getTracestate();
            // Register ownership before mutation so exceptional exits can clean up.
            call.owned.put(request, Boolean.TRUE);
            invoke(request, "setHeader", new Class<?>[]{String.class, String.class}, "traceparent", call.injectedParent);
            if (call.injectedState != null) {
                invoke(request, "setHeader", new Class<?>[]{String.class, String.class}, "tracestate", call.injectedState);
            }
            call.fields.put("propagationStatus", "INJECTED");
            return true;
        } catch (Throwable ignored) {
            call.fields.put("propagationStatus", "UNAVAILABLE");
            return call.injectedParent == null;
        }
    }

    private static void removeOwned(Object request, Call call) throws Exception {
        if (call.injectedParent != null && call.injectedParent.equals(header(request, "traceparent"))) {
            invoke(request, "removeHeaders", new Class<?>[]{String.class}, "traceparent");
            if (call.injectedParent.equals(header(request, "traceparent"))) throw new IllegalStateException();
            if (call.injectedState != null && call.injectedState.equals(header(request, "tracestate"))) {
                invoke(request, "removeHeaders", new Class<?>[]{String.class}, "tracestate");
                if (call.injectedState.equals(header(request, "tracestate"))) throw new IllegalStateException();
            }
        }
    }

    private static String header(Object request, String name) throws Exception {
        Object header = invoke(request, "getFirstHeader", new Class<?>[]{String.class}, name);
        return header == null ? null : (String) invoke(header, "getValue");
    }

    private static URI destination(Object host, URI request) throws Exception {
        URI uri = host == null ? request : new URI((String) invoke(host, "toURI"));
        if (uri == null || uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))) return null;
        return uri;
    }

    private static boolean allowed(URI uri) {
        int defaultPort = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        return destinations.contains(authority(uri)) || (uri.getPort() == -1 || uri.getPort() == defaultPort)
                && destinations.contains(uri.getHost().toLowerCase(Locale.ROOT) + ":" + defaultPort);
    }

    private static String authority(URI uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        int defaultPort = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        return port == -1 || port == defaultPort ? host : host + ":" + port;
    }

    private static Object invoke(Object receiver, String name) throws Exception {
        return invoke(receiver, name, new Class<?>[0]);
    }

    private static Object invoke(Object receiver, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = publicMethod(receiver.getClass(), name, types);
        if (method == null) throw new NoSuchMethodException(name);
        return method.invoke(receiver, args);
    }

    private static Method publicMethod(Class<?> type, String name, Class<?>[] types) {
        if (Modifier.isPublic(type.getModifiers())) {
            try { return type.getMethod(name, types); } catch (NoSuchMethodException ignored) { }
        }
        for (Class<?> contract : type.getInterfaces()) {
            Method method = publicMethod(contract, name, types);
            if (method != null) return method;
        }
        Class<?> parent = type.getSuperclass();
        return parent == null ? null : publicMethod(parent, name, types);
    }

    private static void emit(Call call) {
        HttpClientTraceRuntime.Listener sink = listener;
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
        final Map<Object, Boolean> owned = new IdentityHashMap<>();
        int depth = 1;
        String injectedParent;
        String injectedState;

        Call(TraceSnapshot snapshot, URI destination, String method, URI uri) {
            segmentId = snapshot.getSegmentId();
            W3CTraceContext parent = W3CTraceContext.fromSnapshot(snapshot);
            context = parent == null ? null : parent.child();
            fields.put("traceId", snapshot.getTraceId());
            fields.put("spanId", context == null ? W3CTraceContext.fresh().getSpanId() : context.getSpanId());
            if (parent != null) fields.put("parentSpanId", parent.getSpanId());
            fields.put("httpMethod", method == null ? "UNKNOWN" : method.substring(0, Math.min(32, method.length())));
            String path = uri.getRawPath();
            if (path != null) path = path.replaceAll(";[^/]*", "");
            fields.put("path", path == null || path.isEmpty() ? "/" : path.substring(0, Math.min(2048, path.length())));
            fields.put("peer", authority(destination));
            fields.put("startedAt", Long.toString(System.currentTimeMillis()));
            fields.put("status", "ACTIVE");
            fields.put("timingBoundary", "response-headers");
            fields.put("propagationStatus", "GUARD_UNAVAILABLE");
        }
    }
}
