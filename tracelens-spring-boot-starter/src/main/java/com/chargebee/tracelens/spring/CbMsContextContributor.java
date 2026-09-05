package com.chargebee.tracelens.spring;

import com.chargebee.tracelens.core.trace.Trace;

import java.lang.reflect.Method;

/**
 * Stamps Chargebee site context onto HTTP traces: siteId, siteType, requestId — read
 * from cb-ms-utils' CbRequestHelper AFTER the security filter has populated it (the
 * filter calls us post-dispatch). Reflective so the starter stays usable in services
 * without cb-ms-* on the classpath (no-op there) — zero app code required.
 */
final class CbMsContextContributor {

    private static final Class<?> HELPER = tryLoad("com.chargebee.ms.utils.CbRequestHelper");
    private static final Method GET_SITE_ID = method("getSiteId");
    private static final Method GET_SITE_TYPE = method("getSiteType");
    private static final Method GET_REQUEST_ID = method("getRequestId");

    private CbMsContextContributor() {
    }

    static void contribute(Trace trace) {
        if (HELPER == null) {
            return;
        }
        try {
            Object siteId = GET_SITE_ID.invoke(null);
            if (siteId != null) {
                trace.putAttribute("siteId", String.valueOf(siteId));
            }
            Object siteType = GET_SITE_TYPE.invoke(null);
            if (siteType != null) {
                trace.putAttribute("siteType", String.valueOf(siteType));
            }
            Object requestId = GET_REQUEST_ID.invoke(null);
            if (requestId != null) {
                trace.putAttribute("requestId", String.valueOf(requestId));
            }
        } catch (Throwable ignored) {
            // context population is best-effort; never break the request
        }
    }

    private static Class<?> tryLoad(String fqcn) {
        try {
            return Class.forName(fqcn);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static Method method(String name) {
        if (HELPER == null) {
            return null;
        }
        try {
            return HELPER.getMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
