package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.JdbcQueryRuntime;
import com.chargebee.tracelens.bridge.TraceContext;
import com.chargebee.tracelens.bridge.TraceRuntime;
import com.chargebee.tracelens.bridge.TraceScope;
import com.chargebee.tracelens.bridge.TraceSnapshot;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Explicit opt-in smoke test against disposable databases only. Never uses application credentials. */
@EnabledIfEnvironmentVariable(named = "TRACELENS_JDBC_SMOKE", matches = "true")
class JdbcDatabaseIntegrationTest {
    private final List<Map<String, String>> events = new ArrayList<>();

    @BeforeAll static void install() {
        JdbcInstrumentation.install(ByteBuddyAgent.install(), true, 4096, 100, true);
    }

    @BeforeEach void record() {
        JdbcQueryRuntime.configure(true, 4096, 100);
        TraceRuntime.registerSink(new TraceRuntime.Sink() {
            public void onLine(String id, String owner, int line) { }
            public void onMethodEnter(String id, String owner, String method) { }
            public void onMethodExit(String id, String owner, String method) { }
            public void onException(String id, String owner, String method, String type, String message) { }
            public void onBoundary(String id, Map<String, String> fields) { events.add(new LinkedHashMap<>(fields)); }
        });
    }

    @AfterEach void cleanup() {
        TraceContext.clear();
        TraceRuntime.unregisterSink();
        JdbcQueryRuntime.configure(false, 4096, 100);
    }

