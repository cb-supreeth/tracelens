package com.chargebee.tracelens.core.record;

import com.chargebee.tracelens.core.config.TraceLensConfig;
import com.chargebee.tracelens.core.event.EventType;
import com.chargebee.tracelens.core.event.RuntimeThreadIdentity;
import com.chargebee.tracelens.core.event.TraceEvent;
import com.chargebee.tracelens.core.export.TraceExporter;
import com.chargebee.tracelens.core.export.TraceProjection;
import com.chargebee.tracelens.core.store.TraceStore;
import com.chargebee.tracelens.core.trace.EntryChannel;
import com.chargebee.tracelens.core.trace.Trace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class EventIdentityTest {
    private final TraceLensConfig config = new TraceLensConfig().setApplicationId("test").setPackages(List.of("a.b"));
    private final TraceStore store = new TraceStore(config);
    private final TraceSink sink = new TraceSink(store);
    private Trace trace() {
        Trace trace = new Trace("test", null, EntryChannel.HTTP, System.currentTimeMillis(), System.nanoTime());
        store.register(trace);
        return trace;
    }

    @Test void allSinkEventsRetainOneRuntimeIdentityAndOptionalDescriptor() throws Exception {
        Trace trace = trace();String id = trace.getSegmentId();Long threadId = RuntimeThreadIdentity.current();
        sink.onMethodEnter(id, "a.b.Service", "read", "(I)Ljava/lang/String;");
        sink.onLine(id, "a.b.Service", 12);
        sink.onMethodExit(id, "a.b.Service", "read", "(I)Ljava/lang/String;");
        sink.onException(id, "a.b.Service", "read", "()V", "java.lang.IllegalStateException", "message");
        sink.onDbRoute(id, "MASTER");
        sink.onExternalCall(id, "redis", "get");
        sink.onBoundary(id, Map.of("system", "mysql", "direction", "query", "destination", "local"));
        assertThat(trace.events()).hasSize(7).allSatisfy(event -> assertThat(event.getThreadId()).isEqualTo(threadId));
        assertThat(trace.events().get(0).getMethodDescriptor()).isEqualTo("(I)Ljava/lang/String;");
        assertThat(trace.events().get(1).getMethodDescriptor()).isNull();
        JsonNode exported = new ObjectMapper().readTree(new TraceExporter(config).export(trace)).path("events");
        assertThat(exported.get(0).path("threadId").asLong()).isEqualTo(threadId);
        assertThat(exported.get(0).path("methodDescriptor").asText()).isEqualTo("(I)Ljava/lang/String;");
        assertThat(exported.get(1).has("methodDescriptor")).isFalse();
        JsonNode paged = new TraceProjection(new TraceExporter(config)).events(trace, Map.of()).path("events").get(0);
        assertThat(paged.path("threadId").asLong()).isEqualTo(threadId);
        assertThat(paged.path("methodDescriptor").asText()).isEqualTo("(I)Ljava/lang/String;");
    }

    @Test void duplicateNamesHaveDifferentTokensAndRenamingKeepsToken() throws Exception {
        Trace trace = trace();String id = trace.getSegmentId();
        Runnable record = () -> {sink.onMethodEnter(id, "a.b.Service", "read", "()V");Thread.currentThread().setName("renamed");sink.onMethodExit(id, "a.b.Service", "read", "()V");};
        Thread first = new Thread(record, "worker"), second = new Thread(record, "worker");
        first.start();first.join();second.start();second.join();
        List<TraceEvent> events = trace.events();assertThat(events).hasSize(4);
        assertThat(events.get(0).getThreadId()).isEqualTo(events.get(1).getThreadId()).isNotEqualTo(events.get(2).getThreadId());
        assertThat(events.get(2).getThreadId()).isEqualTo(events.get(3).getThreadId());
        assertThat(events.get(0).getThread()).isEqualTo(events.get(2).getThread()).isEqualTo("worker");
        assertThat(events.get(1).getThread()).isEqualTo("renamed");
    }

    @Test void httpCallingContextUsesRuntimeIdentityAndCannotBeSpoofedByNameOrSuppliedFields() throws Exception {
        Trace trace = trace();String id = trace.getSegmentId();Long token = RuntimeThreadIdentity.current();
        sink.onLine(id, "a.b.Service", 12);
        Thread another = new Thread(() -> sink.onLine(id, "a.b.Other", 99), Thread.currentThread().getName());
        another.start();another.join();
        Map<String,String> call = new HashMap<>(Map.of("spanId", "call", "status", "ACTIVE", "callingThreadId", "spoofed"));
        trace.recordHttpCall(call);
        assertThat(trace.httpCalls().get(0)).containsEntry("callingEventSeq", "1").containsEntry("callingThreadId", token.toString());
        call.put("status", "COMPLETED");trace.recordHttpCall(call);
        assertThat(trace.httpCalls().get(0)).containsEntry("callingThreadId", token.toString());
        Trace onlyOther = trace();Thread different = new Thread(() -> sink.onLine(onlyOther.getSegmentId(), "a.b.Other", 1), Thread.currentThread().getName());
        different.start();different.join();call.put("status", "ACTIVE");onlyOther.recordHttpCall(call);
        assertThat(onlyOther.httpCalls().get(0)).containsEntry("callingContextStatus", "source-not-observed").doesNotContainKeys("callingThreadId", "callingEventSeq");
    }

    @Test void legacyEventsRemainUnenrichedAndDescriptorStorageIsBounded() {
        TraceEvent legacy = TraceEvent.method(1, 1, 1, "worker", EventType.METHOD_ENTER, "a.b.Service", "read");
        JsonNode json = new ObjectMapper().valueToTree(legacy);
        assertThat(json.has("threadId")).isFalse();
        assertThat(json.has("methodDescriptor")).isFalse();
        TraceEvent bounded = TraceEvent.builder(EventType.METHOD_ENTER).methodDescriptor("(" + "I".repeat(3000) + ")V").build();
        assertThat(bounded.getMethodDescriptor()).hasSize(2048);assertThat(bounded.isFieldsTruncated()).isTrue();
    }
}
