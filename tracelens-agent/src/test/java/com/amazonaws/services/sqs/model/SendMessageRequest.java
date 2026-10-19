package com.amazonaws.services.sqs.model;

public class SendMessageRequest {
    public int attributeWrites;
    public final java.util.Map<String, MessageAttributeValue> writtenAttributes = new java.util.LinkedHashMap<>();
    public String getQueueUrl() { return "https://sqs.example/123456789/test-queue?secret=hidden"; }
    public SendMessageRequest addMessageAttributesEntry(String key, MessageAttributeValue value) {
        attributeWrites++;
        writtenAttributes.put(key, value);
        return this;
    }
    public String getMessageBody() { throw new AssertionError("must not inspect body"); }
    public Object getMessageAttributes() { throw new AssertionError("must not inspect attributes"); }
}
