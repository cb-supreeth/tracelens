package com.chargebee.tracelens.core.trace;

/** Lifecycle of a retained trace. */
public enum TraceStatus {

    /** Still receiving events. */
    ACTIVE,

    /** Entry point finished (e.g. HTTP response sent); async children may still append. */
    COMPLETED,

    /** Exported at least once (informational; trace may still be retained). */
    EXPORTED
}
