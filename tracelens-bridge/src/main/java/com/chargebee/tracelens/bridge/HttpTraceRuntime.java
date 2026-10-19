package com.chargebee.tracelens.bridge;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP boundary shared by javax and jakarta containers. No servlet API may be
 * linked from this bootstrap class: servlet types belong to application loaders.
 * Request bodies, queries, cookies and authorization headers are never inspected.
 */
public final class HttpTraceRuntime {
    public interface Listener {
        /** Opt-in; older adapters keep their original local-only behavior. */
        default boolean distributedTracingEnabled() { return false; }

        /** Register a trace, without activating thread-local context. Null declines capture. */
        TraceSnapshot begin(String label, String user, Map<String, String> attributes);

        /** Complete storage lifecycle only; may be invoked from an async callback thread. */
        void complete(String segmentId, Map<String, String> attributes);
    }

    private static final String STATE_KEY = HttpTraceRuntime.class.getName() + ".state";
    private static final AtomicLong CAPTURED = new AtomicLong();
    private static volatile Listener listener;
    private static final Class<?>[] NO_TYPES = new Class<?>[0];
    private static final Class<?>[] ONE_STRING = {String.class};
    private static final Class<?>[] TWO_STRINGS = {String.class, String.class};
    private static final Class<?>[] ATTRIBUTE_TYPES = {String.class, Object.class};
    private static final ClassValue<ConcurrentHashMap<String, Method>> METHODS =
            new ClassValue<ConcurrentHashMap<String, Method>>() {
                @Override protected ConcurrentHashMap<String, Method> computeValue(Class<?> type) {
                    return new ConcurrentHashMap<>();
                }
            };

    private HttpTraceRuntime() { }

    public static synchronized void registerListener(Listener value) { listener = value; }
    public static synchronized void unregisterListener(Listener expected) {
        if (listener == expected) listener = null;
    }
    public static boolean hasListener() { return listener != null; }
    public static long capturedRequests() { return CAPTURED.get(); }

    /** Enrich an existing request after authentication; never creates a new trace. */
    public static void identify(Object request, String identity, String siteId, String source) {
        try {
            identity = HttpIdentityRuntime.safe(identity);
            siteId = HttpIdentityRuntime.safe(siteId);
            source = HttpIdentityRuntime.safe(source);
            if (identity == null || siteId == null || source == null) return;
            Object saved = call(request, "getAttribute", ONE_STRING, STATE_KEY);
            if (!(saved instanceof State)) return;
            State state = (State) saved;
            synchronized (state) {
                if (state.completed) return;
                if (!state.explicitUser) state.user = identity;
                state.siteId = siteId;
                state.identitySource = source;
                Map<String, String> attributes = new LinkedHashMap<>(state.snapshot.getAttributes());
                attributes.put("siteId", siteId);
                attributes.put("identitySource", source);
                state.snapshot = new TraceSnapshot(state.snapshot.getTraceId(), state.snapshot.getSegmentId(),
                        state.snapshot.getLensId(), state.user, attributes);
            }
            refreshCurrent(state);
        } catch (Throwable ignored) {
            // Optional metadata must never break the authentication boundary.
        }
    }

    /** Refresh only a scope owned by this request; its original Dispatch closes normally. */
    private static void refreshCurrent(State state) {
        TraceSnapshot current = TraceContext.current();
        TraceSnapshot identity = state.snapshot;
        if (current != null && current.getSegmentId().equals(identity.getSegmentId())) {
            TraceContext.restore(identity);
        }
    }

    /** Servlet containers can use custom executors for AsyncContext.start. */
    public static Runnable wrapAsync(Object asyncContext, Runnable task) {
        if (task == null) return null;
        try {
            Object request = call(asyncContext, "getRequest", NO_TYPES);
            Object saved = call(request, "getAttribute", ONE_STRING, STATE_KEY);
            if (saved instanceof State) {
                State state = (State) saved;
                try (TraceScope ignored = TraceContext.activate(state.snapshot)) {
                    return TraceContext.wrap(task);
                }
            }
            return TraceContext.wrap(task);
        } catch (Throwable ignored) {
            return task;
        }
    }

