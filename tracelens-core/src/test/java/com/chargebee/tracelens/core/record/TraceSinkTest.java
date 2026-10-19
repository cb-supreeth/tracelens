package com.chargebee.tracelens.core.record;

import com.chargebee.tracelens.core.config.TraceLensConfig;
import com.chargebee.tracelens.core.event.EventType;
import com.chargebee.tracelens.core.event.TraceEvent;
import com.chargebee.tracelens.core.store.TraceStore;
import com.chargebee.tracelens.core.trace.EntryChannel;
import com.chargebee.tracelens.core.trace.Trace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceSinkTest {

    private TraceStore store;
    private TraceSink sink;

    private Trace activeTrace() {
        store = new TraceStore(new TraceLensConfig()
                .setApplicationId("test").setPackages(List.of("a.b")));
        sink = new TraceSink(store);
        Trace trace = new Trace("lens", "user", EntryChannel.HTTP,
                System.currentTimeMillis(), System.nanoTime());
        store.register(trace);
        return trace;
    }

    private List<TraceEvent> eventsOf(Trace trace) {
        return store.get(trace.getSegmentId()).orElseThrow().events();
    }

    @Test
    void dbRouteEmitsOnChangeAndSuppressesRepeats() {
        Trace trace = activeTrace();
        String seg = trace.getSegmentId();

        sink.onDbRoute(seg, "LIVE_MASTER_RLS");
        sink.onDbRoute(seg, "LIVE_MASTER_RLS"); // repeat — suppressed
        sink.onDbRoute(seg, "LIVE_SLAVE_RLS");  // change — emitted
        sink.onDbRoute(seg, "LIVE_SLAVE_RLS");  // repeat — suppressed
        sink.onDbRoute(seg, "LIVE_MASTER_RLS"); // change back — emitted

        List<TraceEvent> routeEvents = eventsOf(trace).stream()
                .filter(e -> e.getType() == EventType.DB_ROUTE).toList();
        assertThat(routeEvents).hasSize(3);
        assertThat(routeEvents.get(0).getAttributes()).containsEntry("route", "LIVE_MASTER_RLS");
        assertThat(routeEvents.get(1).getAttributes()).containsEntry("route", "LIVE_SLAVE_RLS");
        assertThat(routeEvents.get(2).getAttributes()).containsEntry("route", "LIVE_MASTER_RLS");
    }

    @Test
    void externalCallCarriesSystemAndDetail() {
        Trace trace = activeTrace();
        sink.onExternalCall(trace.getSegmentId(), "ClickhouseQueryExecutor", "executeQueryAsync");

        List<TraceEvent> external = eventsOf(trace).stream()
                .filter(e -> e.getType() == EventType.EXTERNAL_CALL).toList();
        assertThat(external).hasSize(1);
        assertThat(external.get(0).getAttributes())
                .containsEntry("system", "ClickhouseQueryExecutor")
                .containsEntry("detail", "executeQueryAsync");
    }

    @Test
    void messagingBoundaryRetainsMetadataAsBoundedExternalEvent() {
        Trace trace = activeTrace();
        Map<String, String> attrs = Map.of("boundaryKind", "messaging", "system", "kafka",
                "direction", "publish", "destination", "billing-events", "operation", "send",
                "status", "SUBMITTED", "evidenceKind", "client-boundary");
        sink.onBoundary(trace.getSegmentId(), attrs);
        assertThat(eventsOf(trace)).hasSize(1);
        assertThat(eventsOf(trace).get(0).getType()).isEqualTo(EventType.EXTERNAL_CALL);
        assertThat(eventsOf(trace).get(0).getAttributes()).containsAllEntriesOf(attrs);
    }
}
