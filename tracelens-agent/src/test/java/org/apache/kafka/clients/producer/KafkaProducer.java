package org.apache.kafka.clients.producer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class KafkaProducer {
    public Future<Object> send(ProducerRecord record) { return send(record, null); }
    public Future<Object> send(ProducerRecord record, Object callback) {
        return new CompletableFuture<Object>() {
            @Override public Object get() { throw new AssertionError("must not wait for send"); }
            @Override public Object get(long timeout, TimeUnit unit) { throw new AssertionError("must not wait for send"); }
        };
    }
}
