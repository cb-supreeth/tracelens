package org.springframework.data.redis.core;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

public interface ValueOperations<K, V> {
    V get(Object key);
    void set(K key, V value);
    void set(K key, V value, long timeout, TimeUnit unit);
    default void set(K key, V value, Duration timeout) {
        set(key, value, timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
    Long increment(K key);
    Long increment(K key, long delta);
}
