package com.chargebee.tracelens.bridge;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/** Context association without replacing queue entries, Futures or rejected tasks. */
public final class ExecutorTraceRuntime {
    private static final int MAX_PENDING = 4096;
    private static final int MAX_QUARANTINED_EXECUTORS = 256;
    private static final ReferenceQueue<Runnable> COLLECTED = new ReferenceQueue<>();
    private static final ReferenceQueue<Object> COLLECTED_EXECUTORS = new ReferenceQueue<>();
    private static final Map<Key, Pending> PENDING = new HashMap<>();
    private static final Map<Owner, Boolean> QUARANTINED = new HashMap<>();
    private static final ThreadLocal<Submission> SUBMITTING = new ThreadLocal<>();
    private static final ThreadLocal<TraceScope> RUNNING = new ThreadLocal<>();
    private static final AtomicLong SKIPPED = new AtomicLong();
    private static final AtomicLong PROPAGATED = new AtomicLong();
    private static int pendingCount;
    private static boolean associationDisabled;

    private ExecutorTraceRuntime() { }

    public static Object submit(Object executor, Runnable task) {
        if (task == null || task instanceof TracedTask) return null;
        TraceSnapshot snapshot = TraceContext.current();
        // Subclasses and custom queues may replace hooks, queueing rules or task shape.
        if (!supported(executor)) {
            if (snapshot != null) SKIPPED.incrementAndGet();
            return null;
        }
        synchronized (PENDING) {
            reap();
            if (associationDisabled || QUARANTINED.containsKey(new Owner(executor))) {
                if (snapshot != null) SKIPPED.incrementAndGet();
                return null;
            }
            Key key = new Key(executor, task);
            Pending pending = PENDING.get(key);
            if (pending != null) pending.ambiguous = true;
            if (pendingCount >= MAX_PENDING) {
                SKIPPED.incrementAndGet();
                quarantine(executor);
                return null;
            }
            if (pending == null) {
                pending = new Pending();
                PENDING.put(key, pending);
            }
            Submission submission = new Submission(key, snapshot, SUBMITTING.get());
            pending.submissions.add(submission);
            pendingCount++;
            SUBMITTING.set(submission);
            return submission;
        }
    }

    public static void submitted(Object token, Throwable failure) {
        if (!(token instanceof Submission)) return;
        Submission submission = (Submission) token;
        synchronized (PENDING) {
            if (failure != null) discard(submission);
        }
        if (SUBMITTING.get() == submission) {
            if (submission.previous == null) SUBMITTING.remove();
            else SUBMITTING.set(submission.previous);
        }
        submission.previous = null;
    }

    public static void rejected(Object executor, Runnable task) {
        Submission submission = SUBMITTING.get();
        if (submission != null && submission.key.matches(executor, task)) {
            synchronized (PENDING) { discard(submission); }
        }
    }

    public static void removed(Object executor, Runnable task, boolean removed) {
        if (!removed || task == null || !supported(executor)) return;
        synchronized (PENDING) {
            // Queue.remove uses equals; an equal but different task cannot be identified safely.
            try {
                if (task.getClass().getMethod("equals", Object.class).getDeclaringClass() != Object.class) {
                    quarantine(executor);
                    return;
                }
            } catch (Throwable ignored) {
                quarantine(executor);
                return;
            }
            Pending pending = PENDING.get(new Key(executor, task));
            if (pending != null && !pending.submissions.isEmpty()) discard(pending.submissions.get(0));
        }
    }

    public static void before(Object executor, Runnable task) {
        if (!supported(executor)) return;
        TraceSnapshot snapshot = null;
        synchronized (PENDING) {
            reap();
            Pending pending = PENDING.get(new Key(executor, task));
            if (pending == null || pending.submissions.isEmpty()) return;
            Submission submission = pending.submissions.get(0);
            if (pending.ambiguous) SKIPPED.incrementAndGet();
            else snapshot = submission.snapshot;
            discard(submission);
        }
        if (snapshot == null) return;
        TraceScope scope = TraceContext.activate(snapshot);
        try { RUNNING.set(scope); PROPAGATED.incrementAndGet(); }
        catch (Throwable failure) { scope.close(); throw failure; }
    }

