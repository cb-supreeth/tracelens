package com.amazonaws.services.dynamodbv2;

/** SDK-shaped fixture. Arguments must never be inspected by advice. */
public class AmazonDynamoDBClient {
    public Object putItem(Object request) { return request; }
    public Object query(Object request) { return request; }
    public Object deleteItem(Object request) { return request; }
    public void shutdown() { }
}
