package com.chargebee.tracelens.core.store;

import com.chargebee.tracelens.core.event.EventType;
import com.chargebee.tracelens.core.event.TraceEvent;
import com.chargebee.tracelens.core.trace.EntryChannel;
import com.chargebee.tracelens.core.trace.Trace;
import com.chargebee.tracelens.core.trace.TraceStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestSummaryTest {

    private Trace trace() {
        return new Trace("trace-1", "auto:GET /features/123", "alice_42", EntryChannel.HTTP,
                1_000L, 5_000L, 100);
    }

    @Test
    void exposesCapturedRequestMetadataWithoutInferringValues() {
        Trace trace = trace();
        trace.putAttribute("siteId", "42");
        trace.putAttribute("route", "/features/{id}");
        trace.putAttribute("filterException", "java.lang.IllegalStateException");
        trace.putAttribute("asyncTimeout", "true");

        TraceSummary summary = new TraceSummary(trace);

        assertThat(summary.getSiteId()).isEqualTo("42");
        assertThat(summary.getRouteTemplate()).isEqualTo("/features/{id}");
        assertThat(summary.getFilterException()).isEqualTo("java.lang.IllegalStateException");
        assertThat(summary.getAsyncTimeout()).isEqualTo("true");
    }

    @Test
    void missingMetadataRemainsUnknownEvenWhenUserAndPathLookRecognizable() {
        Trace trace = trace();
        trace.putAttribute("path", "/features/123");

        TraceSummary summary = new TraceSummary(trace);

        assertThat(summary.getUser()).isEqualTo("alice_42");
        assertThat(summary.getSiteId()).isNull();
        assertThat(summary.getRouteTemplate()).isNull();
        assertThat(summary.getFilterException()).isNull();
        assertThat(summary.getAsyncTimeout()).isNull();
    }

    @Test
    void caughtExceptionEventsDoNotBecomeRequestFailureMetadata() {
        Trace trace = trace();
        trace.addEvent(TraceEvent.builder(EventType.EXCEPTION)
                .className("com.example.Features").methodName("recover")
                .attr("exceptionType", "java.lang.IllegalStateException").build());
        trace.putAttribute("httpStatus", "200");
        trace.complete(1_025L);

        TraceSummary summary = new TraceSummary(trace);

        assertThat(summary.getEventCount()).isEqualTo(1);
        assertThat(summary.getStatus()).isEqualTo(TraceStatus.COMPLETED);
        assertThat(summary.getHttpStatus()).isEqualTo("200");
        assertThat(summary.getFilterException()).isNull();
        assertThat(summary.getAsyncTimeout()).isNull();
    }

    @Test
    void serializesAdditiveFieldsAsMetadataWithUnknownValuesLeftNull() {
        Trace trace = trace();
        trace.putAttribute("siteId", "42");
        trace.putAttribute("route", "/features/{id}");
        trace.putAttribute("asyncTimeout", "false");

        JsonNode json = new ObjectMapper().valueToTree(new TraceSummary(trace));

        assertThat(json.get("siteId").asText()).isEqualTo("42");
        assertThat(json.get("routeTemplate").asText()).isEqualTo("/features/{id}");
        assertThat(json.get("asyncTimeout").asText()).isEqualTo("false");
        assertThat(json.get("filterException").isNull()).isTrue();
        assertThat(json.has("events")).isFalse();
    }
}
