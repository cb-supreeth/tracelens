package com.chargebee.tracelens.core.event;

/** Kinds of events on a trace timeline. */
public enum EventType {

    /** A source line executed (the bread and butter: FQCN + line number). */
    LINE,

    /** A method in an instrumented package was entered. */
    METHOD_ENTER,

    /** A method in an instrumented package returned. */
    METHOD_EXIT,

    /** An exception was thrown from an instrumented method. */
    EXCEPTION,

    /** A call left the process: HTTP client, SQS publish/consume, Redis, ClickHouse, etc. */
    EXTERNAL_CALL,

    /** The RoutingDataSource picked a pool (live/test x master/slave x RLS) — EMS-specific. */
    DB_ROUTE,

    /** Free-form lifecycle marker ("request entered filter", "job started", ...). */
    MARKER
}
