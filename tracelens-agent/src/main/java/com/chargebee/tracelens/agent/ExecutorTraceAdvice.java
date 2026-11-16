package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.ExecutorTraceRuntime;
import net.bytebuddy.asm.Advice;

/** Exact ThreadPoolExecutor support; application task identities never change. */
public final class ExecutorTraceAdvice {
    private ExecutorTraceAdvice() { }

    public static final class Submit {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static Object enter(@Advice.This Object executor, @Advice.Argument(0) Runnable task) {
            return ExecutorTraceRuntime.submit(executor, task);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter Object token, @Advice.Thrown Throwable failure) {
            ExecutorTraceRuntime.submitted(token, failure);
        }
    }

    public static final class Before {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void enter(@Advice.This Object executor, @Advice.Argument(1) Runnable task) {
            ExecutorTraceRuntime.before(executor, task);
        }
    }

    public static final class After {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void enter(@Advice.This Object executor) { ExecutorTraceRuntime.after(executor); }
    }

    public static final class Reject {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void enter(@Advice.This Object executor, @Advice.Argument(0) Runnable task) {
            ExecutorTraceRuntime.rejected(executor, task);
        }
    }

    public static final class Remove {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void exit(@Advice.This Object executor, @Advice.Argument(0) Runnable task,
                                @Advice.Return boolean removed) {
            ExecutorTraceRuntime.removed(executor, task, removed);
        }
    }

    public static final class Purge {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void exit(@Advice.This Object executor) { ExecutorTraceRuntime.purge(executor, false); }
    }

    public static final class Stop {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void exit(@Advice.This Object executor) { ExecutorTraceRuntime.purge(executor, true); }
    }
}
