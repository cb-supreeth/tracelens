package com.chargebee.tracelens.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class MessagingBoundaryRuntimeTest {
    private final List<Map<String, String>> events = new ArrayList<>();
    private final List<String> segments = new ArrayList<>();
    private TraceScope scope;

    @BeforeEach void setup() {
        MessagingBoundaryRuntime.configure(true, false);
        TraceRuntime.registerSink(new TraceRuntime.Sink() {
            public void onLine(String s, String c, int l) { }
            public void onMethodEnter(String s, String c, String m) { }
            public void onMethodExit(String s, String c, String m) { }
            public void onException(String s, String c, String m, String t, String v) { }
            public void onBoundary(String segment, Map<String, String> fields) {
                segments.add(segment);
                events.add(fields);
            }
        });
        scope = TraceContext.activate(new TraceSnapshot("trace", "segment", "lens", "user", null));
    }

    @AfterEach void cleanup() {
        scope.close();
        TraceContext.clear();
        TraceRuntime.unregisterSink();
        TraceRuntime.unregisterLifecycleListener();
        MessagingBoundaryRuntime.configure(false, false);
    }

    @Test void sqsQueueMetadataDoesNotReadBodiesAttributesCredentialsOrQuery() {
        Object token = MessagingBoundaryRuntime.enterSqs(new SqsRequest(), false);
        MessagingBoundaryRuntime.exitPublish(token, null);
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("system", "sqs").containsEntry("direction", "publish")
                .containsEntry("destination", "queue.fifo").containsEntry("status", "SUBMITTED")
                .containsEntry("boundaryKind", "messaging").containsEntry("evidenceKind", "client-boundary");
        assertThat(events.toString()).doesNotContain("private", "account-id", "secret");
        assertThat(segments).containsExactly("segment");
    }

    @Test void sqsBatchCountsEntriesWithoutReadingThemAndHandlesThrowingCollection() {
        MessagingBoundaryRuntime.exitPublish(MessagingBoundaryRuntime.enterSqs(new SqsBatch(), true), null);
        assertThat(events.get(0)).containsEntry("operation", "sendMessageBatch").containsEntry("batchRecordCount", "2");
        MessagingBoundaryRuntime.exitPublish(MessagingBoundaryRuntime.enterSqs(new BrokenBatch(), true), null);
        MessagingBoundaryRuntime.exitPublish(MessagingBoundaryRuntime.enterSqs(new SqsRequest(), false), null);
        assertThat(events).hasSize(3); // a throwing accessor must not leave a stale dedupe scope
    }

    @Test void kafkaSendDedupePreservesActualNestedSendsAndNeverClaimsFutureSuccess() {
        KafkaRecord record = new KafkaRecord("billing-events");
        Object outer = MessagingBoundaryRuntime.enterKafkaSend(record);
        Object delegated = MessagingBoundaryRuntime.enterKafkaSend(record);
        MessagingBoundaryRuntime.exitPublish(delegated, null);
        assertThat(events).isEmpty();
        MessagingBoundaryRuntime.exitPublish(MessagingBoundaryRuntime.enterKafkaSend(new KafkaRecord("other")), null);
        MessagingBoundaryRuntime.exitPublish(outer, null);
        assertThat(events).hasSize(2);
        assertThat(events.get(1)).containsEntry("destination", "billing-events").containsEntry("status", "SUBMITTED");
        assertThat(events.toString()).doesNotContain("COMPLETED", "DELIVERED");
    }

    @Test void failuresCaptureOnlyExceptionTypeAndMalformedAccessorsFailOpen() {
        MessagingBoundaryRuntime.exitPublish(MessagingBoundaryRuntime.enterKafkaSend(new KafkaRecord("events")),
                new IllegalStateException("private-payload"));
        MessagingBoundaryRuntime.exitPublish(MessagingBoundaryRuntime.enterKafkaSend(new BrokenRecord()), null);
        MessagingBoundaryRuntime.exitPublish(MessagingBoundaryRuntime.enterSqs(new Object(), false), null);
        assertThat(events).hasSize(3);
        assertThat(events.get(0)).containsEntry("status", "ERROR")
                .containsEntry("errorType", "java.lang.IllegalStateException");
        assertThat(events.get(1)).containsEntry("destination", "unknown");
        assertThat(events.get(2)).containsEntry("destination", "unknown");
        assertThat(events.toString()).doesNotContain("private-payload");
    }

    @Test void untracedAndDisabledOperationsDoNotReadSdkObjects() {
        TraceContext.clear();
        assertThat(MessagingBoundaryRuntime.enterKafkaSend(new BrokenRecord())).isNull();
        assertThat(MessagingBoundaryRuntime.enterSqs(new SqsRequest(), false)).isNull();
        assertThat(MessagingBoundaryRuntime.enterKafkaPoll()).isNull();
        MessagingBoundaryRuntime.exitKafkaPoll(null, new Records(), null);
        MessagingBoundaryRuntime.configure(false, false);
        try (TraceScope ignored = TraceContext.activate(new TraceSnapshot("t", "l", "u", null))) {
            assertThat(MessagingBoundaryRuntime.enterKafkaSend(new BrokenRecord())).isNull();
            assertThat(MessagingBoundaryRuntime.enterQueueman(this, null)).isNull();
        }
        assertThat(events).isEmpty();
    }

    @Test void kafkaPollObservesTopicsWithoutReadingRecordsOrBindingContext() {
        TraceSnapshot before = TraceContext.current();
        MessagingBoundaryRuntime.exitKafkaPoll(MessagingBoundaryRuntime.enterKafkaPoll(), new Records(), null);
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).containsEntry("system", "kafka").containsEntry("direction", "consume")
                .containsEntry("destination", "events").containsEntry("status", "OBSERVED")
                .containsEntry("batchRecordCount", "3").containsEntry("observation", "poll-delivery-only");
        assertThat(TraceContext.current()).isSameAs(before);
    }

    @Test void queuemanKeepsExistingContextAndNeverStringifiesHeaders() {
        Map<String, Object> headers = new HashMap<>();
        headers.put("queueName", "ems-events");
        headers.put("payload", new Poison());
        headers.put(TraceSnapshot.TRACE_ID_KEY, new Poison());
        TraceSnapshot before = TraceContext.current();
        MessagingBoundaryRuntime.exitQueueman(MessagingBoundaryRuntime.enterQueueman(this, headers), null);
        assertThat(TraceContext.current()).isSameAs(before);
        assertThat(events.get(0)).containsEntry("system", "queueman").containsEntry("direction", "consume")
                .containsEntry("destination", "ems-events").containsEntry("status", "OBSERVED");
    }

    @Test void untracedQueuemanStartsAndClosesOnlyLifecycleAcceptedLocalSegment() {
        TraceContext.clear();
        List<String> ended = new ArrayList<>();
        TraceRuntime.registerLifecycleListener(new TraceRuntime.LifecycleListener() {
            TraceScope lifecycleScope;
            public String begin(String traceId, String lens, String user, String channel, Map<String, String> attrs) {
                assertThat(traceId).isNull();
                assertThat(attrs).isEmpty();
                assertThat(lens).isEqualTo("auto:queueman");
                assertThat(user).isEqualTo("anonymous");
                lifecycleScope = TraceContext.activate(new TraceSnapshot("local", "new-segment", lens, user, attrs));
                return "new-segment";
            }
            public void end(String segment) { ended.add(segment); lifecycleScope.close(); }
        });
        Map<String, Object> headers = new HashMap<>();
        headers.put(TraceSnapshot.TRACE_ID_KEY, "untrusted-upstream");
        headers.put(TraceSnapshot.USER_KEY, "private-user");
        headers.put("spanId", "0123456789abcdef");
        headers.put("traceFlags", "01");
        headers.put("tracestate", "private=upstream");
        headers.put("payload", new Poison());
        Object token = MessagingBoundaryRuntime.enterQueueman(this, headers);
        assertThat(token).isNotNull();
        MessagingBoundaryRuntime.exitQueueman(token, new IllegalArgumentException("private"));
        assertThat(ended).containsExactly("new-segment");
        assertThat(segments).containsExactly("new-segment");
        assertThat(TraceContext.current()).isNull();
        assertThat(events.get(0)).containsEntry("status", "ERROR");
        assertThat(events.toString()).doesNotContain("private", "untrusted-upstream");
    }

    @Test void continuationPreservesValidatedW3cFieldsAndLongTracestateOnly() {
        TraceContext.clear();
        MessagingBoundaryRuntime.configure(true, true);
        CapturingLifecycle lifecycle = new CapturingLifecycle();
        TraceRuntime.registerLifecycleListener(lifecycle);
        Map<String, Object> headers = validW3cHeaders();
        String state = "first=" + "a".repeat(250) + ",second=" + "b".repeat(200);
        headers.put("tracestate", state);
        headers.put("authorization", new Poison());
        Object token = MessagingBoundaryRuntime.enterQueueman(this, headers);
        try {
            W3CTraceContext restored = W3CTraceContext.fromSnapshot(TraceContext.current());
            assertThat(restored).isNotNull();
            assertThat(restored.getTraceId()).isEqualTo(headers.get(TraceSnapshot.TRACE_ID_KEY));
            assertThat(restored.getSpanId()).isEqualTo(headers.get("spanId"));
            assertThat(restored.getFlags()).isEqualTo("01");
            assertThat(restored.getTracestate()).isEqualTo(state);
            assertThat(lifecycle.attributes).containsOnlyKeys("spanId", "traceFlags", "tracestate");
        } finally { MessagingBoundaryRuntime.exitQueueman(token, null); }
        assertThat(TraceContext.current()).isNull();
    }

    @Test void malformedW3cFieldsAreNotSanitizedIntoValidPropagationContext() {
        TraceContext.clear();
        MessagingBoundaryRuntime.configure(true, true);
        CapturingLifecycle lifecycle = new CapturingLifecycle();
        TraceRuntime.registerLifecycleListener(lifecycle);
        for (Map<String, Object> invalid : Arrays.asList(
                Collections.<String, Object>singletonMap("spanId", "0000000000000000"),
                Collections.<String, Object>singletonMap("spanId", "0123456789abcdef\n"),
                Collections.<String, Object>singletonMap("traceFlags", "01\n"),
                Collections.<String, Object>singletonMap("traceFlags", new Poison()),
                Collections.<String, Object>singletonMap(TraceSnapshot.TRACE_ID_KEY, "0123456789abcdef0123456789abcdef\n"))) {
            Map<String, Object> headers = validW3cHeaders();
            headers.putAll(invalid);
            Object token = MessagingBoundaryRuntime.enterQueueman(this, headers);
            try {
                assertThat(lifecycle.attributes).doesNotContainKeys("spanId", "traceFlags", "tracestate");
                assertThat(W3CTraceContext.fromSnapshot(TraceContext.current())).isNull();
            } finally { MessagingBoundaryRuntime.exitQueueman(token, null); }
        }
        for (String invalidState : Arrays.asList("vendor=unsafe\nvalue", "a=" + "b".repeat(513))) {
            Map<String, Object> headers = validW3cHeaders();
            headers.put("tracestate", invalidState);
            Object token = MessagingBoundaryRuntime.enterQueueman(this, headers);
            try {
                assertThat(lifecycle.attributes).containsKeys("spanId", "traceFlags").doesNotContainKey("tracestate");
            } finally { MessagingBoundaryRuntime.exitQueueman(token, null); }
        }
    }

    private static Map<String, Object> validW3cHeaders() {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put(TraceSnapshot.TRACE_ID_KEY, "0123456789abcdef0123456789abcdef");
        headers.put("spanId", "0123456789abcdef");
        headers.put("traceFlags", "01");
        headers.put("tracestate", "vendor=fixture");
        return headers;
    }

    private static class CapturingLifecycle implements TraceRuntime.LifecycleListener {
        Map<String, String> attributes;
        TraceScope scope;
        public String begin(String traceId, String lens, String user, String channel, Map<String, String> attrs) {
            attributes = new LinkedHashMap<>(attrs);
            scope = TraceContext.activate(new TraceSnapshot(traceId, "consumer-segment", lens, user, attrs));
            return "consumer-segment";
        }
        public void end(String segment) { scope.close(); }
    }

    @Test void missingLifecycleAndThrowingSinkFailOpen() {
        TraceContext.clear();
        assertThat(MessagingBoundaryRuntime.enterQueueman(this, null)).isNull();
        assertThatCode(() -> TraceRuntime.onBoundary("segment", null)).doesNotThrowAnyException();
    }

    public static class Poison {
        @Override public String toString() { throw new AssertionError("payload must not be read"); }
    }
    public static class SqsRequest {
        public String getQueueUrl() { return "https://private:secret@sqs.example/account-id/queue.fifo?secret=private#private"; }
        public String getMessageBody() { throw new AssertionError("body read"); }
        public Map<String, Object> getMessageAttributes() { throw new AssertionError("attributes read"); }
    }
    public static class SqsBatch extends SqsRequest {
        public Collection<?> getEntries() { return Arrays.asList(new Poison(), new Poison()); }
    }
    public static class BrokenBatch extends SqsRequest {
        public Collection<?> getEntries() {
            return new AbstractCollection<Object>() {
                public Iterator<Object> iterator() { throw new AssertionError("entries read"); }
                public int size() { throw new AssertionError("broken size"); }
            };
        }
    }
    public static class KafkaRecord {
        private final String topic;
        KafkaRecord(String topic) { this.topic = topic; }
        public String topic() { return topic; }
        public Object key() { throw new AssertionError("key read"); }
        public Object value() { throw new AssertionError("value read"); }
        public Object headers() { throw new AssertionError("headers read"); }
    }
    public static class BrokenRecord {
        public String topic() { throw new AssertionError("broken accessor"); }
    }
    public static class Partition {
        final String topic;
        Partition(String topic) { this.topic = topic; }
        public String topic() { return topic; }
    }
    public static class Records implements Iterable<Object> {
        public int count() { return 3; }
        public Set<Partition> partitions() {
            return new LinkedHashSet<>(Arrays.asList(new Partition("events"), new Partition("events"), new Partition("other")));
        }
        public Iterator<Object> iterator() { throw new AssertionError("records read"); }
    }
}
