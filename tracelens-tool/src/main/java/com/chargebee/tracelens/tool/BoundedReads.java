package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.*;

/** Shared admission control and per-operation budgets, including cancelled reads still winding down. */
final class BoundedReads {
    static final int MAX_MILLIS = 12000, MAX_REQUESTS = 512, MAX_BYTES = 32 * 1024 * 1024;
    private static final ThreadPoolExecutor READERS = new ThreadPoolExecutor(8, 8, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(64), runnable -> {
                Thread thread = new Thread(runnable, "tracelens-bounded-read");
                thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static final ThreadLocal<Allowance> CURRENT = new ThreadLocal<>();
    static { READERS.allowCoreThreadTimeOut(true); }
    private BoundedReads() {}

    static final class Limit extends ToolException {
        Limit(String reason) { super(503, "Investigation " + reason + " budget reached; retained evidence may be missing."); }
    }
    static final class Allowance {
        final int maxBytes; final long deadline;
        int consumed; boolean started;
        Allowance(int maxBytes, long deadline) { this.maxBytes = maxBytes; this.deadline = deadline; }
        void start() { if (started) throw new Limit("request"); started = true; }
        int timeout(int maximum) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || Thread.currentThread().isInterrupted()) throw new Limit("time");
            return (int) Math.min(maximum, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
        }
    }
    static Allowance allowance() { return CURRENT.get(); }
    static <T> Future<T> submit(Callable<T> operation) {
        try { return READERS.submit(operation); }
        catch (RejectedExecutionException busy) { throw new Limit("concurrency"); }
    }
    static <T> T await(Future<T> pending, long deadline) {
        try {
            if (pending.isDone()) return pending.get();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            return pending.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timedOut) {
            pending.cancel(true); throw new Limit("time");
        } catch (InterruptedException interrupted) {
            pending.cancel(true); Thread.currentThread().interrupt(); throw new Limit("time");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IllegalStateException(cause);
        } finally { READERS.purge(); }
    }

    static final class Budget {
        final long deadline;
        private int requests, bytes;
        Budget() { this(MAX_MILLIS, MAX_REQUESTS, MAX_BYTES); }
        Budget(int millis, int requests, int bytes) {
            this(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis), requests, bytes);
        }
        Budget(long deadline, int requests, int bytes) { this.deadline = deadline; this.requests = requests; this.bytes = bytes; }
        ObjectNode read(Callable<ObjectNode> operation, int maxBytes) {
            return await(submit(() -> call(operation, maxBytes)), deadline);
        }
        ObjectNode call(Callable<ObjectNode> operation, int maxBytes) {
            int reserved;
            synchronized (this) {
                if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) throw new Limit("time");
                if (requests <= 0) throw new Limit("request");
                if (bytes <= 0) throw new Limit("byte");
                requests--; reserved = Math.min(maxBytes, bytes); bytes -= reserved;
            }
            Allowance previous = CURRENT.get(), allowance = new Allowance(reserved, deadline);
            CURRENT.set(allowance); int charged = reserved;
            try {
                ObjectNode result = operation.call();
                int size = Captures.bytes(result).length;
                charged = allowance.started ? allowance.consumed : size;
                if (size > reserved || charged > reserved) throw new Limit("byte");
                allowance.timeout(Integer.MAX_VALUE);
                return result;
            } catch (RuntimeException failure) { throw failure; }
            catch (Exception failure) { throw new IllegalStateException(failure); }
            finally {
                if (allowance.started) charged = allowance.consumed;
                synchronized (this) { bytes += reserved - Math.min(reserved, charged); }
                if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            }
        }
    }
}
