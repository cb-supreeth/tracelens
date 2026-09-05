package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.TraceRuntime;
import com.chargebee.tracelens.bridge.TraceSnapshot;
import net.bytebuddy.asm.Advice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Consumer half of async correlation: on queueman message entry, read the trace
 * identity the producer stamped into message attributes and continue the trace as a
 * new segment (same traceId join key). Untraced messages pass through untouched.
 *
 * Applied to AbstractQueuemanMessageHandler.processMessage(T, Map, CommitHandler).
 */
public final class QueuemanConsumerAdvice {

    private QueuemanConsumerAdvice() {
    }

    @Advice.OnMethodEnter
    public static String enter(@Advice.Argument(1) Map<String, Object> headers) {
        if (headers == null) {
            return null;
        }
        Map<String, String> wire = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : headers.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                wire.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        String traceId = wire.remove(TraceSnapshot.TRACE_ID_KEY);
        if (traceId == null || traceId.isBlank()) {
            return null; // untraced message — no segment
        }
        String lensId = wire.remove(TraceSnapshot.LENS_ID_KEY);
        String user = wire.remove(TraceSnapshot.USER_KEY);
        // segment-id STAYS in the wire map: TraceLifecycle reads it as parentSegmentId
        return TraceRuntime.beginLifecycle(traceId, lensId, user, "SQS", wire);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@Advice.Enter String segmentId) {
        TraceRuntime.endLifecycle(segmentId);
    }
}
