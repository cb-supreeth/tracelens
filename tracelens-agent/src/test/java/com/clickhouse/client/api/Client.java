package com.clickhouse.client.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import com.clickhouse.client.api.query.QuerySettings;

/** SDK-shaped fixture: no client library dependency and no network. */
public class Client {
    public CompletableFuture<Object> response = new PoisonFuture();
    public RuntimeException failure;

    public CompletableFuture<Object> query(String sql, Map<String, Object> parameters, QuerySettings settings) {
        if (failure != null) throw failure;
        return response;
    }

    public CompletableFuture<Object> query(String sql) { return query(sql, null, null); }

    public CompletableFuture<Object> query(String sql, Map<String, Object> parameters) {
        return query(sql, parameters, null);
    }

    public CompletableFuture<Object> execute(String sql) {
        return query(sql, null, null);
    }

    public CompletableFuture<Object> queryRecords(String sql, Object settings) {
        return query(sql, null, null);
    }

    public Object getTableSchema(String table) {
        query("SELECT secret FROM " + table, null, null);
        return new Object();
    }

    public Object getTableSchemaFromQuery(String query) {
        query(query, null, null);
        return new Object();
    }

    public CompletableFuture<Object> insert(String table, List<?> data, Object settings) {
        if (failure != null) throw failure;
        return response;
    }

    public String getEndpoint() {
        return "https://secret-user:secret-password@private.invalid";
    }

    public static final class PoisonFuture extends CompletableFuture<Object> {
        @Override public boolean isDone() { throw new AssertionError("future inspected"); }
        @Override public Object join() { throw new AssertionError("future awaited"); }
        @Override public Object get() { throw new AssertionError("future awaited"); }
        @Override public String toString() { throw new AssertionError("future inspected"); }
    }
}
