package com.amazonaws.services.sqs;

import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.amazonaws.services.sqs.model.SendMessageBatchRequest;

/** SDK-shaped fixture, never contacts a queue. */
public interface AmazonSQS {
    Object sendMessage(SendMessageRequest request);
    Object sendMessageBatch(SendMessageBatchRequest request);
}
