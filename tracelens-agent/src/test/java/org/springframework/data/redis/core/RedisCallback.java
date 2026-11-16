package org.springframework.data.redis.core;

public interface RedisCallback<T> {
    T doInRedis(Object connection);
}