    /** Return an opaque dispatch scope. Advice calls exit on the SAME thread. */
    public static Object enter(Object request, Object response) {
        Listener currentListener = listener;
        if (currentListener == null || request == null || response == null) return null;
        State state = null;
        try {
            Object saved = call(request, "getAttribute", ONE_STRING, STATE_KEY);
            if (saved instanceof State) {
                state = (State) saved;
            } else {
                // An app-owned adapter or another entry point already owns this scope.
                if (TraceContext.current() != null) return null;
                String method = text(call(request, "getMethod", NO_TYPES));
                String uri = text(call(request, "getRequestURI", NO_TYPES));
                if (method == null || uri == null) return null; // non-HTTP ServletRequest
                String context = text(call(request, "getContextPath", NO_TYPES));
                String path = context != null && !context.isEmpty() && uri.startsWith(context)
                        ? uri.substring(context.length()) : uri;
                boolean skipped = !included(path) || ignored(path);
                if (skipped && !currentListener.distributedTracingEnabled()) return null;
                String label = header(request, TraceSnapshot.LENS_ID_KEY);
                String user = header(request, TraceSnapshot.USER_KEY);
                boolean explicitUser = user != null;
                if (user == null) user = principal(request);
                Map<String, String> attributes = new LinkedHashMap<>();
                if (skipped) attributes.put("captureSkipped", "path-filter");
                if (currentListener.distributedTracingEnabled()) {
                    String incoming = text(call(request, "getHeader", ONE_STRING, "traceparent"));
                    W3CTraceContext parent = W3CTraceContext.parse(incoming,
                            combinedTracestate(request));
                    // Multiple traceparents are ambiguous, even when the first is valid.
                    Object headers = call(request, "getHeaders", ONE_STRING, "traceparent");
                    if (headers instanceof java.util.Enumeration) {
                        java.util.Enumeration<?> values = (java.util.Enumeration<?>) headers;
                        if (values.hasMoreElements()) values.nextElement();
                        if (values.hasMoreElements()) parent = null;
                    }
                    W3CTraceContext contextId = parent == null ? W3CTraceContext.fresh() : parent.child();
                    attributes.put("distributedTraceId", contextId.getTraceId());
                    attributes.put("spanId", contextId.getSpanId());
                    attributes.put("traceFlags", contextId.getFlags());
                    put(attributes, "tracestate", contextId.getTracestate());
                    if (parent != null) attributes.put("parentSpanId", parent.getSpanId());
                    attributes.put("traceContextSource", parent != null ? "remote" : incoming == null ? "root" : "invalid");
                }
                attributes.put("httpMethod", method);
                attributes.put("path", path);
                put(attributes, "contentType", safe(text(call(request, "getContentType", NO_TYPES))));
                Object length = call(request, "getContentLengthLong", NO_TYPES);
                if (!(length instanceof Number)) length = call(request, "getContentLength", NO_TYPES);
                if (length instanceof Number && ((Number) length).longValue() >= 0) {
                    attributes.put("requestSizeBytes", length.toString());
                }
                put(attributes, "actionId", header(request, TraceSnapshot.ACTION_ID_KEY));
                put(attributes, "actionName", header(request, TraceSnapshot.ACTION_NAME_KEY));
                put(attributes, "sessionId", header(request, TraceSnapshot.SESSION_ID_KEY));
                TraceSnapshot snapshot = currentListener.begin(label, user, attributes);
                if (snapshot == null) return null;
                state = new State(currentListener, snapshot, request, response, user, explicitUser);
                // setAttribute is mandatory: nesting and redispatch must find the same state.
                invoke(request, "setAttribute", ATTRIBUTE_TYPES, STATE_KEY, state);
                CAPTURED.incrementAndGet();
                call(response, "setHeader", TWO_STRINGS, "X-TraceLens-Id", snapshot.getSegmentId());
                if (currentListener.distributedTracingEnabled()) {
                    call(response, "setHeader", TWO_STRINGS, "X-TraceLens-Trace-Id", snapshot.getTraceId());
                }
                echo(response, "X-TraceLens-Action-Id", attributes.get("actionId"));
                echo(response, "X-TraceLens-Action-Name", attributes.get("actionName"));
                echo(response, "X-TraceLens-Session-Id", attributes.get("sessionId"));
            }
            synchronized (state) {
                if (state.completed) return null;
                state.entries++;
                String user = principal(request);
                if (state.user == null && user != null) state.user = user;
            }
            return new Dispatch(state, request, response, TraceContext.activate(state.snapshot));
        } catch (Throwable ignored) {
            // Reflection or a listener failure must never prevent servlet execution.
            if (state != null) state.finish(true);
            return null;
        }
    }

