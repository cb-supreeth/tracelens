package com.chargebee.tracelens.core.export;

import com.chargebee.tracelens.core.config.TraceLensConfig;
import com.chargebee.tracelens.core.event.EventType;
import com.chargebee.tracelens.core.event.TraceEvent;
import com.chargebee.tracelens.core.record.TraceSink;
import com.chargebee.tracelens.core.store.TraceStore;
import com.chargebee.tracelens.core.trace.EntryChannel;
import com.chargebee.tracelens.core.trace.Trace;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LargeTraceProjectionTest {
    private final TraceLensConfig config = new TraceLensConfig().setApplicationId("large-test").setHttpRetentionPercent(0);
    private final TraceExporter exporter = new TraceExporter(config);
    private final TraceProjection projection = new TraceProjection(exporter);
    private Trace trace() {
        Trace result = new Trace("test", "user", EntryChannel.HTTP, 1000, 0);
        result.putAttribute("serviceInstanceId", "instance-1");
        return result;
    }
    private TraceEvent line() { return TraceEvent.line(0, 1000, 1, "worker", "example.Class", 3); }
    private TraceEvent boundary() {
        return TraceEvent.builder(EventType.EXTERNAL_CALL).attr("boundaryKind", "database")
                .attr("system", "postgresql").attr("operation", "executeQuery").attr("direction", "outbound")
                .attr("evidenceKind", "jdbc-execution").attr("sql", "select private_literal from table_name")
                .attr("sqlParameters", "PRIVATE_PARAMETERS").attr("durationMs", "3").build();
    }

    @Test void oversizedFullExportStillHasSmallCompleteOverviewWithOriginalAnchors() throws Exception {
        Trace trace = trace();
        String clazz = "example." + String.join("", Collections.nCopies(700, "A"));
        for (int i = 0; i < 12000; i++) assertThat(trace.addEvent(TraceEvent.line(0, 1000, i, "worker", clazz, i))).isTrue();
        trace.addEvent(boundary());
        trace.putAttribute("httpRequestBody", "PRIVATE_HTTP_BODY");
        trace.putAttribute("httpQueryParameters", "PRIVATE_HTTP_QUERY");
        assertThat(exporter.export(trace).getBytes(StandardCharsets.UTF_8).length).isGreaterThan(8 * 1024 * 1024);
        ObjectNode overview = projection.overview(trace);
        assertThat(TraceProjection.serialize(overview).getBytes(StandardCharsets.UTF_8).length).isLessThan(8192);
        assertThat(overview.path("events")).hasSize(1);
        assertThat(overview.at("/events/0/eventIndex").asInt()).isEqualTo(12000);
        assertThat(overview.at("/events/0/seq").asInt()).isEqualTo(12001);
        assertThat(overview.toString()).doesNotContain("PRIVATE_HTTP_BODY", "PRIVATE_HTTP_QUERY", "PRIVATE_PARAMETERS", "\"sql\"");
        assertThat(overview.at("/events/0/attributes/sqlDetailsAvailable").asBoolean()).isTrue();
        assertThat(projection.requestData(trace).toString()).contains("PRIVATE_HTTP_BODY");
    }

    @Test void pagesUseStableHighWaterAndMethodsKeepTheirOriginalIndexes() throws Exception {
        Trace trace = trace();
        trace.addEvent(line());
        trace.addEvent(TraceEvent.method(0, 1000, 2, "worker", EventType.METHOD_ENTER, "example.Class", "run"));
        trace.addEvent(boundary());
        ObjectNode page = projection.events(trace, TraceProjection.query("limit=1&maxBytes=4096&kind=methods"));
        assertThat(page.path("through").asInt()).isEqualTo(3);
        assertThat(page.at("/events/0/eventIndex").asInt()).isEqualTo(1);
        assertThat(page.path("hasMore").asBoolean()).isTrue();
        trace.addEvent(line());
        ObjectNode next = projection.events(trace, TraceProjection.query("after=2&through=3&kind=methods"));
        assertThat(next.path("events")).hasSize(1);
        assertThat(next.path("nextAfter").asInt()).isEqualTo(3);
        assertThat(next.path("hasMore").asBoolean()).isFalse();
        assertThat(TraceProjection.serialize(page).getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(4096);
        assertThatThrownBy(() -> projection.events(trace, TraceProjection.query("through=99"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> projection.events(trace, TraceProjection.query("maxBytes=1048577"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TraceProjection.query("after=0&after=1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void oversizedEventHasExplicitWarningAndMakesForwardProgress() {
        Trace trace = trace();
        trace.addEvent(TraceEvent.builder(EventType.EXTERNAL_CALL).attr("large", String.join("", Collections.nCopies(16000, "x"))).build());
        trace.addEvent(line());
        ObjectNode page = projection.events(trace, TraceProjection.query("maxBytes=4096"));
        assertThat(page.path("partial").asBoolean()).isTrue();
        assertThat(page.path("warnings").toString()).contains("oversized");
        assertThat(page.path("nextAfter").asInt()).isEqualTo(2);
        assertThat(page.at("/events/0/seq").asInt()).isEqualTo(2);
        assertThat(page.at("/events/0/detailGapBefore").asBoolean()).isTrue();
    }

    @Test void oversizedEventMarksOnlyTheFirstRetainedRowAndPagesStayWithinByteBudget() throws Exception {
        Trace trace = trace();
        trace.addEvent(TraceEvent.builder(EventType.EXTERNAL_CALL).attr("large", String.join("", Collections.nCopies(16000, "x"))).build());
        for (int i = 0; i < 400; i++) assertThat(trace.addEvent(line())).isTrue();
        ObjectNode page = projection.events(trace, TraceProjection.query("limit=5000&maxBytes=8192"));
        assertThat(TraceProjection.serialize(page).getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(8192);
        assertThat(page.path("partial").asBoolean()).isTrue();
        assertThat(page.path("hasMore").asBoolean()).isTrue();
        assertThat(page.path("events").size()).isGreaterThan(2);
        assertThat(page.at("/events/0/seq").asInt()).isEqualTo(2);
        assertThat(page.at("/events/0/detailGapBefore").asBoolean()).isTrue();
        for (int i = 1; i < page.path("events").size(); i++)
            assertThat(page.path("events").get(i).path("detailGapBefore").asBoolean()).isFalse();
        assertThat(page.path("nextAfter").asLong()).isEqualTo(page.path("events").get(page.path("events").size() - 1).path("seq").asLong());
        assertThat(page.path("warnings").toString()).contains("1 oversized");
    }

    @Test void bytePressurePreservesBoundaryAndCompletionAndReportsPerSegmentReason() {
        TraceLensConfig small = new TraceLensConfig().setApplicationId("test").setHttpRetentionPercent(0)
                .setMaxSegmentBytes(1048576).setMaxTrackedBytes(1048576);
        try (TraceStore store = new TraceStore(small)) {
            Trace trace = trace();
            assertThat(store.register(trace)).isTrue();
            for (int i = 0; i < 2000; i++) store.appendEvent(trace.getSegmentId(), line());
            assertThat(trace.isTruncated()).isTrue();
            assertThat(trace.dropReasons()).containsKey("store-byte-budget");
            assertThat(store.appendEvent(trace.getSegmentId(), boundary())).isTrue();
            assertThat(trace.events().get(trace.eventCount() - 1).isDetailGapBefore()).isTrue();
            assertThat(trace.omittedDetailCallbacks()).isPositive();
            trace.complete(2000);
            assertThat(trace.getStatus().name()).isEqualTo("COMPLETED");
            assertThat(trace.retainedBytes()).isLessThanOrEqualTo(1048576);
            assertThat(((Number) store.retentionSummary().get("estimatedRetainedBytes")).longValue()).isLessThanOrEqualTo(1048576);
        }
    }

    @Test void overviewModeAndPathTimeWindowOmitOnlyDetail() {
        AtomicLong clock = new AtomicLong(1000);
        TraceLensConfig selective = new TraceLensConfig().setCaptureMode("overview")
                .setDetailedCapturePaths(Collections.singletonList("/investigate"))
                .setDetailedCaptureUntilEpochMillis(1100);
        assertThat(selective.detailedCapture("/investigate", 1000)).isTrue();
        assertThat(selective.detailedCapture("/investigate-other", 1000)).isFalse();
        assertThat(selective.detailedCapture("/investigate/order", 1101)).isFalse();
        TraceLensConfig trailingSlash = new TraceLensConfig().setCaptureMode("overview")
                .setDetailedCapturePaths(Collections.singletonList("/investigate/"));
        assertThat(trailingSlash.detailedCapture("/investigate/order", 1000)).isTrue();
        assertThat(trailingSlash.detailedCapture("/investigate-other", 1000)).isFalse();
        try (TraceStore store = new TraceStore(selective, () -> 0, clock::get)) {
            Trace overview = trace(); overview.putAttribute("path", "/other"); store.register(overview);
            Trace detail = trace(); detail.putAttribute("path", "/investigate/order"); store.register(detail);
            TraceSink sink = new TraceSink(store);
            sink.onLine(overview.getSegmentId(), "example.Class", 1);
            sink.onLine(detail.getSegmentId(), "example.Class", 1);
            assertThat(overview.eventCount()).isZero();
            assertThat(detail.eventCount()).isEqualTo(1);
            clock.set(1101);
            sink.onLine(detail.getSegmentId(), "example.Class", 2);
            assertThat(detail.eventCount()).isEqualTo(1);
            assertThat(detail.dropReasons()).containsKey("detailed-capture-window-ended");
            assertThat(store.appendEvent(overview.getSegmentId(), boundary())).isTrue();
            assertThat(store.appendEvent(detail.getSegmentId(), boundary())).isTrue();
            assertThat(detail.events().get(1).isDetailGapBefore()).isTrue();
            assertThat(detail.omittedDetailCallbacks()).isEqualTo(1);
        }
    }

    @Test void configRejectsUnboundedBudgetsAndAmbiguousPaths() {
        assertThat(new TraceLensConfig().setApplicationId("test").validate(true)).isEmpty();
        assertThat(new TraceLensConfig().setApplicationId("test").setMaxSegmentBytes(1).validate(true)).isNotEmpty();
        assertThat(new TraceLensConfig().setApplicationId("test").setCaptureMode("all").validate(true)).isNotEmpty();
        assertThat(new TraceLensConfig().setApplicationId("test").setDetailedCapturePaths(Collections.singletonList("/*")).validate(true)).isNotEmpty();
    }

    @Test void rejectedCriticalEventMarksGapBeforeSmallerBoundaryAtBothAdmissionLayers() {
        TraceEvent.Builder large = TraceEvent.builder(EventType.EXCEPTION).message("synthetic");
        String value = String.join("", Collections.nCopies(16000, "x"));
        for (int i = 0; i < 32; i++) large.attr("field" + i, value);
        TraceEvent exception = large.build();
        Trace direct = trace();
        direct.configureCapture(true, 0, 1048576);
        assertThat(direct.addEvent(TraceEvent.method(0, 1000, 1, "worker", EventType.METHOD_ENTER, "example.Class", "outer"))).isTrue();
        assertThat(direct.addEvent(exception)).isFalse();
        assertThat(direct.addEvent(boundary())).isTrue();
        assertThat(direct.events().get(1).getSeq()).isEqualTo(2);
        assertThat(direct.events().get(1).isDetailGapBefore()).isTrue();
        assertThat(direct.omittedDetailCallbacks()).isZero();
        assertThat(direct.dropReasons()).containsKey("segment-byte-budget");

        try (TraceStore store = new TraceStore(new TraceLensConfig().setHttpRetentionPercent(0)
                .setMaxSegmentBytes(1048576).setMaxTrackedBytes(1048576))) {
            Trace retained = trace(); store.register(retained);
            store.appendEvent(retained.getSegmentId(), TraceEvent.method(0, 1000, 1, "worker", EventType.METHOD_ENTER, "example.Class", "outer"));
            assertThat(store.appendEvent(retained.getSegmentId(), exception)).isFalse();
            assertThat(store.appendEvent(retained.getSegmentId(), boundary())).isTrue();
            assertThat(projection.events(retained, Collections.emptyMap()).at("/events/1/detailGapBefore").asBoolean()).isTrue();
            assertThat(retained.omittedDetailCallbacks()).isZero();
            assertThat(retained.dropReasons()).containsKey("store-byte-budget");
        }
    }

    @Test void escapedMetadataCannotExceedOverviewByteLimitOrHideTheSegment() throws Exception {
        Trace trace = trace();
        String escaped = String.join("", Collections.nCopies(512, "\u0001"));
        for (int i = 0; i < 64; i++) {
            Map<String, String> call = new java.util.LinkedHashMap<>();
            call.put("spanId", "span-" + i);
            for (int j = 0; j < 24; j++) call.put("field-" + j, escaped);
            trace.recordHttpCall(call);
        }
        String attribute = String.join("", Collections.nCopies(16000, "\u0001"));
        trace.putAttribute("path", attribute);
        trace.putAttribute("httpMethod", attribute);
        assertThat(TraceProjection.serialize(exporter.metadata(trace)).getBytes(StandardCharsets.UTF_8).length).isGreaterThan(TraceProjection.MAX_PAGE_BYTES);
        ObjectNode overview = projection.overview(trace);
        assertThat(TraceProjection.serialize(overview).getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(TraceProjection.MAX_PAGE_BYTES);
        assertThat(overview.path("segmentId").asText()).isEqualTo(trace.getSegmentId());
        assertThat(overview.path("overviewMetadataTruncated").asBoolean()).isTrue();
        assertThat(overview.path("httpCallsTruncated").asBoolean()).isTrue();
        assertThat(overview.path("partial").asBoolean()).isTrue();
        assertThat(overview.path("warnings").toString()).contains("metadata");

        TraceExporter hugeMetadata = new TraceExporter(new TraceLensConfig().setApplicationId("test")
                .setApplicationVersion(String.join("", Collections.nCopies(TraceProjection.MAX_PAGE_BYTES, "x"))));
        ObjectNode fallback = new TraceProjection(hugeMetadata).overview(trace());
        assertThat(TraceProjection.serialize(fallback).getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(TraceProjection.MAX_PAGE_BYTES);
        assertThat(fallback.path("segmentId").asText()).isNotEmpty();
        assertThat(fallback.path("partial").asBoolean()).isTrue();
    }

    @Test void fullAttributeBudgetCannotPreventOrReplaceInstanceIdentity() {
        Trace trace = new Trace("full-attributes", "user", EntryChannel.HTTP, 1000, 0);
        for (int i = 0; i < 64; i++) trace.putAttribute("field" + i, "value");
        try (TraceStore store = new TraceStore(config)) {
            assertThat(store.register(trace)).isTrue();
            String instance = trace.getAttributes().get("serviceInstanceId");
            assertThat(instance).isNotBlank();
            assertThat(trace.getAttributes()).hasSize(64);
            assertThat(projection.overview(trace).path("serviceInstanceId").asText()).isEqualTo(instance);
            assertThat(projection.events(trace, Collections.emptyMap()).path("serviceInstanceId").asText()).isEqualTo(instance);
            assertThat(projection.requestData(trace).path("serviceInstanceId").asText()).isEqualTo(instance);
            trace.putAttribute("serviceInstanceId", "replacement");
            assertThat(trace.getAttributes().get("serviceInstanceId")).isEqualTo(instance);
            assertThat(trace.dropReasons()).containsKey("service-instance-change-rejected");
        }
    }
}
