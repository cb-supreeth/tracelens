package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.AgentStatus;
import com.chargebee.tracelens.bridge.ClickHouseQueryRuntime;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.utility.JavaModule;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Exact canonical method used by ClickHouse native client-v2 0.9.3 public query/execute wrappers. */
public final class ClickHouseInstrumentation {
    private ClickHouseInstrumentation() { }

    public static void install(Instrumentation instrumentation, boolean capture, int maxSqlLength,
                               int maxQueriesPerRequest, boolean parameters) {
        ClickHouseQueryRuntime.configure(capture, maxSqlLength, maxQueriesPerRequest, parameters);
        AgentStatus.configureIntegration("clickhouse", capture);
        if (!capture) return;
        try {
            new AgentBuilder.Default().disableClassFormatChanges()
                    .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .ignore(nameStartsWith("net.bytebuddy.").or(isSynthetic()))
                    .type(named("com.clickhouse.client.api.Client"))
                    .transform((builder, type, loader, module, domain) -> builder.visit(
                            Advice.to(ClickHouseQueryAdvice.class).on(named("query").and(isPublic()).and(not(isStatic()))
                                    .and(takesArguments(3)).and(takesArgument(0, String.class))
                                    .and(takesArgument(1, java.util.Map.class))
                                    .and(takesArgument(2, named("com.clickhouse.client.api.query.QuerySettings")))
                                    .and(returns(named("java.util.concurrent.CompletableFuture"))))))
                    .with(new AgentBuilder.Listener.Adapter() {
                        @Override public void onTransformation(net.bytebuddy.description.type.TypeDescription type, ClassLoader loader,
                                                               JavaModule module, boolean loaded, net.bytebuddy.dynamic.DynamicType transformed) {
                            AgentStatus.integrationTransformed("clickhouse");
                        }
                        @Override public void onError(String name, ClassLoader loader, JavaModule module,
                                                      boolean loaded, Throwable failure) {
                            AgentStatus.integrationFailed("clickhouse");
                            AgentStatus.noteFailedClass();
                            System.err.println("[tracelens] ClickHouse query instrumentation failed for " + name + ": " + failure);
                        }
                    }).installOn(instrumentation);
            AgentStatus.integrationInstalled("clickhouse");
        } catch (Throwable failure) {
            AgentStatus.integrationFailed("clickhouse");
            AgentStatus.noteFailedClass();
            System.err.println("[tracelens] ClickHouse query instrumentation unavailable: " + failure);
        }
    }
}