    @ParameterizedTest @ValueSource(strings = {"postgresql", "mysql"})
    void capturesRealStatementsAndPreparedReuseWithoutBindingsOrDuplicateEvents(String system) throws Exception {
        try (Connection c = connect(system);
             PreparedStatement prepared = c.prepareStatement("SELECT ? AS value");
             Statement statement = c.createStatement()) {
            assertThat(events).isEmpty(); // Preparation alone is not query execution.
            prepared.setString(1, "secret-bound-value-not-to-record");
            try (TraceScope scope = scope(null)) {
                for (int i = 0; i < 2; i++) {
                    try (ResultSet rs = prepared.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getString(1)).isEqualTo("secret-bound-value-not-to-record");
                    }
                }
                try (ResultSet rs = statement.executeQuery("SELECT 'local-literal-visible' AS value")) {
                    assertThat(rs.next()).isTrue();
                }
            }
            assertThat(events).hasSize(3);
            for (Map<String, String> e : events) {
                assertThat(e).containsEntry("system", system).containsEntry("evidenceKind", "jdbc-execution")
                        .containsEntry("status", "OBSERVED").containsKey("durationMs");
                assertThat(Double.parseDouble(e.get("durationMs"))).isGreaterThanOrEqualTo(0);
                assertThat(e.toString()).doesNotContain("secret-bound-value-not-to-record");
            }
            assertThat(events.get(0)).containsEntry("sql", "SELECT ? AS value");
            assertThat(events.get(1)).containsEntry("sql", "SELECT ? AS value");
            assertThat(events.get(2)).containsEntry("sql", "SELECT 'local-literal-visible' AS value");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"postgresql", "mysql"})
    void preservesDatabaseExceptionsAndObservesBatchesOnce(String system) throws Exception {
        try (Connection c = connect(system); Statement statement = c.createStatement()) {
            statement.executeUpdate("CREATE TEMPORARY TABLE tl_jdbc_smoke (id INTEGER)");
            statement.addBatch("INSERT INTO tl_jdbc_smoke VALUES (1)");
            statement.addBatch("INSERT INTO tl_jdbc_smoke VALUES (2)");
            try (TraceScope scope = scope(null)) {
                assertThat(statement.executeBatch()).hasSize(2);
                assertThatThrownBy(() -> statement.executeQuery("SELECT * FROM tl_jdbc_missing_table"))
                        .isInstanceOf(SQLException.class);
            }
            assertThat(events).hasSize(2);
            assertThat(events.get(0)).containsEntry("operation", "executeBatch").containsEntry("batchSize", "2")
                    .containsEntry("status", "OBSERVED");
            assertThat(events.get(1)).containsEntry("status", "ERROR").containsKey("errorType");
            assertThat(events.get(1).keySet()).doesNotContain("errorMessage", "parameters", "rows");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"postgresql", "mysql"})
    void capturesPreparedBatchTemplateWithoutRewrittenBoundValues(String system) throws Exception {
        try (Connection c = connect(system); Statement setup = c.createStatement()) {
            setup.executeUpdate("CREATE TEMPORARY TABLE tl_jdbc_prepared (value VARCHAR(100))");
            String template = "INSERT INTO tl_jdbc_prepared (value) VALUES (?)";
            try (PreparedStatement prepared = c.prepareStatement(template)) {
                prepared.setString(1, "private-first-bound-value");
                prepared.addBatch();
                prepared.setString(1, "private-second-bound-value");
                prepared.addBatch();
                try (TraceScope scope = scope(null)) {
                    assertThat(prepared.executeBatch()).hasSize(2);
                }
                assertThat(events).hasSize(1);
                assertThat(events.get(0)).containsEntry("sql", template).containsEntry("batchSize", "2")
                        .containsEntry("status", "OBSERVED").containsEntry("sqlSource", "prepared");
                assertThat(events.toString()).doesNotContain("private-first-bound-value", "private-second-bound-value");
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"postgresql", "mysql"})
    void excludesUntracedOrSkippedRequestsAndReportsQueryLimit(String system) throws Exception {
        try (Connection c = connect(system); Statement statement = c.createStatement()) {
            statement.execute("SELECT 1");
            try (TraceScope scope = scope(Collections.singletonMap("captureSkipped", "path-filter"))) {
                statement.execute("SELECT 2");
            }
            assertThat(events).isEmpty();
            JdbcQueryRuntime.configure(true, 32, 2);
            try (TraceScope scope = scope(null)) {
                statement.execute("SELECT 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ'");
                statement.execute("SELECT 3");
                statement.execute("SELECT 4");
                statement.execute("SELECT 5");
            }
            assertThat(events).hasSize(3);
            assertThat(events.get(0).get("sql")).hasSizeLessThanOrEqualTo(32);
            assertThat(events.get(0)).containsEntry("sqlTruncated", "true");
            assertThat(events.get(2)).containsEntry("evidenceKind", "jdbc-query-limit")
                    .containsEntry("status", "TRUNCATED").containsEntry("queryLimit", "2");
            events.clear();
            JdbcQueryRuntime.configure(false, 4096, 100);
            try (TraceScope scope = scope(null)) { statement.execute("SELECT 6"); }
            assertThat(events).isEmpty();
        }
    }

    private TraceScope scope(Map<String, String> attributes) {
        return TraceContext.activate(new TraceSnapshot(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "jdbc-smoke", "local-fixture", attributes));
    }

    @ParameterizedTest @ValueSource(strings = {"postgresql", "mysql"})
    void explicitlyEnabledBindingsCaptureRealPreparedReuseAndPreserveSqlTemplate(String system) throws Exception {
        JdbcQueryRuntime.configure(true, 4096, 100, true);
        try (Connection c = connect(system); PreparedStatement prepared = c.prepareStatement("SELECT ? AS value")) {
            prepared.setString(1, "local first value");
            try (TraceScope scope = scope(null)) {
                try (ResultSet result = prepared.executeQuery()) {
                    assertThat(result.next()).isTrue(); assertThat(result.getString(1)).isEqualTo("local first value");
                }
                prepared.clearParameters(); prepared.setString(1, "local second value");
                try (ResultSet result = prepared.executeQuery()) {
                    assertThat(result.next()).isTrue(); assertThat(result.getString(1)).isEqualTo("local second value");
                }
            }
            assertThat(events).hasSize(2);
            assertThat(events.get(0)).containsEntry("sqlParametersStatus", "captured").containsEntry("sql", "SELECT ? AS value");
            assertThat(events.get(0).get("sqlParameters")).contains("local first value").doesNotContain("local second value");
            assertThat(events.get(1).get("sqlParameters")).contains("local second value").doesNotContain("local first value");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"postgresql", "mysql"})
    void explicitlyEnabledBatchBindingsAreSnapshotsNotDriverRewriteValues(String system) throws Exception {
        JdbcQueryRuntime.configure(true, 4096, 100, true);
        try (Connection c = connect(system); Statement setup = c.createStatement()) {
            setup.executeUpdate("CREATE TEMPORARY TABLE tl_jdbc_binding_batch (value VARCHAR(100))");
            try (PreparedStatement prepared = c.prepareStatement("INSERT INTO tl_jdbc_binding_batch (value) VALUES (?)")) {
                prepared.setString(1, "queued first"); prepared.addBatch();
                prepared.setString(1, "queued second"); prepared.addBatch();
                prepared.setString(1, "not queued");
                try (TraceScope scope = scope(null)) { assertThat(prepared.executeBatch()).hasSize(2); }
                assertThat(events).hasSize(1);
                assertThat(events.get(0)).containsEntry("sqlParametersStatus", "batch").containsEntry("batchSize", "2");
                assertThat(events.get(0).get("sqlParameterSets")).contains("queued first", "queued second").doesNotContain("not queued");
            }
        }
    }

    private Connection connect(String system) throws Exception {
        String variable = "mysql".equals(system) ? "TRACELENS_MYSQL_URL" : "TRACELENS_POSTGRES_URL";
        String url = System.getenv(variable);
        assertThat(url).as("loopback URL of a disposable test database").startsWith("jdbc:" + system + "://127.0.0.1:");
        return DriverManager.getConnection(url, "tracelens", "tracelens-jdbc-local");
    }
}
