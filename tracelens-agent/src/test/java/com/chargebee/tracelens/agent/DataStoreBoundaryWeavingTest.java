package com.chargebee.tracelens.agent;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.s3.AmazonS3Client;
import com.chargebee.tracelens.bridge.DataStoreBoundaryRuntime;
import com.chargebee.tracelens.bridge.TraceContext;
import com.chargebee.tracelens.bridge.TraceRuntime;
import com.chargebee.tracelens.bridge.TraceScope;
import com.chargebee.tracelens.bridge.TraceSnapshot;
import com.clickhouse.client.api.Client;
import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises production install matchers without SDK dependencies or a network. */
class DataStoreBoundaryWeavingTest {
    private final List<Map<String, String>> events = new ArrayList<>();
    private TraceScope scope;

    @BeforeAll static void install() {
        // Preloaded parent class must be retransformed; its subclass is loaded later.
        new RedisTemplate<>();
        DataStoreInstrumentation.install(ByteBuddyAgent.install(), true);
    }

    @BeforeEach void setup() {
        DataStoreBoundaryRuntime.configure(true);
        TraceRuntime.registerSink(new TraceRuntime.Sink() {
            public void onLine(String segment, String owner, int line) { }
            public void onMethodEnter(String segment, String owner, String method) { }
            public void onMethodExit(String segment, String owner, String method) { }
            public void onException(String segment, String owner, String method, String type, String message) { }
            public void onBoundary(String segment, Map<String, String> fields) { events.add(fields); }
        });
        scope = TraceContext.activate(new TraceSnapshot("trace", "segment", "lens", "user", null));
    }

    @AfterEach void cleanup() {
        scope.close();
        TraceContext.clear();
        TraceRuntime.unregisterSink();
        DataStoreBoundaryRuntime.configure(false);
    }

    @Test void clickHouseOverloadsEmitOneEventPreserveFutureAndDoNotInspectSqlOrResults() {
        Client client = new Client();
        assertThat(client.execute("secret sql")).isSameAs(client.response);
        assertBoundary(0, "clickhouse", "database", "execute", "SUBMITTED");
        assertThat(client.queryRecords("secret sql", new Poison())).isSameAs(client.response);
        assertBoundary(1, "clickhouse", "database", "queryRecords", "SUBMITTED");
        client.getTableSchema("secret table");
        assertBoundary(2, "clickhouse", "database", "getTableSchema", "OBSERVED");
        client.getTableSchemaFromQuery("secret sql");
        assertBoundary(3, "clickhouse", "database", "getTableSchemaFromQuery", "OBSERVED");
        assertThat(client.insert("secret table", Collections.singletonList(new Poison()), new Poison()))
                .isSameAs(client.response);
        assertBoundary(4, "clickhouse", "database", "insert", "SUBMITTED");
        client.getEndpoint();
        assertThat(events).hasSize(5);
        assertPrivateMetadataOnly();
    }

    @Test void failedOrCancelledFuturesRemainSubmittedWithoutCompletionObservation() {
        Client client = new Client();
        client.response = new java.util.concurrent.CompletableFuture<>();
        client.response.completeExceptionally(new IllegalStateException("secret remote failure"));
        assertThat(client.execute("secret")).isSameAs(client.response);
        client.response = new java.util.concurrent.CompletableFuture<>();
        client.response.cancel(false);
        assertThat(client.execute("secret")).isSameAs(client.response);
        assertThat(events).hasSize(2);
        assertBoundary(0, "clickhouse", "database", "execute", "SUBMITTED");
        assertBoundary(1, "clickhouse", "database", "execute", "SUBMITTED");
        assertPrivateMetadataOnly();
    }

    @Test void clickHouseLocalFailureIsRecordedAndDoesNotLeaveNestedState() {
        Client client = new Client();
        RuntimeException failure = new IllegalStateException("secret sql details");
        client.failure = failure;
        assertThatThrownBy(() -> client.execute("secret")).isSameAs(failure);
        assertBoundary(0, "clickhouse", "database", "execute", "ERROR");
        assertThat(events.get(0)).containsEntry("errorType", "java.lang.IllegalStateException");
        client.failure = null;
        client.execute("secret");
        assertBoundary(1, "clickhouse", "database", "execute", "SUBMITTED");
        assertThat(events).hasSize(2);
        assertPrivateMetadataOnly();
    }

