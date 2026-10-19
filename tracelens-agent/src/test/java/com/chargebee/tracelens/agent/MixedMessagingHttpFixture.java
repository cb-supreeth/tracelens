package com.chargebee.tracelens.agent;

import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.chargebee.tracelens.bridge.MessagingBoundaryRuntime;
import com.chargebee.tracelens.bridge.TraceContext;
import com.chargebee.tracelens.bridge.TraceRuntime;
import com.chargebee.tracelens.bridge.TraceScope;
import com.chargebee.tracelens.bridge.W3CTraceContext;
import com.chargebee.tracelens.core.config.TraceLensConfig;
import com.chargebee.tracelens.core.record.TraceLifecycle;
import com.chargebee.tracelens.core.record.Tracer;
import com.chargebee.tracelens.core.store.TraceStore;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/** Loaded only after the test injects the bridge, just as an application is after premain. */
public final class MixedMessagingHttpFixture {
    public static void run(String url) throws Exception {
        W3CTraceContext parent = W3CTraceContext.fromSnapshot(TraceContext.current());
        assertThat(parent).isNotNull();
        assertThat(TraceRuntime.class.getClassLoader()).isNull();
        SendMessageRequest sqs = new SendMessageRequest();
        TraceRuntime.injectMessageAttributes(sqs);
        Map<String, Object> headers = new LinkedHashMap<>();
        sqs.writtenAttributes.forEach((key, value) -> headers.put(key, value.recordedValue));
        assertThat(headers).containsKeys("spanId", "traceFlags", "tracestate");
        headers.put("Authorization", "private-unrelated-header");
        try (TraceStore store = new TraceStore(new TraceLensConfig().setApplicationId("mixed-flow")
                .setPackages(Collections.singletonList("fixture")))) {
            TraceLifecycle lifecycle = new TraceLifecycle(new Tracer(store));
            TraceRuntime.registerLifecycleListener(lifecycle);
            MessagingBoundaryRuntime.configure(true, true);
            try (TraceScope consumerThread = TraceContext.activate(null)) {
                Object consumption = MessagingBoundaryRuntime.enterQueueman(new MixedMessagingHttpFixture(), headers);
                assertThat(consumption).isNotNull();
                try {
                    W3CTraceContext continued = W3CTraceContext.fromSnapshot(TraceContext.current());
                    assertThat(continued).isNotNull();
                    assertThat(continued.getSpanId()).isEqualTo(parent.getSpanId());
                    assertThat(continued.getTracestate()).isEqualTo(parent.getTracestate());
                    assertThat(TraceContext.current().getAttributes()).doesNotContainKey("Authorization");
                    HttpURLConnection afterQueue = (HttpURLConnection) new URL(url).openConnection();
                    afterQueue.setConnectTimeout(3000);
                    afterQueue.setReadTimeout(3000);
                    assertThat(afterQueue.getResponseCode()).isEqualTo(200);
                    afterQueue.getInputStream().close();
                } finally { MessagingBoundaryRuntime.exitQueueman(consumption, null); }
                assertThat(TraceContext.current()).isNull();
            } finally {
                TraceRuntime.unregisterLifecycleListener(lifecycle);
                MessagingBoundaryRuntime.configure(false, false);
            }
        }
    }
}
