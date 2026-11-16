package org.springframework.data.redis.core;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Mirrors package-private operation classes and generic bridge methods. */
public class RedisTemplate<K, V> {
    public V response;
    public RuntimeException failure;

    public ValueOperations<K, V> opsForValue() { return new DefaultValueOperations<>(this); }
    public HashOperations<K, Object, Object> opsForHash() { return new DefaultHashOperations<>(this); }
    public SetOperations<K, V> opsForSet() { return new DefaultSetOperations<>(this); }
    public Boolean delete(K key) { return execute(connection -> true); }
    public Boolean unlink(K key) { return execute(connection -> true); }
    public Boolean expire(K key, long timeout, TimeUnit unit) { return execute(connection -> true); }
    public Long convertAndSend(String channel, Object value) { return execute(connection -> 1L); }

    public <T> T execute(RedisCallback<T> callback) { return execute(callback, false, false); }
    public <T> T execute(RedisCallback<T> callback, boolean exposeConnection, boolean pipeline) {
        if (failure != null) throw failure;
        return callback.doInRedis(new Object());
    }
}

class DefaultValueOperations<K, V> implements ValueOperations<K, V> {
    private final RedisTemplate<K, V> template;
    DefaultValueOperations(RedisTemplate<K, V> template) { this.template = template; }
    public V get(Object key) { return template.execute(connection -> template.response); }
    public void set(K key, V value) { template.execute(connection -> null); }
    public void set(K key, V value, long timeout, TimeUnit unit) { set(key, value); }
    public Long increment(K key) { return increment(key, 1); }
    public Long increment(K key, long delta) { return template.execute(connection -> delta); }
}

class DefaultHashOperations<K, HK, HV> implements HashOperations<K, HK, HV> {
    private final RedisTemplate<K, ?> template;
    DefaultHashOperations(RedisTemplate<K, ?> template) { this.template = template; }
    public HV get(K key, Object hashKey) { return template.execute(connection -> null); }
    public Long increment(K key, HK hashKey, long delta) { return template.execute(connection -> delta); }
    public Map<HK, HV> entries(K key) { return template.execute(connection -> Collections.emptyMap()); }
}

class DefaultSetOperations<K, V> implements SetOperations<K, V> {
    private final RedisTemplate<K, V> template;
    DefaultSetOperations(RedisTemplate<K, V> template) { this.template = template; }
    public Long add(K key, V... values) { return template.execute(connection -> (long) values.length); }
    public Set<V> members(K key) { return template.execute(connection -> Collections.emptySet()); }
}
