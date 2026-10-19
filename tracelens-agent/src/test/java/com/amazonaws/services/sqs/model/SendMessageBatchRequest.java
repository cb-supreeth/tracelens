package com.amazonaws.services.sqs.model;

import java.util.Arrays;
import java.util.List;

public class SendMessageBatchRequest {
    public String getQueueUrl() { return "https://sqs.example/123456789/test-batch"; }
    public List<?> getEntries() { return Arrays.asList(new Object(), new Object()); }
}
