package org.apache.kafka.clients.consumer;

public class KafkaConsumer {
    public ConsumerRecords poll(long millis) { return new ConsumerRecords(); }
    public ConsumerRecords poll(java.time.Duration duration) { return poll(duration.toMillis()); }
}
