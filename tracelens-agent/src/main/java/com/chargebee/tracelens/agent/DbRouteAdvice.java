package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.TraceRuntime;
import net.bytebuddy.asm.Advice;

/**
 * DB_ROUTE events from any Spring AbstractRoutingDataSource subclass — which pool
 * (live/test x master/slave x RLS) each connection acquisition routed to. Generic by
 * design (works for any Chargebee service using Spring's routing datasource).
 */
public final class DbRouteAdvice {

    private DbRouteAdvice() {
    }

    @Advice.OnMethodExit
    public static void exit(@Advice.Return Object lookupKey) {
        if (lookupKey != null) {
            TraceRuntime.onDbRoute(String.valueOf(lookupKey));
        }
    }
}
