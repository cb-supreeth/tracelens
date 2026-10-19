package org.apache.kafka.clients.consumer;

import java.util.Collections;
import java.util.Iterator;
import java.util.Set;

public class ConsumerRecords implements Iterable<Object> {
    public int count() { return 1; }
    public Set<Partition> partitions() { return Collections.singleton(new Partition()); }
    public Iterator<Object> iterator() { throw new AssertionError("must not iterate message bodies"); }
    public static class Partition { public String topic() { return "billing-events"; } }
}
