package com.chargebee.tracelens.bridge;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional identity enrichment after a configured application authentication seam.
 * Contains only immutable configuration and ClassValue method caches, never app
 * models, credentials, request bodies, framework types or static context lookups.
 */
public final class HttpIdentityRuntime {
    private static volatile Map<String, Hook> hooks = Collections.emptyMap();
    private static final ClassValue<ConcurrentHashMap<String, Method>> GETTERS =
            new ClassValue<ConcurrentHashMap<String, Method>>() {
                @Override protected ConcurrentHashMap<String, Method> computeValue(Class<?> type) {
                    return new ConcurrentHashMap<>();
                }
            };

    private HttpIdentityRuntime() { }

    /** Bootstrap-safe copy of configuration; all paths are public zero-argument getters. */
    public static final class Hook {
        final String id;
        final int targetArgument;
        final String[] requestPath;
        final String[] userPath;
        final String[] sitePath;
        final String trustedUserHeader;
        final String[] trustedUserHeaderValues;
        final String fallbackUser;

        public Hook(String id, int targetArgument, List<String> requestPath,
                    List<String> userPath, List<String> sitePath, String trustedUserHeader,
                    List<String> trustedUserHeaderValues, String fallbackUser) {
            if (safe(id) == null || targetArgument < -1 || targetArgument > 31) {
                throw new IllegalArgumentException("Invalid HTTP identity hook id/target");
            }
            this.id = id;
            this.targetArgument = targetArgument;
            this.requestPath = path(requestPath);
            this.userPath = path(userPath);
            this.sitePath = path(sitePath);
            this.trustedUserHeader = trustedUserHeader == null ? null : safe(trustedUserHeader);
            if (trustedUserHeader != null && this.trustedUserHeader == null) {
                throw new IllegalArgumentException("Invalid trusted identity header");
            }
            if ("Authorization".equalsIgnoreCase(this.trustedUserHeader)
                    || "Proxy-Authorization".equalsIgnoreCase(this.trustedUserHeader)
                    || "Cookie".equalsIgnoreCase(this.trustedUserHeader)
                    || "Set-Cookie".equalsIgnoreCase(this.trustedUserHeader)) {
                throw new IllegalArgumentException("Credential headers cannot be identity selectors");
            }
            if (trustedUserHeaderValues == null || trustedUserHeaderValues.size() > 16) {
                throw new IllegalArgumentException("Invalid trusted identity header values");
            }
            this.trustedUserHeaderValues = trustedUserHeaderValues.toArray(new String[0]);
            for (String value : this.trustedUserHeaderValues) {
                if (safe(value) == null) throw new IllegalArgumentException("Invalid trusted identity header value");
            }
            this.fallbackUser = fallbackUser == null ? null : safe(fallbackUser);
            if (fallbackUser != null && this.fallbackUser == null) {
                throw new IllegalArgumentException("Invalid fallback identity");
            }
        }
    }

    public static void configure(List<Hook> configured) {
        if (configured == null || configured.size() > 16) {
            throw new IllegalArgumentException("At most 16 HTTP identity hooks are supported");
        }
        Map<String, Hook> next = new LinkedHashMap<>();
        for (Hook hook : configured) {
            if (hook == null || next.put(hook.id, hook) != null) {
                throw new IllegalArgumentException("HTTP identity hook ids must be unique");
            }
        }
        hooks = Collections.unmodifiableMap(next);
    }

    /** Advice invokes this ONLY on a successful return from the configured seam. */
    public static void onSuccessfulExit(String id, Object receiver, Object[] arguments) {
        try {
            Hook hook = hooks.get(id);
            if (hook == null) return;
            Object target = hook.targetArgument == -1 ? receiver
                    : arguments != null && hook.targetArgument < arguments.length
                    ? arguments[hook.targetArgument] : null;
            if (target == null) return;
            Object request = walk(target, hook.requestPath);
            if (request == null) return;
            String site = site(walk(target, hook.sitePath));
            if (site == null) return;
            boolean trusted = hook.trustedUserHeader == null;
            if (!trusted) {
                String value = requestHeader(request, hook.trustedUserHeader);
                for (String allowed : hook.trustedUserHeaderValues) {
                    if (allowed.equals(value)) { trusted = true; break; }
                }
            }
            String user = null;
            if (trusted) {
                Object value = walk(target, hook.userPath);
                if (value instanceof String) user = safe((String) value);
            }
            if (user == null) user = hook.fallbackUser;
            if (user == null) return;
            String identity = safe(user + "_" + site);
            if (identity != null) HttpTraceRuntime.identify(request, identity, site, hook.id);
        } catch (Throwable ignored) {
            // A missing/throwing getter or unsupported model must never change auth behavior.
        }
    }

    private static Object walk(Object root, String[] path) throws Exception {
        Object value = root;
        for (String step : path) {
            if (value == null) return null;
            ConcurrentHashMap<String, Method> cache = GETTERS.get(value.getClass());
            Method getter = cache.get(step);
            if (getter == null) {
                getter = publicMethod(value.getClass(), step, new Class<?>[0]);
                if (getter == null || Modifier.isStatic(getter.getModifiers())
                        || getter.getReturnType() == Void.TYPE || getter.getDeclaringClass() == Object.class) return null;
                cache.putIfAbsent(step, getter);
            }
            value = getter.invoke(value);
        }
        return value;
    }

    private static String requestHeader(Object request, String name) throws Exception {
        Method getter = publicMethod(request.getClass(), "getHeader", new Class<?>[]{String.class});
        if (getter == null || Modifier.isStatic(getter.getModifiers())) return null;
        Object value = getter.invoke(request, name);
        return value instanceof String ? safe((String) value) : null;
    }

    private static String site(Object value) {
        if (value instanceof String) return safe((String) value);
        // Only scalar numbers, never arbitrary Object.toString (which can expose a model).
        if (value instanceof Number) return safe(value.toString());
        return null;
    }

    static String safe(String value) {
        if (value == null || value.length() > 256) return null;
        // Reject controls before trimming, including hidden newlines at either edge.
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < 32 || value.charAt(i) > 126) return null;
        }
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    private static String[] path(List<String> steps) {
        if (steps == null || steps.size() > 8) throw new IllegalArgumentException("Invalid identity getter path");
        String[] copy = steps.toArray(new String[0]);
        for (String step : copy) {
            if (step == null || !step.matches("[A-Za-z_$][A-Za-z0-9_$]{0,127}") || objectMethod(step)) {
                throw new IllegalArgumentException("Identity paths must contain getter method names only");
            }
        }
        return copy;
    }

    private static boolean objectMethod(String name) {
        return "getClass".equals(name) || "toString".equals(name) || "hashCode".equals(name)
                || "equals".equals(name) || "wait".equals(name) || "notify".equals(name)
                || "notifyAll".equals(name) || "clone".equals(name) || "finalize".equals(name);
    }

    private static Method publicMethod(Class<?> type, String name, Class<?>[] arguments) {
        if (Modifier.isPublic(type.getModifiers())) {
            try {
                Method method = type.getMethod(name, arguments);
                if (Modifier.isPublic(method.getDeclaringClass().getModifiers())) return method;
            } catch (NoSuchMethodException ignored) { }
        }
        for (Class<?> api : type.getInterfaces()) {
            Method method = publicMethod(api, name, arguments);
            if (method != null) return method;
        }
        return type.getSuperclass() == null ? null : publicMethod(type.getSuperclass(), name, arguments);
    }
}
