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
        return trace(lensId, EntryChannel.HTTP);
    }

    private Trace trace(String lensId, EntryChannel channel) {
        return new Trace(lensId, "me@chargebee.com", channel,
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
        TraceLensConfig cfg = config().setMaxTrackedEvents(5).setHttpRetentionPercent(0);
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

    @Test
    void schedulerChurnCannotEvictHttpWithinItsReservedShare() {
        try (TraceStore store = newStore(config().setMaxTrackedRequests(4))) {
            Trace request = trace("demo");
            store.register(request);
            for (int i = 0; i < 200; i++) {
                nanos.incrementAndGet();
                assertThat(store.register(trace("job-" + i, EntryChannel.SCHEDULER))).isTrue();
            }
            assertThat(store.get(request.getSegmentId())).contains(request);
            assertThat(store.size()).isEqualTo(4);
            assertThat(store.retentionSummary()).containsEntry("httpRequests", 1)
                    .containsEntry("backgroundRequests", 3);
        }
    }

    @Test
    void eachChannelReclaimsBorrowedSlotsWithoutStarvingTheOther() {
        try (TraceStore store = newStore(config().setMaxTrackedRequests(4))) {
            for (int i = 0; i < 4; i++) store.register(trace("job-" + i, EntryChannel.SCHEDULER));
            for (int i = 0; i < 20; i++) assertThat(store.register(trace("http-" + i))).isTrue();
            assertThat(store.retentionSummary()).containsEntry("httpRequests", 3)
                    .containsEntry("backgroundRequests", 1);
        }
        try (TraceStore store = newStore(config().setMaxTrackedRequests(4))) {
            for (int i = 0; i < 4; i++) store.register(trace("http-" + i));
            for (int i = 0; i < 20; i++) assertThat(store.register(trace("job-" + i, EntryChannel.SCHEDULER))).isTrue();
            assertThat(store.retentionSummary()).containsEntry("httpRequests", 3)
                    .containsEntry("backgroundRequests", 1);
        }
    }

    @Test
    void channelEventBudgetsProtectHttpAndLeaveBackgroundHeadroom() {
        try (TraceStore store = newStore(config().setMaxTrackedRequests(4).setMaxTrackedEvents(1000))) {
            Trace http = trace("demo");
            store.register(http);
            for (int i = 0; i < 675; i++) assertThat(store.appendEvent(http.getSegmentId(), event())).isTrue();
            for (int i = 0; i < 75; i++) assertThat(store.appendEvent(http.getSegmentId(), TraceEvent.marker(0, 0, 0, "test", "boundary"))).isTrue();
            assertThat(store.appendEvent(http.getSegmentId(), event())).isFalse();
            Trace job = trace("job", EntryChannel.SCHEDULER);
            store.register(job);
            for (int i = 0; i < 225; i++) assertThat(store.appendEvent(job.getSegmentId(), event())).isTrue();
            for (int i = 0; i < 25; i++) assertThat(store.appendEvent(job.getSegmentId(), TraceEvent.marker(0, 0, 0, "test", "boundary"))).isTrue();
            assertThat(store.appendEvent(job.getSegmentId(), event())).isFalse();
            assertThat(store.totalEvents()).isEqualTo(1000);
            for (int i = 0; i < 20; i++) {
                Trace fresh = trace("fresh-" + i, EntryChannel.SCHEDULER);
                assertThat(store.register(fresh)).isTrue();
                for (int j = 0; j < 225; j++) store.appendEvent(fresh.getSegmentId(), event());
                for (int j = 0; j < 25; j++) store.appendEvent(fresh.getSegmentId(), TraceEvent.marker(0, 0, 0, "test", "boundary"));
                assertThat(store.get(http.getSegmentId())).contains(http);
                assertThat(http.eventCount()).isEqualTo(750);
                assertThat(store.totalEvents()).isLessThanOrEqualTo(1000);
            }
            assertThat(store.retentionSummary()).containsEntry("httpEvents", 750L)
                    .containsEntry("backgroundEvents", 250L).containsEntry("budgetDroppedEvents", 2L);
        }
    }

    @Test
    void backgroundCannotConsumeHttpEventReserveEvenBeforeFirstRequest() {
        try (TraceStore store = newStore(config().setMaxTrackedEvents(20))) {
            Trace job = trace("job", EntryChannel.SCHEDULER);
            store.register(job);
            for (int i = 0; i < 5; i++) assertThat(store.appendEvent(job.getSegmentId(), event())).isTrue();
            assertThat(store.appendEvent(job.getSegmentId(), event())).isFalse();
            Trace http = trace("http");
            store.register(http);
            for (int i = 0; i < 14; i++) assertThat(store.appendEvent(http.getSegmentId(), event())).isTrue();
            assertThat(store.appendEvent(http.getSegmentId(), TraceEvent.marker(0, 0, 0, "test", "boundary"))).isTrue();
            assertThat(store.totalEvents()).isEqualTo(20);
            assertThat(store.get(job.getSegmentId())).isPresent();
        }
    }

    @Test
    void newHttpCanReplaceOlderHttpAtItsEventBudgetButNotBackground() {
        try (TraceStore store = newStore(config().setMaxTrackedRequests(5).setMaxTrackedEvents(8))) {
            Trace old = trace("old"), job = trace("job", EntryChannel.SCHEDULER);
            store.register(old);
            store.register(job);
            for (int i = 0; i < 6; i++) store.appendEvent(old.getSegmentId(), event());
            store.appendEvent(job.getSegmentId(), event());
            Trace fresh = trace("fresh");
            assertThat(store.register(fresh)).isTrue();
            assertThat(store.get(old.getSegmentId())).isEmpty();
            assertThat(store.get(job.getSegmentId())).contains(job);
            assertThat(store.totalEvents()).isEqualTo(1);
        }
    }

    @Test
    void oneSlotPrefersHttpAndTwoSlotsKeepBothChannels() {
        try (TraceStore store = newStore(config().setMaxTrackedRequests(1))) {
            assertThat(store.register(trace("job", EntryChannel.SCHEDULER))).isTrue();
            Trace http = trace("http");
            assertThat(store.register(http)).isTrue();
            assertThat(store.register(trace("job2", EntryChannel.SCHEDULER))).isFalse();
            assertThat(store.get(http.getSegmentId())).contains(http);
        }
        try (TraceStore store = newStore(config().setMaxTrackedRequests(2))) {
            Trace http = trace("http");
            store.register(http);
            store.register(trace("job", EntryChannel.SCHEDULER));
            store.register(trace("job2", EntryChannel.SCHEDULER));
            assertThat(store.get(http.getSegmentId())).contains(http);
            assertThat(store.retentionSummary()).containsEntry("httpRequests", 1)
                    .containsEntry("backgroundRequests", 1);
        }
    }

    @Test
    void reservationsDoNotPreventIdleExpiryOrExplicitExportAndReset() {
        try (TraceStore store = newStore(config())) {
            Trace idle = trace("idle");
            store.register(idle);
            nanos.addAndGet(TimeUnit.MINUTES.toNanos(11));
            assertThat(store.sweepIdle()).isEqualTo(1);
            Trace http = trace("http"), job = trace("job", EntryChannel.SCHEDULER);
            store.register(http);
            store.register(job);
            store.appendEvent(http.getSegmentId(), event());
            store.appendEvent(job.getSegmentId(), event());
            store.export(http.getSegmentId());
            store.reset(job.getSegmentId());
            assertThat(store.retentionSummary()).containsEntry("httpRequests", 0)
                    .containsEntry("backgroundRequests", 0).containsEntry("httpEvents", 0L)
                    .containsEntry("backgroundEvents", 0L).containsEntry("idleEvictions", 1L);
            store.register(trace("fresh"));
            store.resetAll();
            assertThat(store.retentionSummary()).containsEntry("httpRequests", 0).containsEntry("httpEvents", 0L);
        }
    }

    @Test
    void duplicateRegistrationIsIdempotentAndDoesNotReplaceOrLoseCounters() {
        try (TraceStore store = newStore(config())) {
            Trace http = trace("http");
            store.register(http);
            store.appendEvent(http.getSegmentId(), event());
            assertThat(store.register(http)).isTrue();
            Trace collision = new Trace(http.getTraceId(), "collision", "user", EntryChannel.SCHEDULER,
                    millis.get(), nanos.get(), 100);
            assertThat(store.register(collision)).isFalse();
            assertThat(store.size()).isEqualTo(1);
            assertThat(store.totalEvents()).isEqualTo(1);
            assertThat(store.retentionSummary()).containsEntry("httpRequests", 1).containsEntry("httpEvents", 1L);
        }
    }

    @Test
    void accountsForPrepopulatedEventsAndRejectsOversizedAdmissionWithoutEviction() {
        try (TraceStore store = newStore(config().setMaxTrackedRequests(1).setMaxTrackedEvents(4))) {
            Trace existing = trace("existing");
            existing.addEvent(event());
            store.register(existing);
            Trace oversized = trace("oversized");
            for (int i = 0; i < 4; i++) oversized.addEvent(event());
            assertThat(store.register(oversized)).isFalse();
            assertThat(store.get(existing.getSegmentId())).contains(existing);
            assertThat(store.totalEvents()).isEqualTo(1);
        }
    }

    @Test
    void zeroPercentRestoresCrossChannelEvictionAndFullSharedEventBudget() {
        try (TraceStore store = newStore(config().setMaxTrackedRequests(1).setHttpRetentionPercent(0))) {
            Trace http = trace("http");
            store.register(http);
            store.register(trace("job", EntryChannel.SCHEDULER));
            assertThat(store.get(http.getSegmentId())).isEmpty();
            assertThat(store.retentionSummary()).containsEntry("policy", "shared");
        }
    }
}
