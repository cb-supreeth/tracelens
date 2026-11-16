package com.chargebee.tracelens.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

class ExecutorTraceRuntimeTest {
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());

    @AfterEach void cleanup() {
        ExecutorTraceRuntime.after(executor);
        ExecutorTraceRuntime.purge(executor, true);
        TraceContext.clear();
        executor.shutdownNow();
    }

    @Test void preservesNullTasksAndRestoresTheWorkersPreviousScope() {
        Runnable task = () -> { };
        TraceSnapshot captured = new TraceSnapshot("captured", "lens", "user", null);
        try (TraceScope ignored = TraceContext.activate(captured)) {
            assertThat(TraceContext.wrap((Runnable) null)).isNull();
            assertThat(TraceContext.wrap((java.util.concurrent.Callable<Object>) null)).isNull();
            assertThat(ExecutorTraceRuntime.submit(executor, null)).isNull();
            Object submission = ExecutorTraceRuntime.submit(executor, task);
            ExecutorTraceRuntime.submitted(submission, null);
        }
        TraceSnapshot previous = new TraceSnapshot("previous", "lens", "user", null);
        try (TraceScope ignored = TraceContext.activate(previous)) {
            ExecutorTraceRuntime.before(executor, task);
            assertThat(TraceContext.current()).isSameAs(captured);
            ExecutorTraceRuntime.after(executor);
            assertThat(TraceContext.current()).isSameAs(previous);
        }
        assertThat(ExecutorTraceRuntime.pendingCount()).isZero();
    }

    @Test void ambiguousRepeatedTaskIdentitiesDoNotAcquireAnotherSubmissionsContext() {
        Runnable task = () -> { };
        for (String trace : new String[]{"first", "second"}) {
            try (TraceScope ignored = TraceContext.activate(new TraceSnapshot(trace, "lens", "user", null))) {
                Object submission = ExecutorTraceRuntime.submit(executor, task);
                ExecutorTraceRuntime.submitted(submission, null);
            }
        }
        long skipped = ExecutorTraceRuntime.skippedCount();
        for (int i = 0; i < 2; i++) {
            ExecutorTraceRuntime.before(executor, task);
            assertThat(TraceContext.current()).isNull();
            ExecutorTraceRuntime.after(executor);
        }
        assertThat(ExecutorTraceRuntime.skippedCount()).isEqualTo(skipped + 2);
        assertThat(ExecutorTraceRuntime.pendingCount()).isZero();
    }

    @Test void anEarlierUntracedAdmissionCannotAcquireALaterRepeatedTasksContext() {
        Runnable task = () -> { };
        Object untraced = ExecutorTraceRuntime.submit(executor, task);
        ExecutorTraceRuntime.submitted(untraced, null);
        try (TraceScope ignored = TraceContext.activate(new TraceSnapshot("later", "lens", "user", null))) {
            Object traced = ExecutorTraceRuntime.submit(executor, task);
            ExecutorTraceRuntime.submitted(traced, null);
        }
        for (int i = 0; i < 2; i++) {
            ExecutorTraceRuntime.before(executor, task);
            assertThat(TraceContext.current()).isNull();
            ExecutorTraceRuntime.after(executor);
        }
        assertThat(ExecutorTraceRuntime.pendingCount()).isZero();
    }

    @Test void unsupportedExecutorsRemainUntouchedAndBookkeepingIsBounded() {
        ThreadPoolExecutor custom = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>()) { };
        List<Runnable> tasks = new ArrayList<>();
        try {
            int quarantined = ExecutorTraceRuntime.quarantinedExecutorCount();
            for (int i = 0; i < 4100; i++) {
                Runnable task = new Runnable() { public void run() { } };
                tasks.add(task);
                Object submission = ExecutorTraceRuntime.submit(executor, task);
                ExecutorTraceRuntime.submitted(submission, null);
            }
            assertThat(ExecutorTraceRuntime.pendingCount()).isZero();
            assertThat(ExecutorTraceRuntime.quarantinedExecutorCount()).isEqualTo(quarantined + 1);
            assertThat(ExecutorTraceRuntime.associationDisabled()).isFalse();
            assertThat(tasks).hasSize(4100);
            try (TraceScope ignored = TraceContext.activate(new TraceSnapshot("trace", "lens", "user", null))) {
                assertThat(ExecutorTraceRuntime.submit(custom, () -> { })).isNull();
                assertThat(ExecutorTraceRuntime.submit(executor, tasks.get(4099))).isNull();
            }
            ExecutorTraceRuntime.before(executor, tasks.get(4099));
            assertThat(TraceContext.current()).isNull();
            ExecutorTraceRuntime.after(executor);
        } finally { custom.shutdownNow(); }
    }

    @Test void equalityBasedRemovalQuarantinesRatherThanDiscardingAnUnknownTaskIdentity() {
        Runnable first = new EqualTask();
        Runnable second = new EqualTask();
        try (TraceScope ignored = TraceContext.activate(new TraceSnapshot("trace", "lens", "user", null))) {
            Object one = ExecutorTraceRuntime.submit(executor, first);
            ExecutorTraceRuntime.submitted(one, null);
            Object two = ExecutorTraceRuntime.submit(executor, second);
            ExecutorTraceRuntime.submitted(two, null);
        }
        ExecutorTraceRuntime.removed(executor, second, true);
        assertThat(ExecutorTraceRuntime.pendingCount()).isZero();
        ExecutorTraceRuntime.before(executor, first);
        assertThat(TraceContext.current()).isNull();
        ExecutorTraceRuntime.after(executor);
        ExecutorTraceRuntime.before(executor, second);
        assertThat(TraceContext.current()).isNull();
        ExecutorTraceRuntime.after(executor);
    }

    private static final class EqualTask implements Runnable {
        public void run() { }
        @Override public boolean equals(Object other) { return other instanceof EqualTask; }
        @Override public int hashCode() { return 1; }
    }

    @Test void rejectionAndThrownSubmissionDiscardOnlyTheirOwnAssociation() {
        Runnable task = () -> { };
        try (TraceScope ignored = TraceContext.activate(new TraceSnapshot("trace", "lens", "user", null))) {
            Object submission = ExecutorTraceRuntime.submit(executor, task);
            ExecutorTraceRuntime.rejected(executor, task);
            ExecutorTraceRuntime.submitted(submission, new IllegalStateException("rejected"));
            assertThat(ExecutorTraceRuntime.pendingCount()).isZero();
            Object failed = ExecutorTraceRuntime.submit(executor, task);
            ExecutorTraceRuntime.submitted(failed, new IllegalStateException("queue failed"));
            assertThat(ExecutorTraceRuntime.pendingCount()).isZero();
        }
    }

    @Test void customQueuesAreUnsupportedEvenOnAnExactThreadPoolExecutor() {
        ThreadPoolExecutor customQueue = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>() { });
        Runnable original = () -> { };
        long skipped = ExecutorTraceRuntime.skippedCount();
        try (TraceScope ignored = TraceContext.activate(new TraceSnapshot("trace", "lens", "user", null))) {
            assertThat(ExecutorTraceRuntime.submit(customQueue, original)).isNull();
            assertThat(ExecutorTraceRuntime.pendingCount()).isZero();
            assertThat(ExecutorTraceRuntime.skippedCount()).isEqualTo(skipped + 1);
        } finally { customQueue.shutdownNow(); }
    }
}