    public static void after(Object executor) {
        if (!supported(executor)) return;
        TraceScope scope = RUNNING.get();
        RUNNING.remove();
        if (scope != null) scope.close();
    }

    public static void purge(Object executor, boolean stopped) {
        if (!supported(executor)) return;
        synchronized (PENDING) {
            java.util.Iterator<Map.Entry<Key, Pending>> entries = PENDING.entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<Key, Pending> entry = entries.next();
                Runnable task = entry.getKey().get();
                if (entry.getKey().executor.get() == executor && (stopped || task == null
                        || task.getClass() == java.util.concurrent.FutureTask.class
                        && ((java.util.concurrent.FutureTask<?>) task).isCancelled())) {
                    pendingCount -= entry.getValue().submissions.size();
                    entries.remove();
                }
            }
        }
    }

    public static long skippedCount() { return SKIPPED.get(); }
    public static long propagatedTaskCount() { return PROPAGATED.get(); }

    private static boolean supported(Object executor) {
        if (executor == null || executor.getClass() != ThreadPoolExecutor.class) return false;
        Class<?> queue = ((ThreadPoolExecutor) executor).getQueue().getClass();
        return queue == java.util.concurrent.LinkedBlockingQueue.class
                || queue == java.util.concurrent.ArrayBlockingQueue.class
                || queue == java.util.concurrent.SynchronousQueue.class;
    }

    public static int pendingCount() {
        synchronized (PENDING) { reap(); return pendingCount; }
    }

    public static int quarantinedExecutorCount() {
        synchronized (PENDING) { reap(); return QUARANTINED.size(); }
    }

    public static boolean associationDisabled() {
        synchronized (PENDING) { return associationDisabled; }
    }

    private static void quarantine(Object executor) {
        if (associationDisabled || QUARANTINED.containsKey(new Owner(executor))) return;
        if (QUARANTINED.size() >= MAX_QUARANTINED_EXECUTORS) {
            associationDisabled = true;
            PENDING.clear();
            pendingCount = 0;
            QUARANTINED.clear();
            return;
        }
        QUARANTINED.put(new Owner(executor), Boolean.TRUE);
        // Once an admission is untracked, reusing its identity must not capture a later trace.
        java.util.Iterator<Map.Entry<Key, Pending>> entries = PENDING.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Key, Pending> entry = entries.next();
            if (entry.getKey().executor.get() == executor) {
                pendingCount -= entry.getValue().submissions.size();
                entries.remove();
            }
        }
    }

    private static void discard(Submission submission) {
        Pending pending = PENDING.get(submission.key);
        if (pending != null && pending.submissions.remove(submission)) {
            pendingCount--;
            if (pending.submissions.isEmpty()) PENDING.remove(submission.key);
        }
    }

    private static void reap() {
        Key key;
        while ((key = (Key) COLLECTED.poll()) != null) {
            Pending pending = PENDING.remove(key);
            if (pending != null) pendingCount -= pending.submissions.size();
        }
        Owner owner;
        while ((owner = (Owner) COLLECTED_EXECUTORS.poll()) != null) QUARANTINED.remove(owner);
    }

    private static final class Pending {
        final ArrayList<Submission> submissions = new ArrayList<>();
        boolean ambiguous;
    }

    private static final class Submission {
        final Key key;
        final TraceSnapshot snapshot;
        Submission previous;
        Submission(Key key, TraceSnapshot snapshot, Submission previous) {
            this.key = key;
            this.snapshot = snapshot;
            this.previous = previous;
        }
    }

    private static final class Key extends WeakReference<Runnable> {
        final WeakReference<Object> executor;
        final int hash;
        Key(Object executor, Runnable task) {
            super(task, COLLECTED);
            this.executor = new WeakReference<>(executor);
            hash = 31 * System.identityHashCode(executor) + System.identityHashCode(task);
        }
        boolean matches(Object owner, Runnable task) { return get() == task && executor.get() == owner; }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key) || get() == null || executor.get() == null) return false;
            Key key = (Key) other;
            return get() == key.get() && executor.get() == key.executor.get();
        }
    }

    private static final class Owner extends WeakReference<Object> {
        final int hash;
        Owner(Object executor) {
            super(executor, COLLECTED_EXECUTORS);
            hash = System.identityHashCode(executor);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof Owner && get() != null && get() == ((Owner) other).get();
        }
    }
}
