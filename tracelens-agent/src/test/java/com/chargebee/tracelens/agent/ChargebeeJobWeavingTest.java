package com.chargebee.tracelens.agent;

import com.chargebee.app.models.SchJob;
import com.chargebee.app.scheduler.IgnoreJobSignal;
import com.chargebee.app.scheduler.internal.AppJobExecutor;
import com.chargebee.tracelens.bridge.*;
import com.chargebee.tracelens.core.config.TraceLensConfig;
import com.chargebee.tracelens.core.record.TraceLifecycle;
import com.chargebee.tracelens.core.record.TraceRecording;
import com.chargebee.tracelens.core.record.Tracer;
import com.chargebee.tracelens.core.store.TraceStore;
import com.chargebee.tracelens.core.trace.Trace;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.*;
import java.util.Collections;
import static org.assertj.core.api.Assertions.*;

/** Real Byte Buddy matchers, real lifecycle/store, application-shaped no-network fixtures. */
class ChargebeeJobWeavingTest {
    TraceStore store;

    @BeforeAll static void weave() {
        ChargebeeJobInstrumentation.install(ByteBuddyAgent.install(), Collections.singletonList("compute_billing_metrics"));
    }

    @BeforeEach void start() {
        ChargebeeJobRuntime.configure(Collections.singletonList("compute_billing_metrics"));
        store = new TraceStore(new TraceLensConfig().setApplicationId("test").setEvictOnExport(false));
        TraceRecording.install(store);
        TraceRuntime.registerLifecycleListener(new TraceLifecycle(new Tracer(store)));
    }

    @AfterEach void cleanup() {
        TraceRuntime.unregisterLifecycleListener();
        TraceRecording.uninstall();
        ChargebeeJobRuntime.configure(Collections.emptyList());
        TraceContext.clear();
        store.close();
    }

    @Test void exactAllowlistAndIndependentRoot() throws Exception {
        TraceSnapshot ambient = new TraceSnapshot("http-trace", "http-segment", "request", "user", null);
        try (TraceScope scope = TraceContext.activate(ambient)) {
            AppJobExecutor executor = new AppJobExecutor();
            executor.exec(new SchJob("compute_billing_metrics", "success"), null);
            Trace trace = store.get(executor.observedSegment).get();
            assertThat(trace.getTraceId()).isNotEqualTo("http-trace");
            assertThat(trace.getParentSegmentId()).isNull();
            assertThat(trace.getEntryChannel().name()).isEqualTo("SCHEDULER");
            assertThat(trace.getAttributes()).containsEntry("jobOutcome", "SUCCEEDED")
                    .containsEntry("jobType", "compute_billing_metrics").containsEntry("jobId", "42")
                    .containsEntry("siteId", "7").containsEntry("scheduler", "chargebee");
            assertThat(trace.eventCount()).isPositive();
            assertThat(trace.getStatus().name()).isEqualTo("COMPLETED");
            assertThat(TraceContext.current()).isSameAs(ambient);
            executor.exec(new SchJob("compute_billing_metrics_extra", "failed"), null);
            assertThat(executor.observedSegment).isNull();
            assertThat(store.size()).isEqualTo(1);
            assertThat(TraceContext.current()).isSameAs(ambient);
            executor.exec(new Object(), null);
            assertThat(executor.observedSegment).isEqualTo("http-segment");
        }
    }

    @Test void reportedOutcomesIncludeSwallowedInitializationAndPrecheckFailures() throws Exception {
        String[][] cases = {{"success", "SUCCEEDED"}, {"ignored", "SKIPPED"}, {"failed", "FAILED"},
                {"init-failed", "FAILED"}, {"precheck-failed", "FAILED"}, {"retry", "FAILED"},
                {"reschedule-failed", "FAILED"}, {"unknown", "UNKNOWN"}, {"foreign-audit", "SUCCEEDED"}};
        for (String[] c : cases) {
            AppJobExecutor executor = new AppJobExecutor();
            executor.exec(new SchJob("compute_billing_metrics", c[0]), null);
            Trace trace = store.get(executor.observedSegment).get();
            assertThat(trace.getAttributes()).as(c[0]).containsEntry("jobOutcome", c[1]);
            assertThat(trace.getStatus().name()).isEqualTo("COMPLETED");
            assertThat(TraceContext.current()).isNull();
        }
    }