    public static void exit(Object token, Throwable error) {
        if (!(token instanceof Dispatch)) return;
        Dispatch dispatch = (Dispatch) token;
        State state = dispatch.state;
        try {
            synchronized (state) {
                String user = principal(dispatch.request);
                if (state.user == null && user != null) state.user = user;
                if (error != null) state.failureType = error.getClass().getName();
                state.response = dispatch.response;
                state.entries--;
                if (state.entries != 0) return;
                // An outer filter can recover from an inner servlet exception. Only
                // an exception escaping the outer boundary predicts container 500.
                if (error != null) state.escapingFailure = true;
            }
            if (Boolean.TRUE.equals(call(dispatch.request, "isAsyncStarted", NO_TYPES))) {
                state.attach(call(dispatch.request, "getAsyncContext", NO_TYPES));
            } else {
                synchronized (state) {
                    // ASYNC dispatch has isAsyncStarted=false until startAsync is called
                    // again. Its already-registered listener owns eventual completion.
                    if (state.asyncContext != null && !state.completionRequested) return;
                }
                state.finish(false);
            }
            if (state.completionRequested) state.finish(false);
        } catch (Throwable ignored) {
            state.finish(true);
        } finally {
            // Never leave an HTTP snapshot on a pooled servlet thread, including async.
            dispatch.scope.close();
            // Nested scopes can restore their pre-auth snapshot. Preserve enrichment
            // only when the restored outer scope belongs to this same request.
            refreshCurrent(state);
        }
    }

    private static String combinedTracestate(Object request) {
        Object headers = call(request, "getHeaders", ONE_STRING, "tracestate");
        if (!(headers instanceof java.util.Enumeration)) {
            return text(call(request, "getHeader", ONE_STRING, "tracestate"));
        }
        java.util.Enumeration<?> values = (java.util.Enumeration<?>) headers;
        StringBuilder state = new StringBuilder();
        int count = 0;
        while (values.hasMoreElements()) {
            String value = text(values.nextElement());
            if (++count > 32 || value == null || value.length() > 512 || state.length() + value.length() + 1 > 512) return null;
            if (state.length() > 0) state.append(',');
            state.append(value);
        }
        return state.length() == 0 ? null : state.toString();
    }

    private static final class Dispatch {
        final State state;
        final Object request;
        final Object response;
        final TraceScope scope;
        Dispatch(State state, Object request, Object response, TraceScope scope) {
            this.state = state;
            this.request = request;
            this.response = response;
            this.scope = scope;
        }
    }

    private static final class State implements InvocationHandler {
        final Listener owner;
        volatile TraceSnapshot snapshot;
        final Object request;
        volatile Object response;
        int entries;
        boolean completed;
        volatile boolean completionRequested;
        Object asyncContext;
        Object proxy;
        String user;
        final boolean explicitUser;
        String siteId;
        String identitySource;
        String failureType;
        boolean escapingFailure;
        boolean timedOut;

        State(Listener owner, TraceSnapshot snapshot, Object request, Object response, String user,
              boolean explicitUser) {
            this.owner = owner;
            this.snapshot = snapshot;
            this.request = request;
            this.response = response;
            this.user = user;
            this.explicitUser = explicitUser;
        }

        synchronized void attach(Object context) {
            if (completed || context == null) {
                finish(false);
                return;
            }
            if (asyncContext == context) return;
            try {
                ClassLoader loader = context.getClass().getClassLoader();
                Class<?> listenerType;
                try {
                    // Determine the namespace from the actual context, since both APIs
                    // can coexist in a process hosting several application loaders.
                    Class<?> jakarta = Class.forName("jakarta.servlet.AsyncContext", false, loader);
                    listenerType = Class.forName(jakarta.isInstance(context)
                            ? "jakarta.servlet.AsyncListener" : "javax.servlet.AsyncListener", false, loader);
                } catch (ClassNotFoundException legacy) {
                    listenerType = Class.forName("javax.servlet.AsyncListener", false, loader);
                }
                if (proxy == null) proxy = Proxy.newProxyInstance(listenerType.getClassLoader(),
                        new Class<?>[]{listenerType}, this);
                asyncContext = context;
                HttpTraceRuntime.invoke(context, "addListener", new Class<?>[]{listenerType}, proxy);
            } catch (Throwable alreadyCompletedOrUnsupported) {
                // Complete can race the servlet's return before addListener succeeds.
                completionRequested = true;
                finish(false);
            }
        }

        @Override public Object invoke(Object proxyObject, Method method, Object[] args) {
            String name = method.getName();
            if ("hashCode".equals(name)) return System.identityHashCode(proxyObject);
            if ("equals".equals(name)) return args != null && args.length == 1 && proxyObject == args[0];
            if ("toString".equals(name)) return "TraceLensAsyncListener";
            Object event = args == null || args.length == 0 ? null : args[0];
            try {
                if ("onStartAsync".equals(name)) {
                    synchronized (this) { asyncContext = null; }
                    attach(call(event, "getAsyncContext", NO_TYPES));
                } else if ("onComplete".equals(name)) {
                    Object supplied = call(event, "getSuppliedResponse", NO_TYPES);
                    if (supplied != null) response = supplied;
                    completionRequested = true;
                    finish(false);
                } else if ("onError".equals(name)) {
                    Object failure = call(event, "getThrowable", NO_TYPES);
                    synchronized (this) {
                        failureType = failure == null ? "asyncError" : failure.getClass().getName();
                        escapingFailure = true;
                    }
                } else if ("onTimeout".equals(name)) {
                    synchronized (this) { timedOut = true; }
                }
            } catch (Throwable ignored) {
                // A diagnostic callback is never allowed to throw into a container.
            }
            return null;
        }

