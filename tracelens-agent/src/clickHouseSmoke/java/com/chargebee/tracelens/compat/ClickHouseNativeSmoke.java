package com.chargebee.tracelens.compat;

import com.chargebee.tracelens.bridge.AgentStatus;
import com.chargebee.tracelens.bridge.ClickHouseQueryRuntime;
import com.chargebee.tracelens.bridge.TraceContext;
import com.chargebee.tracelens.bridge.TraceRuntime;
import com.chargebee.tracelens.bridge.TraceScope;
import com.chargebee.tracelens.bridge.TraceSnapshot;
import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QueryResponse;
import com.clickhouse.client.api.query.QuerySettings;
import com.clickhouse.client.api.query.Records;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/** Optional real native SDK 0.9.3 smoke. Constants-only SELECTs against loopback; never mutates application data. */
public final class ClickHouseNativeSmoke {
    private static final List<Map<String, String>> EVENTS = new CopyOnWriteArrayList<>();
    public static void main(String[] args) throws Exception {
        require(AgentStatus.agentActive(), "run with the packaged TraceLens javaagent");
        require(ClickHouseQueryRuntime.class.getClassLoader() == null, "bootstrap bridge required");
        TraceRuntime.registerSink(new TraceRuntime.Sink() {
            public void onLine(String s, String c, int l) { }
            public void onMethodEnter(String s, String c, String m) { }
            public void onMethodExit(String s, String c, String m) { }
            public void onException(String s, String c, String m, String t, String message) { }
            public void onBoundary(String s, Map<String, String> fields) {
                if ("clickhouse".equals(fields.get("system"))) EVENTS.add(fields);
            }
        });
        ClickHouseQueryRuntime.configure(true, 4096, 100, true);
        try (Client client = new Client.Builder().addEndpoint("http://127.0.0.1:8123")
                .setUsername(env("TRACELENS_CLICKHOUSE_USER", "default"))
                .setPassword(env("TRACELENS_CLICKHOUSE_PASSWORD", ""))
                .useAsyncRequests(true).build();
             TraceScope scope = TraceContext.activate(new TraceSnapshot("native-smoke", "native-segment", null, null, null))) {
            // The first direct canonical call captures only the real named binding map, not settings.
            try (QueryResponse ignored = client.query("SELECT {probe:UInt8} AS probe", Collections.singletonMap("probe", 7),
                    new QuerySettings()).get(15, TimeUnit.SECONDS)) { }
            await(1);
            require(EVENTS.size() == 1, "canonical query emitted duplicate metadata");
            require("clickhouse-query".equals(EVENTS.get(0).get("evidenceKind")), "native query not captured");
            require(EVENTS.get(0).get("sqlParameters").contains("\"value\":\"7\""), "named binding missing");
            require("response-complete".equals(EVENTS.get(0).get("completionKind")), "completion not observed");

            // The wrapper delegates through canonical query and must not emit a second ClickHouse boundary.
            try (Records rows = client.queryRecords("SELECT {probe:UInt8} AS probe", Collections.singletonMap("probe", 9),
                    new QuerySettings()).get(15, TimeUnit.SECONDS)) {
                require(rows.iterator().next().getInteger("probe") == 9, "application result changed");
            }
            await(2);
            require(EVENTS.size() == 2, "queryRecords emitted duplicate metadata");
            require(EVENTS.get(1).get("sqlParameters").contains("\"value\":\"9\""), "wrapper binding missing");

            try (com.clickhouse.client.api.command.CommandResponse ignored = client.execute("SELECT 1").get(15, TimeUnit.SECONDS)) { }
            await(3);
            require(EVENTS.size() == 3, "execute emitted duplicate metadata");
            require("OBSERVED".equals(EVENTS.get(2).get("status")), "execute completion not observed");

            // Disabling the opt-in restores metadata-only capture without changing successful execution.
            ClickHouseQueryRuntime.configure(false, 4096, 100, false);
            try (QueryResponse ignored = client.query("SELECT 2").get(15, TimeUnit.SECONDS)) { }
            await(4);
            require(EVENTS.size() == 4, "metadata-only query not observed exactly once");
            require(!EVENTS.get(3).containsKey("sql"), "disabled capture retained SQL");
            require("client-boundary".equals(EVENTS.get(3).get("evidenceKind")), "default metadata not restored");
            require(AgentStatus.failedCount() == 0, "instrumentation failure");
            System.out.println("CLICKHOUSE_NATIVE_SMOKE_OK queries=4 native=3 metadataOnly=1 namedBindings=2");
        } finally {
            TraceRuntime.unregisterSink(); TraceContext.clear(); ClickHouseQueryRuntime.configure(false, 4096, 100, false);
        }
    }
    private static void await(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (EVENTS.size() < count && System.nanoTime() < deadline) Thread.sleep(5);
        require(EVENTS.size() >= count, "native completion event missing");
    }
    private static String env(String key, String fallback) { String value = System.getenv(key); return value == null ? fallback : value; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