    @Test void originalEscapingExceptionAndIgnoredSignalArePreserved() {
        AppJobExecutor executor = new AppJobExecutor();
        assertThatThrownBy(() -> executor.exec(new SchJob("compute_billing_metrics", "escape"), null))
                .isSameAs(executor.escaped);
        assertThat(store.get(executor.observedSegment).get().getAttributes()).containsEntry("jobOutcome", "FAILED");
        assertThat(TraceContext.current()).isNull();
        assertThatThrownBy(() -> executor.exec(new SchJob("compute_billing_metrics", "escape-ignored"), null))
                .isInstanceOf(IgnoreJobSignal.class);
        assertThat(store.get(executor.observedSegment).get().getAttributes()).containsEntry("jobOutcome", "SKIPPED");
        assertThat(TraceContext.current()).isNull();
    }

    @Test void nestedDispatchesDoNotContaminateOuterOutcomeOrContext() throws Exception {
        AppJobExecutor outer = new AppJobExecutor();
        outer.exec(new SchJob("compute_billing_metrics", "nested"), null);
        assertThat(outer.restoredSegment).isEqualTo(outer.observedSegment);
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.get(outer.observedSegment).get().getAttributes()).containsEntry("jobOutcome", "SUCCEEDED");
        outer.exec(new SchJob("compute_billing_metrics", "nested-selected"), null);
        assertThat(outer.restoredSegment).isEqualTo(outer.observedSegment);
        assertThat(store.size()).isEqualTo(3);
        assertThat(store.get(outer.observedSegment).get().getAttributes()).containsEntry("jobOutcome", "SUCCEEDED");
        assertThat(TraceContext.current()).isNull();
    }

    @Test void defaultOffAndRecordingOffDoNotInventTraces() throws Exception {
        AppJobExecutor executor = new AppJobExecutor();
        ChargebeeJobRuntime.configure(Collections.emptyList());
        executor.exec(new SchJob("compute_billing_metrics", "success"), null);
        assertThat(store.size()).isZero();
        ChargebeeJobRuntime.configure(Collections.singletonList("compute_billing_metrics"));
        store.setRecordingEnabled(false);
        executor.exec(new SchJob("compute_billing_metrics", "success"), null);
        assertThat(store.size()).isZero();
        assertThat(executor.observedSegment).isNull();
        assertThat(TraceContext.current()).isNull();
        store.setRecordingEnabled(true);
        TraceRuntime.unregisterLifecycleListener();
        executor.exec(new SchJob("compute_billing_metrics", "failed"), null);
        assertThat(store.size()).isZero();
        assertThat(TraceContext.current()).isNull();
    }

    @Test void callbackFailuresCannotBreakHostOrLeakContext() throws Exception {
        TraceRuntime.registerLifecycleListener(new TraceRuntime.LifecycleListener() {
            public String begin(String trace, String lens, String user, String channel, java.util.Map<String, String> fields) {
                TraceContext.activate(new TraceSnapshot("broken", "broken", lens, user, null));
                throw new IllegalStateException("listener failure");
            }
            public void end(String segment) { throw new IllegalStateException("listener failure"); }
        });
        AppJobExecutor executor = new AppJobExecutor();
        executor.exec(new SchJob("compute_billing_metrics", "success"), null);
        assertThat(executor.auditCalls).isEqualTo(1);
        assertThat(TraceContext.current()).isNull();
        assertThat(store.size()).isZero();
    }

    @Test void missingFrameworkShapeAndInvalidAllowlistFailClosed() {
        Object token = ChargebeeJobRuntime.enter(new Object());
        ChargebeeJobRuntime.exit(token, null);
        assertThat(store.size()).isZero();
        assertThat(TraceContext.current()).isNull();
        ChargebeeJobRuntime.configure(Collections.singletonList("*"));
        assertThat(ChargebeeJobRuntime.enter(new SchJob("compute_billing_metrics", "success"))).isNull();
        assertThat(store.size()).isZero();
    }

    @Test void failingCompletionListenerStillRestoresAmbientContext() throws Exception {
        TraceRuntime.registerLifecycleListener(new TraceRuntime.LifecycleListener() {
            public String begin(String trace, String lens, String user, String channel, java.util.Map<String, String> fields) {
                TraceContext.activate(new TraceSnapshot("job", "job", lens, user, fields));
                return "job";
            }
            public void end(String segment) { throw new IllegalStateException("completion failure"); }
        });
        TraceSnapshot ambient = new TraceSnapshot("outer", "outer", "request", "user", null);
        try (TraceScope scope = TraceContext.activate(ambient)) {
            new AppJobExecutor().exec(new SchJob("compute_billing_metrics", "success"), null);
            assertThat(TraceContext.current()).isSameAs(ambient);
        }
        assertThat(TraceContext.current()).isNull();
    }
}
