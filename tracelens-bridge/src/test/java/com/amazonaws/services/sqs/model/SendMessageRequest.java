package com.amazonaws.services.sqs.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal fake of the AWS SDK v1 request for testing the bridge's reflective injector. */
public class SendMessageRequest {
    private Map<String, MessageAttributeValue> messageAttributes = new LinkedHashMap<>();
    public boolean failReplacement;
    public int replacements;

    public void addMessageAttributesEntry(String key, MessageAttributeValue value) {
        messageAttributes.put(key, value);
    }

    public Map<String, MessageAttributeValue> getMessageAttributes() {
        return messageAttributes;
    }

    public void setMessageAttributes(Map<String, MessageAttributeValue> attributes) {
        if (failReplacement) throw new IllegalStateException("replacement unavailable");
        messageAttributes = new LinkedHashMap<>(attributes);
        replacements++;
    }
}
