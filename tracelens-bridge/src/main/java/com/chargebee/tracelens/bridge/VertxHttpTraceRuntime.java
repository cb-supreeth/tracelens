package com.chargebee.tracelens.bridge;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional Vert.x 3 HTTP/1 adapter. Bootstrap-safe: no Vert.x class is linked here.
 * A Vert.x Context/event-loop is shared by requests, so it must NEVER hold identity.
 * Only registered one-shot/request callbacks carry scoped immutable snapshots.
 * Bodies, query strings, cookies, and authorization headers are not inspected.
 */
public final class VertxHttpTraceRuntime {
    private static final int MAX_IN_FLIGHT = 1024;
    private static final Map<Object, State> RESPONSES = new WeakHashMap<>();
    private static final AtomicLong CAPTURED = new AtomicLong();
    private static volatile Registration registration;
    private static final Class<?>[] NO_TYPES = new Class<?>[0];
    private static final Class<?>[] STRING = {String.class};
    private static final Class<?>[] STRINGS = {String.class, String.class};

    private VertxHttpTraceRuntime() { }

    public static synchronized void registerListener(HttpTraceRuntime.Listener listener) {
        registration = listener == null ? null : new Registration(listener);
    }

    public static synchronized void unregisterListener(HttpTraceRuntime.Listener expected) {
        if (registration == null || registration.listener != expected) return;
        registration = null;
        synchronized (RESPONSES) { RESPONSES.clear(); }
    }

    public static boolean hasListener() { return registration != null; }
    public static long capturedRequests() { return CAPTURED.get(); }
    public static int inFlightRequests() { synchronized (RESPONSES) { return RESPONSES.size(); } }

    /** Called only for HttpServerImpl.requestHandler(Handler), never arbitrary handlers. */
    public static Object wrapServerHandler(Object handler) {
        if (handler == null || wrapped(handler)) return handler;
        try { return proxy(handler, null, true); }
        catch (Throwable ignored) { return handler; }
    }

    /** Narrow instrumentation selects request callbacks, timers, futures and worker hops. */
    public static Object[] wrapCallbacks(Object[] arguments) {
        TraceSnapshot snapshot = TraceContext.current();
        if (snapshot == null || arguments == null) return arguments;
        Object[] result = arguments.clone();
        for (int i = 0; i < result.length; i++) {
            Object value = result[i];
            if (value == null || wrapped(value)) continue;
            try {
                Class<?> api = handlerType(value);
                if (api.isInstance(value)) result[i] = proxy(value, snapshot, false);
            } catch (Throwable ignored) { /* Not a Handler, or an unsupported loader. */ }
        }
        return result;
    }

    /** Response end submission, sendFile end, disconnect, or exception; idempotent. */
    public static void responseExit(Object response, String operation, Throwable failure) {
        try {
            State state;
            synchronized (RESPONSES) { state = RESPONSES.get(response); }
            if (state == null) return;
            boolean ended = Boolean.TRUE.equals(call(response, "ended", NO_TYPES));
            boolean closed = "handleClosed".equals(operation) || "close".equals(operation);
            if (failure != null || ended || closed) state.finish(response, failure, closed && !ended);
        } catch (Throwable ignored) { /* An observer must never break a response. */ }
    }

    public static void responseException(Object response, Throwable failure) {
        responseExit(response, "handleException", failure);
    }

