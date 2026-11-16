package org.springframework.data.redis.core;

import java.util.Map;

public interface HashOperations<K, HK, HV> {
    HV get(K key, Object hashKey);
    Long increment(K key, HK hashKey, long delta);
    Map<HK, HV> entries(K key);
}
