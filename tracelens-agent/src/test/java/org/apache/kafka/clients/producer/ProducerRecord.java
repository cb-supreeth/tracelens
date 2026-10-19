package org.apache.kafka.clients.producer;

public class ProducerRecord {
    public String topic() { return "billing-events"; }
    public Object key() { throw new AssertionError("must not read key"); }
    public Object value() { throw new AssertionError("must not read value"); }
    public Object headers() { throw new AssertionError("must not read headers"); }
}