    @Test void redisOperationsAndInheritedTemplateMethodsDeduplicateCallbacksWithoutClaimingHits() {
        RedisTemplate<Object, Object> template = new RedisTemplate<>();
        Object secret = new Poison();
        assertThat(template.opsForValue().get(secret)).isNull();
        template.response = secret;
        assertThat(template.opsForValue().get(secret)).isSameAs(secret);
        template.opsForValue().set(secret, secret);
        template.opsForValue().increment(secret);
        template.opsForHash().get(secret, secret);
        template.opsForHash().increment(secret, secret, 1);
        template.opsForHash().entries(secret);
        template.opsForSet().add(secret, secret);
        template.opsForSet().members(secret);
        StringRedisTemplate strings = new StringRedisTemplate();
        strings.opsForValue().set("secret key", "secret value", Duration.ofSeconds(1));
        strings.delete("secret key");
        strings.unlink("secret key");
        strings.convertAndSend("secret channel", secret);
        strings.execute(connection -> secret);
        assertThat(events).extracting(event -> event.get("operation"))
                .containsExactly("get", "get", "set", "increment", "get", "increment", "entries",
                        "add", "members", "set", "delete", "unlink", "convertAndSend", "execute");
        for (int i = 0; i < events.size(); i++) {
            assertBoundary(i, "redis", "cache", events.get(i).get("operation"), "OBSERVED");
        }
        assertPrivateMetadataOnly();
    }

    @Test void redisFailuresPropagateUnchangedAndFollowingCallsRemainObservable() {
        StringRedisTemplate template = new StringRedisTemplate();
        RuntimeException failure = new IllegalStateException("secret cache details");
        template.failure = failure;
        assertThatThrownBy(() -> template.opsForValue().get("secret")).isSameAs(failure);
        assertBoundary(0, "redis", "cache", "get", "ERROR");
        template.failure = null;
        template.opsForValue().get("secret");
        assertBoundary(1, "redis", "cache", "get", "OBSERVED");
        assertThat(events).hasSize(2);
        assertPrivateMetadataOnly();
    }

    @Test void awsClientRequestsAreObservedButLocalSigningAndLifecycleAreNot() {
        Object request = new Poison();
        AmazonDynamoDBClient dynamodb = new AmazonDynamoDBClient();
        assertThat(dynamodb.putItem(request)).isSameAs(request);
        dynamodb.query(request);
        dynamodb.deleteItem(request);
        dynamodb.shutdown();
        AmazonS3Client s3 = new AmazonS3Client();
        s3.putObject("secret bucket", "secret key", new File("secret file"));
        s3.generatePresignedUrl("secret bucket", "secret key");
        assertThat(events).hasSize(4);
        assertBoundary(0, "dynamodb", "database", "putItem", "OBSERVED");
        assertBoundary(1, "dynamodb", "database", "query", "OBSERVED");
        assertBoundary(2, "dynamodb", "database", "deleteItem", "OBSERVED");
        assertBoundary(3, "s3", "external", "putObject", "OBSERVED");
        assertPrivateMetadataOnly();
    }

    @Test void disabledUntracedAndUnrelatedLookalikesRemainUnobserved() {
        new LookalikeClient().query("secret");
        new LookalikeClient().get("secret");
        DataStoreBoundaryRuntime.configure(false);
        new Client().execute("secret");
        DataStoreBoundaryRuntime.configure(true);
        TraceContext.clear();
        new Client().execute("secret");
        new StringRedisTemplate().opsForValue().get("secret");
        assertThat(events).isEmpty();
    }

    private void assertBoundary(int index, String system, String kind, String operation, String status) {
        assertThat(events.get(index)).containsEntry("system", system).containsEntry("boundaryKind", kind)
                .containsEntry("operation", operation).containsEntry("status", status)
                .containsEntry("direction", "outbound").containsEntry("evidenceKind", "client-boundary");
    }

    private void assertPrivateMetadataOnly() {
        assertThat(events.toString()).doesNotContain("secret", "private", "HIT", "MISS", "COMPLETED");
        for (Map<String, String> event : events) {
            assertThat(event.keySet()).isSubsetOf("system", "boundaryKind", "direction", "operation",
                    "evidenceKind", "status", "errorType");
        }
    }

    public static class Poison {
        @Override public String toString() { throw new AssertionError("payload inspected"); }
    }

    public static class LookalikeClient {
        public Object query(Object value) { return value; }
        public Object get(Object value) { return value; }
    }
}
