package com.amazonaws.services.sqs.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal fake of the AWS SDK v1 request for testing the bridge's reflective injector. */
public class SendMessageRequest {
    private final Map<String, MessageAttributeValue> messageAttributes = new LinkedHashMap<>();

    public void addMessageAttributesEntry(String key, MessageAttributeValue value) {
        messageAttributes.put(key, value);
    }

    public Map<String, MessageAttributeValue> getMessageAttributes() {
        return messageAttributes;
    }
}