    private static Object proxy(Object delegate, TraceSnapshot snapshot, boolean server) throws Exception {
        Class<?> api = handlerType(delegate);
        if (!api.isInstance(delegate)) return delegate;
        return Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api},
                new ScopedHandler(delegate, api.getMethod("handle", Object.class), snapshot, server));
    }

    private static Class<?> handlerType(Object handler) throws ClassNotFoundException {
        return Class.forName("io.vertx.core.Handler", false, handler.getClass().getClassLoader());
    }

    private static boolean wrapped(Object handler) {
        return Proxy.isProxyClass(handler.getClass())
                && Proxy.getInvocationHandler(handler) instanceof ScopedHandler;
    }

    private static final class ScopedHandler implements InvocationHandler {
        final Object delegate;
        final Method handle;
        final TraceSnapshot snapshot;
        final boolean server;
        ScopedHandler(Object delegate, Method handle, TraceSnapshot snapshot, boolean server) {
            this.delegate = delegate;
            this.handle = handle;
            this.snapshot = snapshot;
            this.server = server;
        }

        @Override public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
            if ("equals".equals(method.getName())) return arguments != null && arguments[0] == proxy;
            if ("toString".equals(method.getName())) return "TraceLensVertxHandler";
            if (!"handle".equals(method.getName())) return method.invoke(delegate, arguments);
            // A separate network request is a separate root unless actual incoming W3C
            // headers link it. Never inherit context from a shared in-JVM proxy thread.
            try (TraceScope ignored = TraceContext.activate(server ? null : snapshot)) {
                State state = server && arguments != null && arguments.length == 1
                        ? begin(arguments[0]) : null;
                try (TraceScope request = server ? TraceContext.activate(state == null ? null : state.snapshot) : null) {
                    try { return handle.invoke(delegate, arguments); }
                    catch (InvocationTargetException original) {
                        Throwable failure = original.getCause();
                        if (state != null) state.finish(state.response.get(), failure, false);
                        throw failure;
                    }
                }
            }
        }
    }

    private static State begin(Object request) {
        Registration current = registration;
        if (current == null || request == null) return null;
        State state = null;
        try {
            String path = path(text(call(request, "path", NO_TYPES)));
            Object methodValue = call(request, "method", NO_TYPES);
            String method = methodValue instanceof Enum<?> ? ((Enum<?>) methodValue).name() : null;
            Object response = call(request, "response", NO_TYPES);
            if (path == null || method == null || response == null) return null;
            boolean skipped = !current.includes(path) || ignored(path);
            if (skipped && !current.listener.distributedTracingEnabled()) return null;
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("httpMethod", method);
            attributes.put("path", path);
            attributes.put("httpAdapter", "vertx3-http1");
            attributes.put("timingBoundary", "response-end-submitted");
            attributes.put("asyncCapture", "registered-vertx-callbacks");
            Object address = call(request, "localAddress", NO_TYPES);
            Object port = call(address, "port", NO_TYPES);
            if (port instanceof Number) attributes.put("httpServerPort", port.toString());
            if (skipped) attributes.put("captureSkipped", "path-filter");
            if (current.listener.distributedTracingEnabled()) {
                String incoming = header(request, "traceparent");
                W3CTraceContext parent = W3CTraceContext.parse(incoming, combinedHeader(request, "tracestate", 512));
                Object headers = call(request, "headers", NO_TYPES);
                Object parents = call(headers, "getAll", STRING, "traceparent");
                if (parents instanceof List<?> && ((List<?>) parents).size() != 1) parent = null;
                W3CTraceContext identity = parent == null ? W3CTraceContext.fresh() : parent.child();
                attributes.put("distributedTraceId", identity.getTraceId());
                attributes.put("spanId", identity.getSpanId());
                attributes.put("traceFlags", identity.getFlags());
                put(attributes, "tracestate", identity.getTracestate());
                if (parent != null) attributes.put("parentSpanId", parent.getSpanId());
                attributes.put("traceContextSource", parent != null ? "remote" : incoming == null ? "root" : "invalid");
            }
            put(attributes, "contentType", header(request, "content-type"));
            put(attributes, "actionId", header(request, TraceSnapshot.ACTION_ID_KEY));
            put(attributes, "actionName", header(request, TraceSnapshot.ACTION_NAME_KEY));
            put(attributes, "sessionId", header(request, TraceSnapshot.SESSION_ID_KEY));
            synchronized (RESPONSES) {
                // Weak keys cannot retain abandoned requests; the hard cap also bounds
                // traces held by connections that never complete or disconnect.
                if (RESPONSES.size() >= MAX_IN_FLIGHT) return null;
                State existing = RESPONSES.get(response);
                if (existing != null) return existing;
                TraceSnapshot snapshot = current.listener.begin(header(request, TraceSnapshot.LENS_ID_KEY),
                        header(request, TraceSnapshot.USER_KEY), attributes);
                if (snapshot == null) return null;
                state = new State(current.listener, snapshot, response);
                RESPONSES.put(response, state);
            }
            CAPTURED.incrementAndGet();
            call(response, "putHeader", STRINGS, "X-TraceLens-Id", state.snapshot.getSegmentId());
            if (current.listener.distributedTracingEnabled()) {
                call(response, "putHeader", STRINGS, "X-TraceLens-Trace-Id", state.snapshot.getTraceId());
            }
            return state;
        } catch (Throwable ignored) {
            if (state != null) state.finish(state.response.get(), null, true);
            return null;
        }
    }

    private static final class State {
        final HttpTraceRuntime.Listener owner;
        final TraceSnapshot snapshot;
        final WeakReference<Object> response;
        boolean completed;
        State(HttpTraceRuntime.Listener owner, TraceSnapshot snapshot, Object response) {
            this.owner = owner;
            this.snapshot = snapshot;
            this.response = new WeakReference<>(response);
        }

        void finish(Object response, Throwable failure, boolean aborted) {
            synchronized (this) {
                if (completed) return;
                completed = true;
            }
            synchronized (RESPONSES) { if (response != null) RESPONSES.remove(response); }
            Map<String, String> attributes = new LinkedHashMap<>();
            // Do not turn an uncommitted/default 200 into an invented successful response.
            if (Boolean.TRUE.equals(call(response, "ended", NO_TYPES))
                    || Boolean.TRUE.equals(call(response, "headWritten", NO_TYPES))) {
                Object status = call(response, "getStatusCode", NO_TYPES);
                if (status instanceof Number) attributes.put("httpStatus", status.toString());
            }
            Object bytes = call(response, "bytesWritten", NO_TYPES);
            if (bytes instanceof Number) attributes.put("responseSizeBytes", bytes.toString());
            Object headers = call(response, "headers", NO_TYPES);
            put(attributes, "responseContentType", safe(text(call(headers, "get", STRING, "content-type"))));
            if (failure != null) attributes.put("filterException", failure.getClass().getName());
            if (aborted) attributes.put("httpAborted", "true");
            try { owner.complete(snapshot.getSegmentId(), attributes); }
            catch (Throwable ignored) { /* No callback may affect the application. */ }
        }
    }

    private static final class Registration {
        final HttpTraceRuntime.Listener listener;
        final List<String> prefixes = new ArrayList<>();
        Registration(HttpTraceRuntime.Listener listener) {
            this.listener = listener;
            List<String> values = listener.httpIncludePaths();
            if (values != null) for (String value : values) {
                if (value == null || !value.startsWith("/")) continue;
                while (value.length() > 1 && value.endsWith("/")) value = value.substring(0, value.length() - 1);
                prefixes.add(value);
            }
        }
        boolean includes(String path) {
            for (String prefix : prefixes) {
                if (prefix.equals("/") || path.equals(prefix) || path.startsWith(prefix + "/")) return true;
            }
            return false;
        }
    }

    private static boolean ignored(String path) {
        return path.equals("/tracelens") || path.startsWith("/tracelens/")
                || path.equals("/health") || path.startsWith("/health/")
                || path.equals("/healthz") || path.endsWith("/healthz") || path.equals("/readyz")
                || path.equals("/actuator/health") || path.startsWith("/actuator/health/")
                || path.equals("/admin/status") || path.equals("/favicon.ico")
                || path.startsWith("/assets/") || path.startsWith("/static/");
    }

    private static String path(String value) {
        if (value == null || !value.startsWith("/")) return null;
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        value = value.replaceAll(";[^/]*", "");
        return value.substring(0, Math.min(value.length(), 2048));
    }

    private static String combinedHeader(Object request, String name, int max) {
        Object headers = call(request, "headers", NO_TYPES);
        Object values = call(headers, "getAll", STRING, name);
        if (!(values instanceof List<?>)) return null;
        StringBuilder combined = new StringBuilder();
        int count = 0;
        for (Object value : (List<?>) values) {
            if (!(value instanceof String) || ++count > 32) return null;
            if (combined.length() > 0) combined.append(',');
            combined.append(value);
            if (combined.length() > max) return null;
        }
        return combined.length() == 0 ? null : combined.toString();
    }

    private static String header(Object request, String name) { return safe(text(call(request, "getHeader", STRING, name))); }
    private static String text(Object value) { return value instanceof String ? (String) value : null; }
    private static void put(Map<String, String> fields, String key, String value) { if (value != null) fields.put(key, value); }
    private static String safe(String value) {
        if (value == null) return null;
        value = value.trim();
        if (value.isEmpty() || value.length() > 256) return null;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 32 || value.charAt(i) > 126) return null;
        return value;
    }

    private static Object call(Object target, String name, Class<?>[] parameters, Object... values) {
        if (target == null) return null;
        try {
            Method method = publicMethod(target.getClass(), name, parameters);
            return method == null ? null : method.invoke(target, values);
        } catch (Throwable ignored) { return null; }
    }

    private static Method publicMethod(Class<?> type, String name, Class<?>[] parameters) {
        if (Modifier.isPublic(type.getModifiers())) {
            try {
                Method method = type.getMethod(name, parameters);
                if (Modifier.isPublic(method.getDeclaringClass().getModifiers())) return method;
            } catch (NoSuchMethodException ignored) { }
        }
        for (Class<?> api : type.getInterfaces()) {
            Method method = publicMethod(api, name, parameters);
            if (method != null) return method;
        }
        return type.getSuperclass() == null ? null : publicMethod(type.getSuperclass(), name, parameters);
    }
}
