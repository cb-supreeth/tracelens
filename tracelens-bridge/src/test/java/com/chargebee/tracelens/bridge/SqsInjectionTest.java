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

    @Test
    void propagatesOnlyBoundedCorrelationAndTranslatesHttpActionFields() {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("httpMethod", "POST");
        fields.put("path", "/private/path");
        fields.put("serviceInstanceId", "local-instance");
        fields.put("httpRequestBody", "private payload");
        fields.put("actionId", "action-1");
        fields.put("actionName", "checkout");
        fields.put("sessionId", "session-1");
        fields.put("siteId", "site-1");
        fields.put("spanId", "0123456789abcdef");
        fields.put("traceFlags", "01");
        fields.put("tracestate", "vendor=opaque");
        TraceSnapshot snapshot = new TraceSnapshot("0123456789abcdef0123456789abcdef", "segment", "lens", "user", fields);
        try (TraceScope ignored = TraceContext.activate(snapshot)) {
            SendMessageRequest request = new SendMessageRequest();
            TraceRuntime.injectMessageAttributes(request);
            assertThat(request.getMessageAttributes()).hasSize(10)
                    .containsKeys(TraceSnapshot.ACTION_ID_KEY, TraceSnapshot.ACTION_NAME_KEY, TraceSnapshot.SESSION_ID_KEY)
                    .doesNotContainKeys("path", "httpMethod", "serviceInstanceId", "httpRequestBody", "tracestate");
            assertThat(request.replacements).isEqualTo(1);
            Map<String, String> wire = new java.util.LinkedHashMap<>();
            request.getMessageAttributes().forEach((key, value) -> wire.put(key, value.getStringValue()));
            TraceSnapshot continued = TraceSnapshot.fromMap(wire);
            assertThat(continued.getAttributes()).containsEntry("actionId", "action-1")
                    .containsEntry("actionName", "checkout").containsEntry("sessionId", "session-1");
        }
    }

    @Test
    void fullCapacityAndConflictingCarriersNeverPartiallyModifyRequests() {
        try (TraceScope ignored = TraceContext.activate(new TraceSnapshot("trace", "lens", "user", null))) {
            SendMessageRequest full = new SendMessageRequest();
            for (int i = 0; i < 7; i++) full.addMessageAttributesEntry("app-" + i, value("kept"));
            Map<String, com.amazonaws.services.sqs.model.MessageAttributeValue> original = full.getMessageAttributes();
            TraceRuntime.injectMessageAttributes(full);
            assertThat(full.getMessageAttributes()).isSameAs(original).hasSize(7);
            assertThat(full.replacements).isZero();

            SendMessageRequest conflict = new SendMessageRequest();
            conflict.addMessageAttributesEntry(TraceSnapshot.ACTION_ID_KEY, value("existing-action"));
            TraceRuntime.injectMessageAttributes(conflict);
            assertThat(conflict.getMessageAttributes()).hasSize(1);
            assertThat(conflict.replacements).isZero();
        }
    }

    @Test
    void preservesApplicationAttributesAndDeclinesRequestReuseOrInvalidIdentity() {
        SendMessageRequest request = new SendMessageRequest();
        com.amazonaws.services.sqs.model.MessageAttributeValue app = value("application-value");
        request.addMessageAttributesEntry("application", app);
        try (TraceScope ignored = TraceContext.activate(new TraceSnapshot("trace", "lens", "user", null))) {
            TraceRuntime.injectMessageAttributes(request);
            assertThat(request.getMessageAttributes().get("application")).isSameAs(app);
            Map<String, com.amazonaws.services.sqs.model.MessageAttributeValue> first = request.getMessageAttributes();
            TraceRuntime.injectMessageAttributes(request);
            assertThat(request.getMessageAttributes()).isSameAs(first);
            assertThat(request.replacements).isEqualTo(1);
        }
        try (TraceScope ignored = TraceContext.activate(new TraceSnapshot("x".repeat(129), "lens", "user", null))) {
            SendMessageRequest invalid = new SendMessageRequest();
            TraceRuntime.injectMessageAttributes(invalid);
            assertThat(invalid.getMessageAttributes()).isEmpty();
        }
    }

    @Test
    void replacementFailureLeavesOriginalMapUntouched() {
        try (TraceScope ignored = TraceContext.activate(new TraceSnapshot("trace", "lens", "user", null))) {
            SendMessageRequest request = new SendMessageRequest();
            request.addMessageAttributesEntry("application", value("kept"));
            Map<String, com.amazonaws.services.sqs.model.MessageAttributeValue> original = request.getMessageAttributes();
            request.failReplacement = true;
            TraceRuntime.injectMessageAttributes(request);
            assertThat(request.getMessageAttributes()).isSameAs(original).hasSize(1);
        }
    }

    @Test
    void scopedInjectionRestoresReusableRequestsWithoutDiscardingApplicationChanges() {
        SendMessageRequest request = new SendMessageRequest();
        com.amazonaws.services.sqs.model.MessageAttributeValue app = value("kept");
        request.addMessageAttributesEntry("application", app);
        try (TraceScope ignored = TraceContext.activate(new TraceSnapshot("first", "lens", "user", null))) {
            Object token = TraceRuntime.beginMessageAttributes(request);
            assertThat(request.getMessageAttributes()).containsKey(TraceSnapshot.TRACE_ID_KEY);
            TraceRuntime.endMessageAttributes(token);
            assertThat(request.getMessageAttributes()).containsOnlyKeys("application");
            assertThat(request.getMessageAttributes().get("application")).isSameAs(app);
        }
        try (TraceScope ignored = TraceContext.activate(new TraceSnapshot("second", "lens", "user", null))) {
            Object token = TraceRuntime.beginMessageAttributes(request);
            assertThat(request.getMessageAttributes().get(TraceSnapshot.TRACE_ID_KEY).getStringValue()).isEqualTo("second");
            request.addMessageAttributesEntry("added-during-send", value("application-change"));
            TraceRuntime.endMessageAttributes(token);
            assertThat(request.getMessageAttributes()).containsKey("added-during-send");
        }
    }

    private static com.amazonaws.services.sqs.model.MessageAttributeValue value(String text) {
        return new com.amazonaws.services.sqs.model.MessageAttributeValue().withDataType("String").withStringValue(text);
    }
}
