package com.chargebee.tracelens.tool;

/** A safe public diagnostic; never include upstream response bodies or secrets. */
public class ToolException extends RuntimeException {
    public final int status;

    public ToolException(int status, String message) {
        super(message);
        this.status = status;
    }

    public ToolException(String message) {
        this(400, message);
    }
}
