package com.amazonaws.services.sqs.model;

public class MessageAttributeValue {
    public String recordedValue;
    public MessageAttributeValue withDataType(String value) { return this; }
    public MessageAttributeValue withStringValue(String value) { recordedValue = value; return this; }
}
