package com.chargebee.tracelens.spring;

import com.chargebee.tracelens.core.config.TraceLensConfig;
import com.chargebee.tracelens.core.event.EventType;
import com.chargebee.tracelens.core.event.TraceEvent;
import com.chargebee.tracelens.core.record.TraceSink;
import com.chargebee.tracelens.core.store.TraceStore;
import com.chargebee.tracelens.core.trace.EntryChannel;
import com.chargebee.tracelens.core.trace.Trace;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;

class CaptureLevelPropertiesTest {
    @Test void methodsScopeReachesSharedRecorderWithoutSuppressingBoundaryEvidence() {
        TraceLensProperties properties = new TraceLensProperties().setPackages(Collections.singletonList("example"))
                .setCaptureMode("methods").setDetailedCapturePaths(Collections.singletonList("/api"));
        TraceLensConfig config = new TraceLensAutoConfiguration().traceLensConfig(properties, new MockEnvironment());
        assertThat(config.getCaptureMode()).isEqualTo("methods");
        assertThat(config.effectiveCaptureMode("/other", System.currentTimeMillis())).isEqualTo("overview");
        try (TraceStore store = new TraceStore(config)) {
            Trace trace = new Trace("fixture", "developer", EntryChannel.HTTP, System.currentTimeMillis(), System.nanoTime());
            trace.putAttribute("path", "/api/order");
            assertThat(store.register(trace)).isTrue();
            TraceSink sink = new TraceSink(store);
            sink.onMethodEnter(trace.getSegmentId(), "example.Controller", "run");
            sink.onLine(trace.getSegmentId(), "example.Controller", 10);
            sink.onExternalCall(trace.getSegmentId(), "database", "select");
            sink.onMethodExit(trace.getSegmentId(), "example.Controller", "run");
            assertThat(trace.events()).extracting(TraceEvent::getType)
                    .containsExactly(EventType.METHOD_ENTER, EventType.EXTERNAL_CALL, EventType.METHOD_EXIT);
            assertThat(trace.isTruncated()).isFalse();
        }
    }
}
