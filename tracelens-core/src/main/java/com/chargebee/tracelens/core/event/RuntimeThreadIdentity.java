package com.chargebee.tracelens.core.event;

import java.util.concurrent.atomic.AtomicLong;

/** Runtime-local identity, scoped by the owning service instance; never an async causal link. */
public final class RuntimeThreadIdentity {
    private static final AtomicLong NEXT = new AtomicLong();
    // Unlike a thread name (or a JVM thread ID after termination), these tokens are not reused.
    private static final ThreadLocal<Long> CURRENT = ThreadLocal.withInitial(NEXT::incrementAndGet);

    private RuntimeThreadIdentity() {}

    public static Long current() { return CURRENT.get(); }
}
