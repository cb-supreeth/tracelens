package com.chargebee.tracelens.core.export;

import com.chargebee.tracelens.core.config.TraceLensConfig;
import com.chargebee.tracelens.core.event.TraceEvent;
import com.chargebee.tracelens.core.trace.EntryChannel;
import com.chargebee.tracelens.core.trace.Trace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TraceExporterTest {

    private final TraceLensConfig config = new TraceLensConfig()
            .setApplicationId("cb-ems")
            .setApplicationVersion("LOCAL")
            .setPackages(List.of("com.chargebee.ems"))
            .setContextParams(new TraceLensConfig.ContextParams()
                    .setJvmArgs(List.of("tracelens.test.marker", "tracelens.test.absent")));

    @Test
    void exportsMetadataAndOrderedTimeline() throws Exception {
        System.setProperty("tracelens.test.marker", "present-value");
        try {
            Trace trace = new Trace("lens-1", "me@chargebee.com", EntryChannel.HTTP, 1_000L, 0L);
            trace.putAttribute("siteId", "site-42");
            trace.addEvent(TraceEvent.line(0, 1_100L, 5L, "http-nio-8082", "com.example.Foo", 10));
            trace.addEvent(TraceEvent.line(0, 1_101L, 7L, "http-nio-8082", "com.example.Foo", 11));
            trace.complete(1_500L);

            String json = new TraceExporter(config).export(trace);
            JsonNode root = new ObjectMapper().readTree(json);

            assertThat(root.get("traceId").asText()).isEqualTo(trace.getTraceId());
            assertThat(root.get("lensId").asText()).isEqualTo("lens-1");
            assertThat(root.get("entryChannel").asText()).isEqualTo("HTTP");
            assertThat(root.get("applicationId").asText()).isEqualTo("cb-ems");
            assertThat(root.get("status").asText()).isEqualTo("COMPLETED");
            assertThat(root.get("durationMs").asLong()).isEqualTo(500L);
            assertThat(root.get("eventCount").asInt()).isEqualTo(2);
            assertThat(root.get("truncated").asBoolean()).isFalse();
            assertThat(root.at("/attributes/siteId").asText()).isEqualTo("site-42");
            assertThat(root.at("/contextParams/tracelens.test.marker").asText()).isEqualTo("present-value");
            assertThat(root.at("/contextParams/tracelens.test.absent").isMissingNode()).isTrue();

            JsonNode events = root.get("events");
            assertThat(events).hasSize(2);
            assertThat(events.get(0).get("seq").asLong()).isEqualTo(1L);
            assertThat(events.get(0).get("type").asText()).isEqualTo("LINE");
            assertThat(events.get(0).get("className").asText()).isEqualTo("com.example.Foo");
            assertThat(events.get(0).get("line").asInt()).isEqualTo(10);
            assertThat(events.get(1).get("line").asInt()).isEqualTo(11);
        } finally {
            System.clearProperty("tracelens.test.marker");
        }
    }

    @Test
    void omitsNullFieldsFromEvents() throws Exception {
        Trace trace = new Trace("lens-2", null, EntryChannel.SCHEDULER, 1_000L, 0L);
        trace.addEvent(TraceEvent.line(0, 1_100L, 5L, "scheduling-1", "com.example.Job", 42));

        JsonNode root = new ObjectMapper().readTree(new TraceExporter(config).export(trace));

        assertThat(root.get("user")).isNull(); // null user omitted
        JsonNode event = root.get("events").get(0);
        assertThat(event.has("methodName")).isFalse();
        assertThat(event.has("message")).isFalse();
    }
}
