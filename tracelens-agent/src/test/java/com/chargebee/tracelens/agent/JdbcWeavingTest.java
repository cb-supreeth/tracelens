package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.JdbcQueryRuntime;
import com.chargebee.tracelens.bridge.TraceContext;
import com.chargebee.tracelens.bridge.TraceRuntime;
import com.chargebee.tracelens.bridge.TraceScope;
import com.chargebee.tracelens.bridge.TraceSnapshot;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.ExceptionMethod;
import net.bytebuddy.implementation.FixedValue;
import net.bytebuddy.implementation.StubMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static net.bytebuddy.matcher.ElementMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real driver bytecode is woven without opening a socket; fixture successes check the full advice seam. */
class JdbcWeavingTest {
    private final List<Map<String, String>> events = new ArrayList<>();

    @BeforeAll static void install() throws Exception {
        Class.forName("com.mysql.cj.jdbc.ClientPreparedStatement"); // retransform an already-loaded driver
        JdbcInstrumentation.install(ByteBuddyAgent.install(), true, 4096, 100, true);
    }
    @BeforeEach void setup() {
        JdbcQueryRuntime.configure(true, 4096, 100);
        TraceRuntime.registerSink(new TraceRuntime.Sink() {
            public void onLine(String s, String c, int l) { }
            public void onMethodEnter(String s, String c, String m) { }
            public void onMethodExit(String s, String c, String m) { }
            public void onException(String s, String c, String m, String t, String e) { }
            public void onBoundary(String s, Map<String, String> fields) { events.add(fields); }
        });
    }
    @AfterEach void cleanup() { TraceContext.clear(); TraceRuntime.unregisterSink(); JdbcQueryRuntime.configure(false, 4096, 100); }
    private TraceScope scope() { return TraceContext.activate(new TraceSnapshot("trace", "segment", null, null, null)); }

    @Test void realPostgresPreparedDriverKeepsTemplateAndThrowsOriginalSqlException() throws Exception {
        PreparedStatement statement = (PreparedStatement) allocate("org.postgresql.jdbc.PgPreparedStatement");
        set(statement, "lock", new org.postgresql.jdbc.ResourceLock());
        set(statement, "isClosed", 1);
        JdbcQueryRuntime.prepared(statement, "select * from features where id = ?");
        try (TraceScope ignored = scope()) {
            assertThatThrownBy(statement::executeQuery).isInstanceOf(SQLException.class).hasMessageContaining("closed");
            assertThatThrownBy(statement::executeLargeUpdate).isInstanceOf(SQLException.class).hasMessageContaining("closed");
        }
        assertThat(events).hasSize(2);
        for (Map<String, String> event : events) {
            assertThat(event).containsEntry("system", "postgresql").containsEntry("status", "ERROR")
                    .containsEntry("sqlSource", "prepared").containsEntry("sql", "select * from features where id = ?")
                    .containsEntry("errorType", "org.postgresql.util.PSQLException");
        }
    }

