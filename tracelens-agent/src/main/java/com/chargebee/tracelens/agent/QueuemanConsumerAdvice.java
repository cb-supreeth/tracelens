package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.MessagingBoundaryRuntime;
import net.bytebuddy.asm.Advice;

import java.util.Map;

/**
 * Consumer half of async correlation: on queueman message entry, read the trace
 * identity the producer stamped into message attributes and continue the trace as a
 * new segment (same traceId join key). Uncorrelated handlers start a local segment
 * only when the registered lifecycle accepts one; an existing context is retained.
 *
 * Applied to AbstractQueuemanMessageHandler.processMessage(T, Map, CommitHandler).
 */
public final class QueuemanConsumerAdvice {

    private QueuemanConsumerAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Object enter(@Advice.This Object handler, @Advice.Argument(1) Map<?, ?> headers) {
        return MessagingBoundaryRuntime.enterQueueman(handler, headers);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter Object token, @Advice.Thrown Throwable failure) {
        MessagingBoundaryRuntime.exitQueueman(token, failure);
    }
}
