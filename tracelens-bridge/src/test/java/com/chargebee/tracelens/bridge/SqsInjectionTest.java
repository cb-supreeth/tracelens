package com.chargebee.tracelens.bridge;

import com.amazonaws.services.sqs.model.SendMessageRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The producer-side SQS attribute injection (reflective, SDK-free bridge code). */
class SqsInjectionTest {

    @AfterEach
    void cleanup() {
        TraceContext.clear();
    }

    @Test
    void injectsTraceIdentityAsMessageAttributes() {
        TraceSnapshot snapshot = new TraceSnapshot("trace-1", "seg-1", "my-lens",
                "me@chargebee.com", Map.of("siteId", "site-42"));
        try (TraceScope ignored = TraceContext.activate(snapshot)) {
            SendMessageRequest request = new SendMessageRequest();
            TraceRuntime.injectMessageAttributes(request);

            Map<String, com.amazonaws.services.sqs.model.MessageAttributeValue> attrs =
                    request.getMessageAttributes();
            assertThat(attrs).containsKeys("tracelens-trace-id", "tracelens-segment-id",
                    "tracelens-id", "tracelens-user", "siteId");
            assertThat(attrs.get("tracelens-trace-id").getStringValue()).isEqualTo("trace-1");
            assertThat(attrs.get("tracelens-segment-id").getStringValue()).isEqualTo("seg-1");
            assertThat(attrs.get("siteId").getStringValue()).isEqualTo("site-42");
        }
    }

    @Test
    void noOpWithoutActiveTrace() {
        SendMessageRequest request = new SendMessageRequest();
        TraceRuntime.injectMessageAttributes(request);
        assertThat(request.getMessageAttributes()).isEmpty();
    }

    @Test
    void noOpOnUnfamiliarRequestShape() {
        try (TraceScope ignored = TraceContext.activate(
                new TraceSnapshot("t", "l", "u", null))) {
            // must not throw on an object without addMessageAttributesEntry
            TraceRuntime.injectMessageAttributes(new Object());
        }
    }
}
