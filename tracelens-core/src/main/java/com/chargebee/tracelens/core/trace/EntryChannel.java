package com.chargebee.tracelens.core.trace;

/** How a trace entered the service. */
public enum EntryChannel {

    /** Synchronous HTTP request (servlet filter). */
    HTTP,

    /** Queueman/SQS message consumption. */
    SQS,

    /** A @Scheduled job run (synthetic trace; lens-id = job name). */
    SCHEDULER,

    /** Anything else (tests, manual registration). */
    OTHER
}
