package com.amazonaws.services.sqs.model;

public class SendMessageRequest {
    public int attributeWrites;
    public int attributeReads;
    public java.util.Map<String, MessageAttributeValue> writtenAttributes = new java.util.LinkedHashMap<>();
    public String getQueueUrl() { return "https://sqs.example/123456789/test-queue?secret=hidden"; }
    public SendMessageRequest addMessageAttributesEntry(String key, MessageAttributeValue value) {
        attributeWrites++;
        writtenAttributes.put(key, value);
        return this;
    }
    public String getMessageBody() { throw new AssertionError("must not inspect body"); }
    public java.util.Map<String, MessageAttributeValue> getMessageAttributes() { attributeReads++; return writtenAttributes; }
    public void setMessageAttributes(java.util.Map<String, MessageAttributeValue> attributes) {
        attributeWrites++;
        writtenAttributes = new java.util.LinkedHashMap<>(attributes);
    }
}
