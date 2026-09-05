package com.chargebee.tracelens.bridge;

/** Marker for tasks already wrapped for trace propagation — prevents double-wrapping
 *  when a task passes multiple instrumented hand-off points (submit -> execute). */
public interface TracedTask {
}
