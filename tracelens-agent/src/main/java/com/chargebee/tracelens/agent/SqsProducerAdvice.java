package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.TraceRuntime;
import com.chargebee.tracelens.bridge.MessagingBoundaryRuntime;
import net.bytebuddy.asm.Advice;

/**
 * Stamps the current trace identity onto outgoing SQS messages (AWS SDK v1
 * SendMessageRequest) as message attributes — the producer half of async
 * correlation. SDK-free: the bridge does the reflective attribute injection.
 */
public final class SqsProducerAdvice {

    private SqsProducerAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Object enter(@Advice.Argument(0) Object sendMessageRequest,
                               @Advice.Origin("#m") String method) {
        boolean batch = "sendMessageBatch".equals(method);
        // Preserve the existing single-message propagation behavior. Batch
        // observation does not add new header/attribute propagation.
        if (!batch && MessagingBoundaryRuntime.isPropagationEnabled()) {
            TraceRuntime.injectMessageAttributes(sendMessageRequest);
        }
        return MessagingBoundaryRuntime.enterSqs(sendMessageRequest, batch);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter Object token, @Advice.Thrown Throwable failure) {
        MessagingBoundaryRuntime.exitPublish(token, failure);
    }
}