    @Test void realMySqlPreparedDriverAndInheritedOverloadAreWovenOnce() throws Exception {
        PreparedStatement statement = (PreparedStatement) allocate("com.mysql.cj.jdbc.ClientPreparedStatement");
        JdbcQueryRuntime.prepared(statement, "update features set name = ? where id = ?");
        try (TraceScope ignored = scope()) {
            assertThatThrownBy(statement::executeUpdate).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.execute("select 'literal'", Statement.NO_GENERATED_KEYS)).isInstanceOf(SQLException.class);
        }
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).containsEntry("system", "mysql").containsEntry("sqlSource", "prepared")
                .containsEntry("sql", "update features set name = ? where id = ?");
        assertThat(events.get(1)).containsEntry("sql", "select 'literal'").containsEntry("sqlSource", "statement");
    }

    @Test void connectionPrepareAdviceCapturesOutsideTraceAndExecutionBatchesPreserveReturns() throws Exception {
        PreparedStatement statement = (PreparedStatement) new ByteBuddy().subclass(Object.class).implement(PreparedStatement.class)
                .name("org.postgresql.jdbc.TraceLensPreparedFixture")
                .method(isAbstract()).intercept(StubMethod.INSTANCE)
                .method(named("executeUpdate").and(takesArguments(0))).intercept(FixedValue.value(7))
                .method(named("executeBatch")).intercept(FixedValue.value(new int[]{1, 1}))
                .method(named("toString")).intercept(ExceptionMethod.throwing(AssertionError.class, "Do not inspect bindings"))
                .make().load(getClass().getClassLoader(), ClassLoadingStrategy.Default.WRAPPER_PERSISTENT).getLoaded().getDeclaredConstructor().newInstance();
        Connection connection = (Connection) new ByteBuddy().subclass(Object.class).implement(Connection.class)
                .name("org.postgresql.jdbc.TraceLensConnectionFixture")
                .method(isAbstract()).intercept(StubMethod.INSTANCE)
                .method(named("prepareStatement")).intercept(FixedValue.reference(statement))
                .make().load(statement.getClass().getClassLoader(), ClassLoadingStrategy.Default.WRAPPER).getLoaded().getDeclaredConstructor().newInstance();
        PreparedStatement prepared = connection.prepareStatement("insert into feature values (?, 'local literal')");
        prepared.setString(1, "secret binding");
        assertThat(events).isEmpty();
        prepared.addBatch(); prepared.addBatch();
        try (TraceScope ignored = scope()) {
            assertThat(prepared.executeUpdate()).isEqualTo(7);
            assertThat(prepared.executeBatch()).containsExactly(1, 1);
            prepared.close();
            assertThat(prepared.executeUpdate()).isEqualTo(7);
        }
        assertThat(events).hasSize(3);
        assertThat(events.get(0)).containsEntry("sql", "insert into feature values (?, 'local literal')")
                .containsEntry("sqlSource", "prepared").containsEntry("status", "OBSERVED");
        assertThat(events.get(1)).containsEntry("batchSize", "2");
        assertThat(events.get(2)).containsEntry("sqlSource", "unavailable").doesNotContainKey("sql");
        assertThat(events.toString()).doesNotContain("secret binding");
    }

    @Test void disabledAdviceLeavesRealDriverErrorsUnobserved() throws Exception {
        Statement statement = (Statement) allocate("com.mysql.cj.jdbc.StatementImpl");
        JdbcQueryRuntime.configure(false, 4096, 100);
        try (TraceScope ignored = scope()) {
            assertThatThrownBy(() -> statement.executeQuery("select 1")).isInstanceOf(SQLException.class);
        }
        assertThat(events).isEmpty();
    }

    @Test void optInBindingAdviceCapturesPrimitiveNullAndBatchesAndClearsOnlyOnSuccessfulReturn() throws Exception {
        JdbcQueryRuntime.configure(true, 4096, 100, true);
        PreparedStatement prepared = (PreparedStatement) new ByteBuddy().subclass(Object.class).implement(PreparedStatement.class)
                .name("org.postgresql.jdbc.TraceLensBindingsFixture")
                .method(isAbstract()).intercept(StubMethod.INSTANCE)
                .method(named("executeUpdate").and(takesArguments(0))).intercept(FixedValue.value(4))
                .method(named("toString")).intercept(ExceptionMethod.throwing(AssertionError.class, "Do not inspect statement"))
                .make().load(getClass().getClassLoader(), ClassLoadingStrategy.Default.WRAPPER_PERSISTENT).getLoaded().getDeclaredConstructor().newInstance();
        JdbcQueryRuntime.prepared(prepared, "insert into test values (?,?)");
        prepared.setInt(1, 17); prepared.setString(2, "first"); prepared.addBatch();
        prepared.setLong(1, 18L); prepared.setNull(2, java.sql.Types.VARCHAR); prepared.addBatch();
        try (TraceScope ignored = scope()) {
            assertThat(prepared.executeUpdate()).isEqualTo(4);
            prepared.executeBatch();
            prepared.clearParameters(); prepared.executeUpdate();
        }
        assertThat(events).hasSize(3);
        assertThat(events.get(0)).containsEntry("sqlParametersStatus", "captured");
        assertThat(events.get(0).get("sqlParameters")).contains("\"Long\"", "\"18\"", "\"status\":\"null\"");
        assertThat(events.get(1)).containsEntry("sqlParametersStatus", "batch").containsEntry("batchSize", "2");
        assertThat(events.get(1).get("sqlParameterSets")).contains("first", "\"17\"", "\"18\"");
        assertThat(events.get(2)).containsEntry("sqlParameters", "[]");
    }

    @Test void failedSetterAndFailedClearAdviceDoNotCommitBindings() throws Exception {
        JdbcQueryRuntime.configure(true, 4096, 100, true);
        PreparedStatement prepared = (PreparedStatement) new ByteBuddy().subclass(Object.class).implement(PreparedStatement.class)
                .name("com.mysql.cj.jdbc.TraceLensFailingBindingsFixture")
                .method(isAbstract()).intercept(StubMethod.INSTANCE)
                .method(named("setInt").or(named("clearParameters"))).intercept(ExceptionMethod.throwing(SQLException.class, "original driver error"))
                .make().load(getClass().getClassLoader(), ClassLoadingStrategy.Default.WRAPPER_PERSISTENT).getLoaded().getDeclaredConstructor().newInstance();
        JdbcQueryRuntime.prepared(prepared, "select ?");
        prepared.setString(1, "retained");
        assertThatThrownBy(() -> prepared.setInt(1, 999)).isInstanceOf(SQLException.class).hasMessage("original driver error");
        assertThatThrownBy(prepared::clearParameters).isInstanceOf(SQLException.class).hasMessage("original driver error");
        try (TraceScope ignored = scope()) { prepared.execute(); }
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("sqlParameters")).contains("retained").doesNotContain("999");
    }

    @Test void realClosedDriverSettersRemainFailOpenWithBindingsEnabled() throws Exception {
        JdbcQueryRuntime.configure(true, 4096, 100, true);
        PreparedStatement prepared = (PreparedStatement) allocate("org.postgresql.jdbc.PgPreparedStatement");
        set(prepared, "lock", new org.postgresql.jdbc.ResourceLock()); set(prepared, "isClosed", 1);
        JdbcQueryRuntime.prepared(prepared, "select ?");
        assertThatThrownBy(() -> prepared.setString(1, "not successfully bound")).isInstanceOf(SQLException.class);
        try (TraceScope ignored = scope()) { assertThatThrownBy(prepared::executeQuery).isInstanceOf(SQLException.class); }
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("sqlParameters", "[]").containsEntry("status", "ERROR");
    }

    // Closed driver instances need no sockets, credentials, server, result data or constructor side effects.
    private static Object allocate(String type) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return ((sun.misc.Unsafe) field.get(null)).allocateInstance(Class.forName(type));
    }
    private static void set(Object instance, String name, Object value) throws Exception {
        for (Class<?> type = instance.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(instance, value); return; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
