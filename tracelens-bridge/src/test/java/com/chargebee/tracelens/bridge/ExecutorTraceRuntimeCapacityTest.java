package com.chargebee.tracelens.bridge;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

class ExecutorTraceRuntimeCapacityTest {
    @Test void quarantineBookkeepingCannotGrowWithoutBound() throws Exception {
        URL classes = ExecutorTraceRuntime.class.getProtectionDomain().getCodeSource().getLocation();
        List<ThreadPoolExecutor> executors = new ArrayList<>();
        // Isolate the irreversible safety circuit breaker from the other runtime tests.
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes}, null)) {
            Class<?> runtime = loader.loadClass(ExecutorTraceRuntime.class.getName());
            Method submit = runtime.getMethod("submit", Object.class, Runnable.class);
            Method submitted = runtime.getMethod("submitted", Object.class, Throwable.class);
            ThreadPoolExecutor owner = executor();
            executors.add(owner);
            Runnable task = () -> { };
            for (int i = 0; i < 4096; i++) submitted.invoke(null, submit.invoke(null, owner, task), null);
            assertThat(runtime.getMethod("pendingCount").invoke(null)).isEqualTo(4096);
            for (int i = 0; i < 256; i++) {
                ThreadPoolExecutor overflow = executor();
                executors.add(overflow);
                assertThat(submit.invoke(null, overflow, task)).isNull();
            }
            assertThat(runtime.getMethod("quarantinedExecutorCount").invoke(null)).isEqualTo(256);
            assertThat(runtime.getMethod("associationDisabled").invoke(null)).isEqualTo(false);
            ThreadPoolExecutor overflow = executor();
            executors.add(overflow);
            assertThat(submit.invoke(null, overflow, task)).isNull();
            assertThat(runtime.getMethod("associationDisabled").invoke(null)).isEqualTo(true);
            assertThat(runtime.getMethod("pendingCount").invoke(null)).isEqualTo(0);
            assertThat(runtime.getMethod("quarantinedExecutorCount").invoke(null)).isEqualTo(0);
            assertThat(submit.invoke(null, owner, task)).isNull();
        } finally {
            for (ThreadPoolExecutor executor : executors) executor.shutdownNow();
        }
    }

    private static ThreadPoolExecutor executor() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    }
}
