package com.chargebee.app.scheduler;
public class IgnoreJobSignal extends Exception {
    public IgnoreJobSignal(String reason) { super(reason); }
}
