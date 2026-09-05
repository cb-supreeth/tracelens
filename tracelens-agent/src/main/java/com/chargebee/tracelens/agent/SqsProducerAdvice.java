package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.TraceRuntime;
import net.bytebuddy.asm.Advice;

/**
 * Stamps the current trace identity onto outgoing SQS messages (AWS SDK v1
 * SendMessageRequest) as message attributes — the producer half of async
 * correlation. SDK-free: the bridge does the reflective attribute injection.
 */
public final class SqsProducerAdvice {

    private SqsProducerAdvice() {
    }

    @Advice.OnMethodEnter
    public static void enter(@Advice.Argument(0) Object sendMessageRequest) {
        TraceRuntime.injectMessageAttributes(sendMessageRequest);
    }
}
