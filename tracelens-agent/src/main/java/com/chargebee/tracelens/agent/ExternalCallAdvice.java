package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.TraceRuntime;
import net.bytebuddy.asm.Advice;

/**
 * EXTERNAL_CALL events for configured wrapper classes (ClickHouse executor, DynamoDB
 * repository, ...). Config-driven via tracelens.json externalCallClasses.
 */
public final class ExternalCallAdvice {

    private ExternalCallAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.Origin("#t") String type, @Advice.Origin("#m") String method) {
        TraceRuntime.onExternalCall(type.substring(type.lastIndexOf('.') + 1), method);
    }
}
