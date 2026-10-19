package com.chargebee.tracelens.agent;

import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.model.SendMessageBatchRequest;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.chargebee.queueman.listener.AbstractQueuemanMessageHandler;
import com.chargebee.tracelens.bridge.*;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the real install matchers with SDK-shaped, no-network test fixtures. */
class MessagingBoundaryWeavingTest {
    @AfterEach void cleanup() {
        TraceRuntime.unregisterSink();
        TraceContext.clear();
        MessagingBoundaryRuntime.configure(false, false);
    }

    @Test void sendsPollAndHandlerAreMetadataOnlyAndCaptureDoesNotEnablePropagation() throws Exception {
        Instrumentation instrumentation = ByteBuddyAgent.install();
        Method install = TraceLensAgent.class.getDeclaredMethod("installMessagingInstrumentation", Instrumentation.class);
        install.setAccessible(true);
        install.invoke(null, instrumentation);
        List<Map<String, String>> events = new ArrayList<>();
        TraceRuntime.registerSink(new TraceRuntime.Sink() {
            public void onLine(String s, String c, int l) { }
            public void onMethodEnter(String s, String c, String m) { }
            public void onMethodExit(String s, String c, String m) { }
            public void onException(String s, String c, String m, String t, String v) { }
            public void onBoundary(String s, Map<String, String> fields) { events.add(fields); }
        });
        MessagingBoundaryRuntime.configure(true, false);
        try (TraceScope ignored = TraceContext.activate(new TraceSnapshot("trace", "segment", "lens", "user", null))) {
            SendMessageRequest sqsRequest = new SendMessageRequest();
            new SqsClient().sendMessage(sqsRequest);
            assertThat(sqsRequest.attributeWrites).isZero();
            new SqsClient().sendMessageBatch(new SendMessageBatchRequest());
            new KafkaProducer().send(new ProducerRecord());
            new KafkaConsumer().poll(java.time.Duration.ofMillis(10));
            new Handler().processMessage(new Poison(), Collections.singletonMap("queueName", "handler-queue"), new Poison());
            assertThat(events).hasSize(5);
            assertThat(events.get(0)).containsEntry("system", "sqs").containsEntry("status", "SUBMITTED")
                    .containsEntry("destination", "test-queue");
            assertThat(events.get(1)).containsEntry("operation", "sendMessageBatch").containsEntry("batchRecordCount", "2");
            assertThat(events.get(2)).containsEntry("system", "kafka").containsEntry("direction", "publish")
                    .containsEntry("status", "SUBMITTED");
            assertThat(events.get(3)).containsEntry("system", "kafka").containsEntry("direction", "consume")
                    .containsEntry("status", "OBSERVED");
            assertThat(events.get(4)).containsEntry("system", "queueman").containsEntry("direction", "consume");
            assertThat(events.toString()).doesNotContain("secret", "hidden");

            // Existing single-send injection remains available only when explicitly enabled.
            MessagingBoundaryRuntime.configure(true, true);
            new SqsClient().sendMessage(sqsRequest);
            assertThat(sqsRequest.attributeWrites).isGreaterThan(0);
        }
        int before = events.size();
        new KafkaConsumer().poll(10);
        new KafkaProducer().send(new ProducerRecord());
        assertThat(events).hasSize(before);
        assertThat(TraceContext.current()).isNull();
    }

    public static class SqsClient implements AmazonSQS {
        public Object sendMessage(SendMessageRequest request) { return new Object(); }
        public Object sendMessageBatch(SendMessageBatchRequest request) { return new Object(); }
    }
    public static class Handler extends AbstractQueuemanMessageHandler { }
    public static class Poison {
        @Override public String toString() { throw new AssertionError("payload inspected"); }
    }
}
