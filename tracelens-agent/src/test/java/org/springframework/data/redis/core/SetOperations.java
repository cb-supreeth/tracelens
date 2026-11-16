package org.springframework.data.redis.core;

import java.util.Set;

public interface SetOperations<K, V> {
    Long add(K key, V... values);
    Set<V> members(K key);
}
