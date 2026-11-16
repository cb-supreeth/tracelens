package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.ClickHouseQueryRuntime;
import com.chargebee.tracelens.bridge.DataStoreBoundaryRuntime;
import com.chargebee.tracelens.bridge.TraceContext;
import com.chargebee.tracelens.bridge.TraceRuntime;
import com.chargebee.tracelens.bridge.TraceScope;
import com.chargebee.tracelens.bridge.TraceSnapshot;
import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QuerySettings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Canonical signature verified against native client-v2 0.9.3; both production transformers installed. */
class ClickHouseQueryWeavingTest {
    private final List<Map<String, String>> events = new ArrayList<>();
    private TraceScope scope;

    @BeforeAll static void install() {
        new Client(); // exercise retransformation, not only first-load weaving
        DataStoreInstrumentation.install(ByteBuddyAgent.install(), true);
        ClickHouseInstrumentation.install(ByteBuddyAgent.install(), true, 4096, 100, true);
    }

    @BeforeEach void setup() {
        ClickHouseQueryRuntime.configure(true, 4096, 100, true);
        DataStoreBoundaryRuntime.configure(true);
        TraceRuntime.registerSink(new TraceRuntime.Sink() {
            public void onLine(String s, String c, int l) { }
            public void onMethodEnter(String s, String c, String m) { }
            public void onMethodExit(String s, String c, String m) { }
            public void onException(String s, String c, String m, String t, String message) { }
            public void onBoundary(String s, Map<String, String> fields) { events.add(fields); }
        });
        scope = TraceContext.activate(new TraceSnapshot("trace", "segment", "lens", null, null));
    }

    @AfterEach void cleanup() {
        scope.close(); TraceContext.clear(); TraceRuntime.unregisterSink();
        ClickHouseQueryRuntime.configure(false, 4096, 100, false); DataStoreBoundaryRuntime.configure(false);
    }

    @Test void canonicalQueryPreservesFutureAndCapturesOnlyRealNamedParameters() {
        Client client = completed();
        Object future = client.query("SELECT {item:String}", Collections.singletonMap("item", "item-1"), new QuerySettings());
        assertThat(future).isSameAs(client.response);
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("evidenceKind", "clickhouse-query")
                .containsEntry("status", "OBSERVED").containsEntry("completionKind", "response-complete")
                .containsEntry("sql", "SELECT {item:String}").containsEntry("sqlSource", "native-query");
        assertThat(events.get(0).get("sqlParameters")).contains("\"name\":\"item\"", "item-1");
    }

    @Test void publicWrappersDeduplicateMetadataButInsertMetadataIsPreserved() {
        Client client = completed();
        client.execute("SELECT 1");
        client.queryRecords("SELECT 2", new QuerySettings());
        client.getTableSchema("test_table");
        client.getTableSchemaFromQuery("SELECT 3");
        client.query("SELECT 4");
        client.query("SELECT {n:Int32}", Collections.singletonMap("n", 4));
        client.insert("private_table", Collections.singletonList(new Poison()), new Poison());
        assertThat(events).hasSize(7);
        assertThat(events.subList(0, 6)).allSatisfy(e -> assertThat(e).containsEntry("evidenceKind", "clickhouse-query"));
        assertThat(events.get(6)).containsEntry("operation", "insert").containsEntry("evidenceKind", "client-boundary");
        assertThat(events.toString()).doesNotContain("private_table");
    }

    @Test void disabledNativeCaptureRestoresExistingMetadataAndDoesNotReadSqlOrFuture() {
        ClickHouseQueryRuntime.configure(false, 4096, 100, true);
        Client client = new Client();
        client.execute("private SQL"); client.query("private SQL", Collections.singletonMap("secret", new Poison()), new QuerySettings());
        assertThat(events).hasSize(2).allSatisfy(e -> assertThat(e)
                .containsEntry("evidenceKind", "client-boundary").containsEntry("status", "SUBMITTED"));
        assertThat(events.toString()).doesNotContain("private", "secret", "sqlParameters");
    }

    @Test void asyncFailureAndSynchronousFailureRemainApplicationFailures() {
        Client client = new Client();
        client.response = new CompletableFuture<>();
        RuntimeException remote = new IllegalStateException("private server SQL");
        assertThat(client.execute("SELECT 1")).isSameAs(client.response);
        assertThat(events).isEmpty();
        client.response.completeExceptionally(remote);
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("status", "ERROR").containsEntry("errorType", remote.getClass().getName());
        client.failure = new IllegalArgumentException("private settings");
        assertThatThrownBy(() -> client.execute("SELECT 2")).isSameAs(client.failure);
        assertThat(events).hasSize(2);
        assertThat(events.get(1)).containsEntry("completionKind", "immediate-error").containsEntry("status", "ERROR");
        assertThat(events.toString()).doesNotContain("private server SQL", "private settings");
    }

    private Client completed() {
        Client client = new Client(); client.response = CompletableFuture.completedFuture(new Poison()); return client;
    }
    private static final class Poison {
        @Override public String toString() { throw new AssertionError("object inspected"); }
    }
}
