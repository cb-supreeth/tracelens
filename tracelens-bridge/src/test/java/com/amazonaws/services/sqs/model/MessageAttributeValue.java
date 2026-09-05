package com.amazonaws.services.sqs.model;

/** Minimal fake of the AWS SDK v1 type for testing the bridge's reflective injector. */
public class MessageAttributeValue {
    private String dataType;
    private String stringValue;

    public MessageAttributeValue withDataType(String dataType) {
        this.dataType = dataType;
        return this;
    }

    public MessageAttributeValue withStringValue(String stringValue) {
        this.stringValue = stringValue;
        return this;
    }

    public String getStringValue() {
        return stringValue;
    }

    public String getDataType() {
        return dataType;
    }
}
