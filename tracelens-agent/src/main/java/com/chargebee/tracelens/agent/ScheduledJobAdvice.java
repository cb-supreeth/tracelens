package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.TraceRuntime;
import net.bytebuddy.asm.Advice;

/**
 * Synthetic traces for @Scheduled jobs (EMS is job-heavy; FlowLens covers only
 * HTTP/Kafka/JMS). lens-id = "ClassName.method", channel = SCHEDULER. No-op when the
 * app has no tracing stack registered.
 */
public final class ScheduledJobAdvice {

    private ScheduledJobAdvice() {
    }

    @Advice.OnMethodEnter
    public static String enter(@Advice.Origin("#t") String type, @Advice.Origin("#m") String method) {
        String simple = type.substring(type.lastIndexOf('.') + 1);
        return TraceRuntime.beginLifecycle(null, simple + "." + method, "scheduler",
                "SCHEDULER", null);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@Advice.Enter String segmentId, @Advice.Thrown Throwable thrown) {
        TraceRuntime.endLifecycle(segmentId);
    }
}
