package com.chargebee.tracelens.bridge;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * SDK-free, payload-free observation of a bounded set of known client APIs.
 * Never accepts client objects, arguments, results, credentials, URLs, SQL, or keys.
 * A returned Future is SUBMITTED, not successful; synchronous return is OBSERVED,
 * not proof of a cache hit, a committed transaction, or remote durability.
 */
public final class DataStoreBoundaryRuntime {
    private static final int MAX_DEPTH = 128;
    private static final ThreadLocal<Boolean> CALLBACK = new ThreadLocal<>();
    private static final Map<String, Client> CLIENTS = clients();
    private static volatile boolean captureEnabled;

    private DataStoreBoundaryRuntime() { }

    public static void configure(boolean capture) {
        captureEnabled = capture;
    }

    /** Shared with the agent so installation and runtime validation cannot drift. */
    public static Set<String> supportedTypes() {
        return CLIENTS.keySet();
    }

    public static Set<String> supportedOperations(String owner) {
        Client client = CLIENTS.get(owner);
        return client == null ? Collections.<String>emptySet() : client.operations;
    }

    public static Object enter(String owner, String operation, boolean asynchronous) {
        return enter(owner, operation, asynchronous, null);
    }

    public static Object enter(String owner, String operation, boolean asynchronous, String descriptor) {
        try {
            if (!captureEnabled || Boolean.TRUE.equals(CALLBACK.get()) || !TraceRuntime.hasSink()) return null;
            if (ClickHouseQueryRuntime.isEnabled() && "com.clickhouse.client.api.Client".equals(owner)
                    && "query".equals(operation)
                    && "(Ljava/lang/String;Ljava/util/Map;Lcom/clickhouse/client/api/query/QuerySettings;)Ljava/util/concurrent/CompletableFuture;".equals(descriptor)) return null;
            String segmentId = TraceContext.currentSegmentId();
            if (segmentId == null) return null;
            Client client = CLIENTS.get(owner);
            if (client == null || !client.operations.contains(operation)) return null;
            SystemDefinition system = client.system;
            Call existing = system.current.get();
            // Public overloads and template callbacks delegate within the same client.
            // Observe the outer operation once, independently for each known system.
            if (existing != null) {
                if (existing.depth >= MAX_DEPTH) return null;
                existing.depth++;
                return existing;
            }
            Call call = new Call(system, segmentId, operation, asynchronous);
            system.current.set(call);
            return call;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void exit(Object token, Throwable failure) {
        if (!(token instanceof Call)) return;
        Call call = (Call) token;
        try {
            if (call.finished || --call.depth > 0) return;
            call.finished = true;
            if (call.nativeQueryObserved) return;
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("boundaryKind", call.system.kind);
            fields.put("system", call.system.name);
            fields.put("direction", "outbound");
            fields.put("operation", call.operation);
            fields.put("evidenceKind", "client-boundary");
            fields.put("status", failure != null ? "ERROR" : call.asynchronous ? "SUBMITTED" : "OBSERVED");
            if (failure != null) {
                String type = failure.getClass().getName();
                fields.put("errorType", type.substring(0, Math.min(256, type.length())));
            }
            if (!Boolean.TRUE.equals(CALLBACK.get())) {
                CALLBACK.set(Boolean.TRUE);
                try { TraceRuntime.onBoundary(call.segmentId, fields); }
                finally { CALLBACK.remove(); }
            }
        } catch (Throwable ignored) {
            // Optional observation must not affect the client's return or exception.
        } finally {
            if (call.depth <= 0 && call.system.current.get() == call) call.system.current.remove();
        }
    }

    /** The native query hook replaces only an enclosing query wrapper's metadata event. */
    public static void nativeClickHouseQueryObserved() {
        Client client = CLIENTS.get("com.clickhouse.client.api.Client");
        Call call = client.system.current.get();
        if (call != null && !"insert".equals(call.operation)) call.nativeQueryObserved = true;
    }

    private static Map<String, Client> clients() {
        Map<String, Client> clients = new LinkedHashMap<>();
        SystemDefinition clickhouse = new SystemDefinition("clickhouse", "database");
        SystemDefinition redis = new SystemDefinition("redis", "cache");
        SystemDefinition dynamodb = new SystemDefinition("dynamodb", "database");
        SystemDefinition s3 = new SystemDefinition("s3", "external");
        add(clients, "com.clickhouse.client.api.Client", clickhouse,
                "query", "queryRecords", "execute", "insert", "getTableSchema", "getTableSchemaFromQuery");
        add(clients, "org.springframework.data.redis.core.DefaultValueOperations", redis,
                "get", "set", "setIfAbsent", "setIfPresent", "multiGet", "multiSet", "multiSetIfAbsent",
                "getAndDelete", "getAndExpire", "getAndPersist", "getAndSet", "increment", "decrement",
                "append", "size", "getBit", "setBit", "bitField");
        add(clients, "org.springframework.data.redis.core.DefaultHashOperations", redis,
                "get", "hasKey", "multiGet", "increment", "keys", "lengthOfValue", "size", "putAll",
                "put", "putIfAbsent", "values", "entries", "delete", "scan", "randomKey", "randomKeys",
                "randomEntry", "randomEntries");
        add(clients, "org.springframework.data.redis.core.DefaultSetOperations", redis,
                "add", "remove", "pop", "move", "size", "isMember", "intersect", "intersectAndStore",
                "union", "unionAndStore", "difference", "differenceAndStore", "members", "randomMember",
                "distinctRandomMembers", "randomMembers", "scan");
        // StringRedisTemplate inherits these methods. Callback execution is one
        // boundary for the whole callback, never an exact Redis command count.
        add(clients, "org.springframework.data.redis.core.RedisTemplate", redis,
                "delete", "unlink", "hasKey", "countExistingKeys", "expire", "expireAt", "getExpire",
                "persist", "keys", "scan", "randomKey", "rename", "renameIfAbsent", "type", "move",
                "convertAndSend", "execute", "executePipelined");
        add(clients, "com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient", dynamodb,
                "putItem", "getItem", "query", "scan", "deleteItem", "updateItem", "batchGetItem", "batchWriteItem");
        add(clients, "com.amazonaws.services.s3.AmazonS3Client", s3,
                "putObject", "getObject", "deleteObject", "listObjects", "listObjectsV2");
        return Collections.unmodifiableMap(clients);
    }

    private static void add(Map<String, Client> clients, String owner, SystemDefinition system, String... operations) {
        clients.put(owner, new Client(system, Collections.unmodifiableSet(
                new LinkedHashSet<>(Arrays.asList(operations)))));
    }

    private static final class Client {
        final SystemDefinition system;
        final Set<String> operations;
        Client(SystemDefinition system, Set<String> operations) {
            this.system = system;
            this.operations = operations;
        }
    }

    private static final class SystemDefinition {
        final String name;
        final String kind;
        final ThreadLocal<Call> current = new ThreadLocal<>();
        SystemDefinition(String name, String kind) {
            this.name = name;
            this.kind = kind;
        }
    }

    private static final class Call {
        final SystemDefinition system;
        final String segmentId;
        final String operation;
        final boolean asynchronous;
        int depth = 1;
        boolean finished;
        boolean nativeQueryObserved;
        Call(SystemDefinition system, String segmentId, String operation, boolean asynchronous) {
            this.system = system;
            this.segmentId = segmentId;
            this.operation = operation;
            this.asynchronous = asynchronous;
        }
    }
}
