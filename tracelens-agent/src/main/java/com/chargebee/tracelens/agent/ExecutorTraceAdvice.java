package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.TraceContext;
import net.bytebuddy.asm.Advice;

import java.util.concurrent.Callable;

/**
 * Trace propagation across thread pools without OTel: wrap tasks at the terminal
 * hand-off points so the snapshot rides along. Covers every Spring
 * ThreadPoolTaskExecutor (they delegate to a java.util.concurrent pool), ad-hoc
 * pools, and scheduled pools — framework-independent by design.
 *
 *   ThreadPoolExecutor.execute(Runnable)          — submit() funnels here
 *   ScheduledThreadPoolExecutor.schedule*(...)    — its execute()/submit() funnel here
 *
 * No-trace and already-wrapped (TracedTask) tasks pass through unmodified, so pools
 * with no traced work pay one ThreadLocal read per task.
 */
public final class ExecutorTraceAdvice {

    private ExecutorTraceAdvice() {
    }

    public static final class WrapRunnable {
        @Advice.OnMethodEnter
        public static void enter(@Advice.Argument(value = 0, readOnly = false) Runnable task) {
            task = TraceContext.wrap(task);
        }
    }

    public static final class WrapCallable {
        @Advice.OnMethodEnter
        @SuppressWarnings({"rawtypes", "unchecked"})
        public static void enter(@Advice.Argument(value = 0, readOnly = false) Callable task) {
            task = TraceContext.wrap(task);
        }
    }
}
