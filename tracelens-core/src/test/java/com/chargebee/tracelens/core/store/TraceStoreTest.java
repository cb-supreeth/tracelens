package com.chargebee.tracelens.core.store;

import com.chargebee.tracelens.core.config.TraceLensConfig;
import com.chargebee.tracelens.core.event.TraceEvent;
import com.chargebee.tracelens.core.trace.EntryChannel;
import com.chargebee.tracelens.core.trace.Trace;
import com.chargebee.tracelens.core.trace.TraceStatus;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class TraceStoreTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000L);
    private final AtomicLong millis = new AtomicLong(10_000L);

    private TraceLensConfig config() {
        return new TraceLensConfig()
                .setApplicationId("cb-ems")
                .setPackages(java.util.List.of("com.chargebee.ems"))
                .setMaxTrackedRequests(3)
                .setMaxTrackedEvents(1_000)
                .setIdleTtlMinutes(10)
                .setEvictOnExport(true);
    }

    private TraceStore newStore(TraceLensConfig config) {
        return new TraceStore(config, nanos::get, millis::get);
    }

    private Trace trace(String lensId) {
        return new Trace(lensId, "me@chargebee.com", EntryChannel.HTTP,
                millis.get(), nanos.get());
    }

    private TraceEvent event() {
        return TraceEvent.line(0, millis.get(), 0L, "t", "com.example.Foo", 1);
    }

    @Test
    void registerAndGet() {
        try (TraceStore store = newStore(config())) {
            Trace trace = trace("lens-a");
            assertThat(store.register(trace)).isTrue();
            assertThat(store.get(trace.getTraceId())).contains(trace);
            assertThat(store.size()).isEqualTo(1);
        }
    }

    @Test
    void lruEvictsEldestAtRequestCap() {
        try (TraceStore store = newStore(config())) {
            Trace first = trace("lens-first");
            store.register(first);
            // touch "first" so it is NOT the LRU when the cap overflows
            store.appendEvent(first.getTraceId(), event());
            Trace second = trace("lens-second");
            store.register(second);
            Trace third = trace("lens-third");
            store.register(third);

            Trace fourth = trace("lens-fourth");
            assertThat(store.register(fourth)).isTrue();

            assertThat(store.size()).isEqualTo(3);
            assertThat(store.get(first.getTraceId())).isPresent(); // recently active
            assertThat(store.get(second.getTraceId())).isEmpty();  // eldest evicted
            assertThat(store.get(fourth.getTraceId())).isPresent();
        }
    }

    @Test
    void globalEventCeilingStopsAppends() {
        TraceLensConfig cfg = config().setMaxTrackedEvents(5);
        try (TraceStore store = newStore(cfg)) {
            Trace trace = trace("lens-a");
            store.register(trace);
            for (int i = 0; i < 5; i++) {
                assertThat(store.appendEvent(trace.getTraceId(), event())).isTrue();
            }
            assertThat(store.appendEvent(trace.getTraceId(), event())).isFalse();
            assertThat(store.totalEvents()).isEqualTo(5);
        }
    }

    @Test
    void registrationRejectedAtCapacityUntilEvictionFreesBudget() {
        TraceLensConfig cfg = config().setMaxTrackedRequests(10).setMaxTrackedEvents(4);
        try (TraceStore store = newStore(cfg)) {
            Trace full = trace("lens-full");
            store.register(full);
            for (int i = 0; i < 4; i++) {
                store.appendEvent(full.getTraceId(), event());
            }
            // at ceiling: registering a new trace evicts the LRU one to free budget
            Trace fresh = trace("lens-fresh");
            assertThat(store.register(fresh)).isTrue();
            assertThat(store.get(full.getTraceId())).isEmpty();
            assertThat(store.totalEvents()).isZero();
        }
    }

    @Test
    void sweepsIdleTracesOnly() {
        try (TraceStore store = newStore(config())) {
            Trace idle = trace("lens-idle");
            store.register(idle);

            nanos.addAndGet(TimeUnit.MINUTES.toNanos(11)); // past the 10-minute TTL

            Trace active = trace("lens-active");
            store.register(active); // registered "now"

            assertThat(store.sweepIdle()).isEqualTo(1);
            assertThat(store.get(idle.getTraceId())).isEmpty();
            assertThat(store.get(active.getTraceId())).isPresent();
        }
    }

    @Test
    void exportIsDestructiveByDefault() {
        try (TraceStore store = newStore(config())) {
            Trace trace = trace("lens-a");
            store.register(trace);
            store.appendEvent(trace.getTraceId(), event());

            assertThat(store.export(trace.getTraceId())).isPresent();
            assertThat(store.get(trace.getTraceId())).isEmpty();
            assertThat(store.totalEvents()).isZero();
        }
    }

    @Test
    void exportRetainsWhenEvictOnExportFalse() {
        try (TraceStore store = newStore(config().setEvictOnExport(false))) {
            Trace trace = trace("lens-a");
            store.register(trace);

            assertThat(store.export(trace.getTraceId())).isPresent();
            assertThat(store.get(trace.getTraceId())).isPresent();
            assertThat(store.get(trace.getTraceId()).get().getStatus()).isEqualTo(TraceStatus.EXPORTED);
        }
    }

    @Test
    void disabledRecordingRejectsRegistrations() {
        try (TraceStore store = newStore(config())) {
            store.setRecordingEnabled(false);
            assertThat(store.register(trace("lens-a"))).isFalse();
            assertThat(store.size()).isZero();
        }
    }

    @Test
    void findByLensIdReturnsAllMatches() {
        try (TraceStore store = newStore(config())) {
            store.register(trace("repro-1"));
            store.register(trace("repro-1"));
            store.register(trace("other"));
            assertThat(store.findByLensId("repro-1")).hasSize(2);
        }
    }

    @Test
    void resetAllClearsEverything() {
        try (TraceStore store = newStore(config())) {
            Trace trace = trace("lens-a");
            store.register(trace);
            store.appendEvent(trace.getTraceId(), event());
            assertThat(store.resetAll()).isEqualTo(1);
            assertThat(store.size()).isZero();
            assertThat(store.totalEvents()).isZero();
        }
    }
}