        void finish(boolean force) {
            Map<String, String> attributes = new LinkedHashMap<>();
            synchronized (this) {
                if (completed || (!force && entries != 0)) return;
                completed = true;
                Object status = call(response, "getStatus", NO_TYPES);
                // Servlet 2.x lacks getStatus; omit unknown status rather than invent it.
                if (status instanceof Number) {
                    int code = ((Number) status).intValue();
                    attributes.put("httpStatus", String.valueOf(escapingFailure && code < 400 ? 500 : code));
                } else if (escapingFailure) attributes.put("httpStatus", "500");
                put(attributes, "responseContentType", safe(text(call(response, "getContentType", NO_TYPES))));
                put(attributes, "user", user);
                put(attributes, "siteId", siteId);
                put(attributes, "identitySource", identitySource);
                put(attributes, "filterException", failureType);
                if (timedOut) attributes.put("asyncTimeout", "true");
                Object route = call(request, "getAttribute", ONE_STRING,
                        "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern");
                if (route != null) put(attributes, "route", safe(route.toString()));
            }
            try { owner.complete(snapshot.getSegmentId(), attributes); }
            catch (Throwable ignored) { /* tracing must never break the request */ }
        }
    }

    private static boolean included(String path) {
        String prefixes = System.getProperty("tracelens.http.includePaths", "/");
        for (String prefix : prefixes.split(",")) {
            prefix = prefix.trim();
            while (prefix.length() > 1 && prefix.endsWith("/")) prefix = prefix.substring(0, prefix.length() - 1);
            if ("/".equals(prefix) || path.equals(prefix) || path.startsWith(prefix + "/")) return true;
        }
        return false;
    }

    private static boolean ignored(String path) {
        return path.equals("/tracelens") || path.startsWith("/tracelens/")
                || path.equals("/health") || path.startsWith("/health/")
                || path.equals("/healthz") || path.equals("/readyz")
                || path.equals("/actuator/health") || path.startsWith("/actuator/health/")
                || path.equals("/admin/status") || path.equals("/favicon.ico")
                || path.startsWith("/assets/") || path.startsWith("/static/");
    }

    private static void echo(Object response, String name, String value) {
        if (value != null) call(response, "setHeader", TWO_STRINGS, name, value);
    }
    private static void put(Map<String, String> attrs, String name, String value) {
        if (value != null) attrs.put(name, value);
    }
    private static String principal(Object request) {
        try {
            Object principal = call(request, "getUserPrincipal", NO_TYPES);
            String name = principal instanceof Principal ? ((Principal) principal).getName() : null;
            return safe(name == null ? text(call(request, "getRemoteUser", NO_TYPES)) : name);
        } catch (Throwable ignored) {
            return null;
        }
    }
    private static String header(Object request, String name) {
        return safe(text(call(request, "getHeader", ONE_STRING, name)));
    }
    private static String text(Object value) { return value instanceof String ? (String) value : null; }
    private static String safe(String value) {
        if (value == null) return null;
        value = value.trim();
        if (value.isEmpty() || value.length() > 256) return null;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < 32 || value.charAt(i) > 126) return null;
        }
        return value;
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args) {
        try { return invoke(target, name, types, args); }
        catch (Throwable ignored) { return null; }
    }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        if (target == null) return null;
        ConcurrentHashMap<String, Method> cache = METHODS.get(target.getClass());
        Method method = cache.get(name);
        if (method == null) {
            method = publicMethod(target.getClass(), name, types);
            if (method == null) throw new NoSuchMethodException(name);
            cache.putIfAbsent(name, method);
        }
        return method.invoke(target, args);
    }

    /** Use public API declarations even for non-public container/wrapper classes. */
    private static Method publicMethod(Class<?> type, String name, Class<?>[] params) {
        if (Modifier.isPublic(type.getModifiers())) {
            try {
                Method method = type.getMethod(name, params);
                if (Modifier.isPublic(method.getDeclaringClass().getModifiers())) return method;
            } catch (NoSuchMethodException ignored) { }
        }
        for (Class<?> api : type.getInterfaces()) {
            Method method = publicMethod(api, name, params);
            if (method != null) return method;
        }
        Class<?> parent = type.getSuperclass();
        return parent == null ? null : publicMethod(parent, name, params);
    }
}
