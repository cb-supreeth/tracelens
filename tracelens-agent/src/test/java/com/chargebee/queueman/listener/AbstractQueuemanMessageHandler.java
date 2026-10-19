package com.chargebee.queueman.listener;

import java.util.Map;

public abstract class AbstractQueuemanMessageHandler {
    public void processMessage(Object payload, Map<String, Object> headers, Object commit) {
        // Empty handler fixture; the observer must never inspect payload/commit.
    }
}
