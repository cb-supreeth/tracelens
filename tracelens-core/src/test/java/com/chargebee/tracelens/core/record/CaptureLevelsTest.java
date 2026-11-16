package com.chargebee.tracelens.core.record;

import com.chargebee.tracelens.core.config.TraceLensConfig;
import com.chargebee.tracelens.core.config.TraceLensConfigLoader;
import com.chargebee.tracelens.core.event.EventType;
import com.chargebee.tracelens.core.event.TraceEvent;
import com.chargebee.tracelens.core.export.TraceExporter;
import com.chargebee.tracelens.core.export.TraceProjection;
import com.chargebee.tracelens.core.store.TraceStore;
import com.chargebee.tracelens.core.store.TraceSummary;
import com.chargebee.tracelens.core.trace.EntryChannel;
import com.chargebee.tracelens.core.trace.Trace;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class CaptureLevelsTest {
    @TempDir Path directory;

    private TraceLensConfig config(String mode) {
        return new TraceLensConfig().setApplicationId("capture-level-test").setCaptureMode(mode)
                .setPackages(Collections.singletonList("example")).setHttpRetentionPercent(0);
    }

    private Trace trace(String path) {
        Trace trace = new Trace("fixture", "developer", EntryChannel.HTTP, 1000, 0);
        trace.putAttribute("path", path);
        return trace;
    }

    @ParameterizedTest
    @ValueSource(strings = {"overview", "methods", "detailed"})
    void captureLevelOnlyControlsMethodAndLineEvidence(String mode) throws Exception {
        TraceLensConfig config = config(mode);
        assertThat(config.validate()).isEmpty();
        Path file = directory.resolve(mode + ".json");
        Files.write(file, ("{\"applicationId\":\"test\",\"packages\":[\"example\"],\"captureMode\":\"" + mode + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        assertThat(new TraceLensConfigLoader().load(file).getCaptureMode()).isEqualTo(mode);
        try (TraceStore store = new TraceStore(config)) {
            Trace trace = trace("/api");
            assertThat(store.register(trace)).isTrue();
            TraceSink sink = new TraceSink(store);
            String id = trace.getSegmentId();
            sink.onMethodEnter(id, "example.Controller", "run");
            sink.onLine(id, "example.Controller", 10);
            sink.onExternalCall(id, "database", "select");
            sink.onException(id, "example.Controller", "run", "FixtureException", "synthetic");
            sink.onMethodExit(id, "example.Controller", "run");
            assertThat(trace.events()).extracting(TraceEvent::getType)
                    .contains(EventType.EXTERNAL_CALL, EventType.EXCEPTION);
            if ("overview".equals(mode)) {
                assertThat(trace.events()).extracting(TraceEvent::getType)
                        .containsExactly(EventType.EXTERNAL_CALL, EventType.EXCEPTION);
            } else {
                assertThat(trace.events()).extracting(TraceEvent::getType)
                        .contains(EventType.METHOD_ENTER, EventType.METHOD_EXIT);
                assertThat(trace.events().stream().anyMatch(event -> event.getType() == EventType.LINE))
                        .isEqualTo("detailed".equals(mode));
                assertThat(trace.isTruncated()).isFalse();
                assertThat(trace.omittedDetailCallbacks()).isZero();
                assertThat(trace.dropReasons()).isEmpty();
                assertThat(trace.events()).noneMatch(TraceEvent::isDetailGapBefore);
            }
            assertThat(new TraceSummary(trace).getCaptureMode()).isEqualTo(mode);
            TraceExporter exporter = new TraceExporter(config);
            assertThat(exporter.toJson(trace).path("captureMode").asText()).isEqualTo(mode);
            TraceProjection projection = new TraceProjection(exporter);
            assertThat(projection.overview(trace).path("codeDetailAvailable").asBoolean()).isEqualTo(!"overview".equals(mode));
            ObjectNode page = projection.events(trace, Collections.emptyMap());
            assertThat(page.path("captureMode").asText()).isEqualTo(mode);
            assertThat(page.path("partial").asBoolean()).isEqualTo("overview".equals(mode));
            ObjectNode capabilities = exporter.toJson(trace).objectNode();
            TraceProjection.capabilities(capabilities, config);
            assertThat(capabilities.path("methodCaptureEnabled").asBoolean()).isEqualTo(!"overview".equals(mode));
            assertThat(capabilities.path("lineCaptureEnabled").asBoolean()).isEqualTo("detailed".equals(mode));
        }
    }

    @Test
    void methodsScopeAndWindowFallBackToOverviewWithoutPromotingLines() {
        AtomicLong clock = new AtomicLong(1000);
        TraceLensConfig config = config("methods").setDetailedCapturePaths(Collections.singletonList("/api"))
                .setDetailedCaptureUntilEpochMillis(1100);
        assertThat(config.effectiveCaptureMode("/api", 1100)).isEqualTo("methods");
        assertThat(config.effectiveCaptureMode("/api/subpath", 1000)).isEqualTo("methods");
        assertThat(config.effectiveCaptureMode("/api-other", 1000)).isEqualTo("overview");
        assertThat(config.effectiveCaptureMode(null, 1000)).isEqualTo("overview");
        assertThat(config.effectiveCaptureMode("/api", 1101)).isEqualTo("overview");
        assertThat(config.detailedCapture("/api", 1000)).isFalse();
        try (TraceStore store = new TraceStore(config, () -> 0, clock::get)) {
            Trace selected = trace("/api/order"), other = trace("/api-other");
            store.register(selected); store.register(other);
            TraceSink sink = new TraceSink(store);
            sink.onMethodEnter(selected.getSegmentId(), "example.Controller", "run");
            sink.onLine(selected.getSegmentId(), "example.Controller", 10);
            sink.onMethodEnter(other.getSegmentId(), "example.Controller", "run");
            assertThat(selected.eventCount()).isEqualTo(1);
            assertThat(selected.isTruncated()).isFalse();
            assertThat(other.eventCount()).isZero();
            assertThat(other.captureMode()).isEqualTo("overview");
            clock.set(1101);
            sink.onMethodExit(selected.getSegmentId(), "example.Controller", "run");
            sink.onExternalCall(selected.getSegmentId(), "database", "select");
            assertThat(selected.captureMode()).isEqualTo("overview");
            assertThat(selected.dropReasons()).containsKey("detailed-capture-window-ended");
            assertThat(selected.events()).extracting(TraceEvent::getType)
                    .containsExactly(EventType.METHOD_ENTER, EventType.EXTERNAL_CALL);
            assertThat(selected.events().get(1).isDetailGapBefore()).isTrue();
            Trace late = trace("/api/order"); store.register(late);
            assertThat(late.captureMode()).isEqualTo("overview");
        }
    }

    @Test
    void directAndStoreAdmissionsRejectLinesWithoutConsumingMethodsBudget() {
        Trace direct = trace("/api");
        direct.configureCapture("methods", 0, 1048576);
        TraceEvent line = TraceEvent.line(0, 1000, 1, "worker", "example.Controller", 10);
        TraceEvent method = TraceEvent.method(0, 1000, 1, "worker", EventType.METHOD_ENTER, "example.Controller", "run");
        assertThat(direct.addEvent(line)).isFalse();
        assertThat(direct.addEvent(method)).isTrue();
        assertThat(direct.isTruncated()).isFalse();
        assertThat(direct.omittedDetailCallbacks()).isZero();
        try (TraceStore store = new TraceStore(config("methods").setMaxTrackedEvents(10))) {
            Trace trace = trace("/api"); store.register(trace);
            long retained = trace.retainedBytes();
            for (int i = 0; i < 100; i++) assertThat(store.appendEvent(trace.getSegmentId(), line)).isFalse();
            assertThat(trace.retainedBytes()).isEqualTo(retained);
            assertThat(store.totalEvents()).isZero();
            for (int i = 0; i < 9; i++) assertThat(store.appendEvent(trace.getSegmentId(), method)).isTrue();
            assertThat(store.appendEvent(trace.getSegmentId(), method)).isFalse();
            assertThat(trace.dropReasons()).containsKey("store-event-budget");
            assertThat(store.appendEvent(trace.getSegmentId(), TraceEvent.builder(EventType.EXCEPTION).build())).isTrue();
            assertThat(store.totalEvents()).isEqualTo(10);
        }
    }

    @Test
    void methodsStillRespectByteBudgetAndReserveBoundaryEvidence() {
        try (TraceStore store = new TraceStore(config("methods").setMaxSegmentBytes(1048576).setMaxTrackedBytes(1048576))) {
            Trace trace = trace("/api"); store.register(trace);
            TraceSink sink = new TraceSink(store);
            for (int i = 0; i < 2000; i++) sink.onMethodEnter(trace.getSegmentId(), "example.Controller", "run");
            assertThat(trace.dropReasons()).containsKey("store-byte-budget");
            sink.onExternalCall(trace.getSegmentId(), "database", "select");
            assertThat(trace.events().get(trace.eventCount() - 1).getType()).isEqualTo(EventType.EXTERNAL_CALL);
            assertThat(trace.retainedBytes()).isLessThanOrEqualTo(1048576);
        }
    }
}
