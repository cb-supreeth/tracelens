package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.AgentStatus;
import com.chargebee.tracelens.bridge.JdbcQueryRuntime;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

import static net.bytebuddy.matcher.ElementMatchers.*;

/** Driver-level hooks; no application dependency on JDBC driver implementation classes. */
public final class JdbcInstrumentation {
    private JdbcInstrumentation() { }

    public static void install(Instrumentation instrumentation, boolean enabled, int maxSqlLength, int maxQueriesPerRequest) {
        install(instrumentation, enabled, maxSqlLength, maxQueriesPerRequest, false);
    }

    public static void install(Instrumentation instrumentation, boolean enabled, int maxSqlLength, int maxQueriesPerRequest,
                               boolean captureParameters) {
        JdbcQueryRuntime.configure(enabled, maxSqlLength, maxQueriesPerRequest, captureParameters);
        AgentStatus.configureIntegration("jdbc", enabled);
        if (!enabled) return;
        try {
            ElementMatcher.Junction<TypeDescription> drivers = nameStartsWith("org.postgresql.jdbc.")
                    .or(nameStartsWith("com.mysql.cj.jdbc.")).or(nameStartsWith("com.mysql.jdbc."));
            ElementMatcher.Junction<MethodDescription> methods = isMethod().and(isPublic()).and(not(isStatic()))
                    .and(not(isAbstract())).and(not(isNative())).and(not(isSynthetic()));
            ElementMatcher.Junction<MethodDescription> execute = methods.and(namedOneOf("execute", "executeQuery",
                    "executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch"));
            ElementMatcher.Junction<MethodDescription> binding = methods.and(takesArgument(0, int.class))
                    .and(namedOneOf("setNull", "setBoolean", "setByte", "setShort", "setInt", "setLong", "setFloat",
                            "setDouble", "setBigDecimal", "setString", "setBytes", "setDate", "setTime", "setTimestamp",
                            "setAsciiStream", "setUnicodeStream", "setBinaryStream", "setObject", "setCharacterStream",
                            "setRef", "setBlob", "setClob", "setArray", "setURL", "setRowId", "setNString",
                            "setNCharacterStream", "setNClob", "setSQLXML"));
            new AgentBuilder.Default().disableClassFormatChanges()
                    .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .ignore(nameStartsWith("net.bytebuddy.").or(isSynthetic()))
                    .type(drivers.and(hasSuperType(named("java.sql.Connection")).or(hasSuperType(named("java.sql.Statement")))))
                    .transform((builder, type, loader, module, domain) -> {
                        if (captureParameters && hasSuperType(named("java.sql.PreparedStatement")).matches(type)) {
                            builder = builder.visit(Advice.to(JdbcAdvice.Binding.class).on(binding))
                                    .visit(Advice.to(JdbcAdvice.ClearParameters.class).on(methods.and(named("clearParameters")).and(takesArguments(0))));
                        }
                        return builder
                            .visit(Advice.to(JdbcAdvice.Prepare.class).on(methods.and(namedOneOf("prepareStatement", "prepareCall")).and(takesArgument(0, String.class))))
                            .visit(Advice.to(JdbcAdvice.WithSql.class).on(execute.and(takesArgument(0, String.class))))
                            .visit(Advice.to(JdbcAdvice.WithoutSql.class).on(execute.and(takesArguments(0))))
                            .visit(Advice.to(JdbcAdvice.AddBatchSql.class).on(methods.and(named("addBatch")).and(takesArguments(String.class))))
                            .visit(Advice.to(JdbcAdvice.AddBatchPrepared.class).on(methods.and(named("addBatch")).and(takesArguments(0))))
                            .visit(Advice.to(JdbcAdvice.ClearBatch.class).on(methods.and(named("clearBatch")).and(takesArguments(0))))
                            .visit(Advice.to(JdbcAdvice.Close.class).on(methods.and(named("close")).and(takesArguments(0))));
                    })
                    .with(new AgentBuilder.Listener.Adapter() {
                        @Override public void onTransformation(TypeDescription type, ClassLoader loader, JavaModule module,
                                                               boolean loaded, net.bytebuddy.dynamic.DynamicType transformed) {
                            AgentStatus.integrationTransformed("jdbc");
                        }
                        @Override public void onError(String name, ClassLoader loader, JavaModule module,
                                                      boolean loaded, Throwable failure) {
                            AgentStatus.integrationFailed("jdbc");
                            AgentStatus.noteFailedClass();
                            // Error messages can include SQL; report only class/type diagnostics.
                            System.err.println("[tracelens] JDBC instrumentation failed for " + name + ": " + failure.getClass().getName());
                        }
                    }).installOn(instrumentation);
            AgentStatus.integrationInstalled("jdbc");
        } catch (Throwable failure) {
            AgentStatus.integrationFailed("jdbc");
            AgentStatus.noteFailedClass();
            System.err.println("[tracelens] JDBC instrumentation unavailable: " + failure.getClass().getName());
        }
    }
}
