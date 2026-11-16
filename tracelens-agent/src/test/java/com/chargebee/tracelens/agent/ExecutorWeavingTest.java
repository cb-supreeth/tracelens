package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.TraceContext;
import com.chargebee.tracelens.bridge.TraceScope;
import com.chargebee.tracelens.bridge.TraceSnapshot;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;
import com.chargebee.tracelens.bridge.ExecutorTraceRuntime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Uses the agent's real executor instrumentation: identity-preserving association
 * must carry context onto pool threads. Fails when the advice doesn't fire
 * (e.g. retransformation silently no-ops).
 */
class ExecutorWeavingTest {

    /** Jar up the compiled bridge classes and append to the bootstrap search path. */
    private static void injectBridgeIntoBootstrap(Instrumentation inst) throws Exception {
        java.io.File classesDir = java.nio.file.Path.of("..", "tracelens-bridge", "build",
                "classes", "java", "main").toAbsolutePath().normalize().toFile();
        if (!classesDir.isDirectory()) {
            throw new IllegalStateException("bridge classes not found at " + classesDir);
        }
        java.io.File jar = java.io.File.createTempFile("tracelens-bridge-test", ".jar");
        jar.deleteOnExit();
        try (java.util.jar.JarOutputStream out = new java.util.jar.JarOutputStream(
                new java.io.FileOutputStream(jar))) {
            java.nio.file.Files.walk(classesDir.toPath())
                    .filter(java.nio.file.Files::isRegularFile)
                    .forEach(p -> {
                        String entry = classesDir.toPath().relativize(p).toString()
                                .replace(java.io.File.separatorChar, '/');
                        try {
                            out.putNextEntry(new java.util.jar.JarEntry(entry));
                            out.write(java.nio.file.Files.readAllBytes(p));
                            out.closeEntry();
                        } catch (java.io.IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        inst.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(jar));
    }

    @Test
    void threadPoolExecutorTasksCarryTraceContext() throws Exception {
        // This test class runs in its own JVM (forkEvery=1). The FIRST touch of the
        // bridge must be this injection — after this, every TraceContext/TraceRuntime
        // reference (this test class, the woven JDK class) resolves to the bootstrap
        // copy, exactly like production where the agent injects the bridge at premain.
        Instrumentation inst = ByteBuddyAgent.install();
        injectBridgeIntoBootstrap(inst);

        java.lang.reflect.Method install = TraceLensAgent.class.getDeclaredMethod("installExecutorInstrumentation", Instrumentation.class);
        install.setAccessible(true);
        install.invoke(null, inst);
        assertThat(TraceContext.class.getClassLoader()).isNull();
        assertThat(TraceSnapshot.class.getClassLoader()).isNull();
        assertThat(ExecutorTraceRuntime.class.getClassLoader()).isNull();

        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            AtomicReference<String> traceIdOnPoolThread = new AtomicReference<>();
            TraceSnapshot snapshot = new TraceSnapshot("trace-exec-test", "lens", "user", null);
            try (TraceScope ignored = TraceContext.activate(snapshot)) {
                pool.submit(() -> traceIdOnPoolThread.set(TraceContext.currentTraceId())).get();
                assertThatThrownBy(() -> pool.execute(null)).isInstanceOf(NullPointerException.class);
                RuntimeException original = new IllegalStateException("application");
                java.util.concurrent.Future<?> failed = pool.submit(() -> { throw original; });
                assertThatThrownBy(failed::get).cause().isSameAs(original);
            }

            assertThat(traceIdOnPoolThread.get())
                    .as("pending=%s propagated=%s skipped=%s", ExecutorTraceRuntime.pendingCount(),
                            ExecutorTraceRuntime.propagatedTaskCount(), ExecutorTraceRuntime.skippedCount())
                    .isEqualTo("trace-exec-test");
            assertThat(pool.submit(TraceContext::currentTraceId).get()).isNull();
            checkUntracedThenTracedReuse((ThreadPoolExecutor) pool);
            checkQueueContracts((ThreadPoolExecutor) pool, snapshot);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        checkRejectionIdentity();
        checkUnsupportedCustomQueue();
        assertThat(ExecutorTraceRuntime.pendingCount()).isZero();
    }

    // JUnit inspects method signatures before bootstrap injection; keep bridge
    // types out of signatures so that inspection cannot preload a second copy.
    private static void checkUntracedThenTracedReuse(ThreadPoolExecutor pool) throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        pool.execute(() -> { started.countDown(); await(release); });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        AtomicReference<String> unexpectedTrace = new AtomicReference<>();
        Runnable repeated = () -> {
            if (TraceContext.currentTraceId() != null) unexpectedTrace.set(TraceContext.currentTraceId());
        };
        try {
            pool.execute(repeated);
            try (TraceScope ignored = TraceContext.activate(new TraceSnapshot("later", "lens", "user", null))) {
                pool.execute(repeated);
            }
        } finally { release.countDown(); }
        pool.submit(() -> { }).get();
        assertThat(unexpectedTrace.get()).isNull();
    }

    private static void checkQueueContracts(ThreadPoolExecutor pool, Object snapshot) throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        pool.execute(() -> { started.countDown(); await(release); });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        try (TraceScope ignored = TraceContext.activate((TraceSnapshot) snapshot)) {
            FutureTask<Void> original = new FutureTask<>(() -> null);
            pool.execute(original);
            assertThat(pool.getQueue().peek()).isSameAs(original);
            assertThat(pool.remove(original)).isTrue();
            assertThat(pool.getQueue()).isEmpty();
            FutureTask<Void> cancelled = new FutureTask<>(() -> null);
            pool.execute(cancelled);
            assertThat(cancelled.cancel(false)).isTrue();
            pool.purge();
            assertThat(pool.getQueue()).isEmpty();
            assertThat(ExecutorTraceRuntime.pendingCount()).isZero();
            FutureTask<Void> stopped = new FutureTask<>(() -> null);
            pool.execute(stopped);
            assertThat(pool.shutdownNow()).containsExactly(stopped);
        } finally { release.countDown(); }
    }

    private static void checkRejectionIdentity() throws Exception {
        AtomicReference<Runnable> rejected = new AtomicReference<>();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), (task, executor) -> rejected.set(task));
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        pool.execute(() -> { started.countDown(); await(release); });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        try (TraceScope ignored = TraceContext.activate(new TraceSnapshot("rejected", "lens", "user", null))) {
            FutureTask<Void> queued = new FutureTask<>(() -> null), refused = new FutureTask<>(() -> null);
            pool.execute(queued);
            pool.execute(refused);
            assertThat(rejected.get()).isSameAs(refused);
            assertThat(ExecutorTraceRuntime.pendingCount()).isEqualTo(1);
            assertThat(pool.shutdownNow()).containsExactly(queued);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private static void checkUnsupportedCustomQueue() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<Runnable>() { });
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        pool.execute(() -> { started.countDown(); await(release); });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        AtomicReference<String> observed = new AtomicReference<>("not-run");
        Runnable original = () -> observed.set(TraceContext.currentTraceId());
        try {
            try (TraceScope ignored = TraceContext.activate(new TraceSnapshot("unsupported", "lens", "user", null))) {
                pool.execute(original);
            }
            assertThat(pool.getQueue().peek()).isSameAs(original);
            assertThat(ExecutorTraceRuntime.pendingCount()).isZero();
            release.countDown();
            pool.submit(() -> { }).get();
            assertThat(observed.get()).isNull();
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
