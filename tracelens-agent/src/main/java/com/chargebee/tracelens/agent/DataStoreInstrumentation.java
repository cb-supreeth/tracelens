package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.AgentStatus;
import com.chargebee.tracelens.bridge.DataStoreBoundaryRuntime;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

import static net.bytebuddy.matcher.ElementMatchers.*;

/** Known SDK boundaries, independent of application line-capture package scope. */
public final class DataStoreInstrumentation {
    private DataStoreInstrumentation() { }

    public static void install(Instrumentation instrumentation, boolean capture) {
        DataStoreBoundaryRuntime.configure(capture);
        AgentStatus.configureIntegration("data-store", capture);
        if (!capture) return;
        try {
            ElementMatcher.Junction<TypeDescription> types = none();
            for (String owner : DataStoreBoundaryRuntime.supportedTypes()) types = types.or(named(owner));
            new AgentBuilder.Default()
                    .disableClassFormatChanges()
                    .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .ignore(nameStartsWith("net.bytebuddy.").or(isSynthetic()))
                    .type(types)
                    .transform((builder, type, loader, module, domain) -> {
                        ElementMatcher.Junction<MethodDescription> operations = none();
                        for (String operation : DataStoreBoundaryRuntime.supportedOperations(type.getName())) {
                            operations = operations.or(named(operation));
                        }
                        operations = operations.and(isMethod()).and(isPublic()).and(not(isStatic()))
                                .and(not(isAbstract())).and(not(isNative())).and(not(isSynthetic()));
                        ElementMatcher.Junction<MethodDescription> future = returns(
                                hasSuperType(named("java.util.concurrent.Future"))
                                        .or(hasSuperType(named("java.util.concurrent.CompletionStage"))));
                        return builder
                                .visit(Advice.to(DataStoreBoundaryAdvice.Asynchronous.class).on(operations.and(future)))
                                .visit(Advice.to(DataStoreBoundaryAdvice.Synchronous.class).on(operations.and(not(future))));
                    })
                    .with(new AgentBuilder.Listener.Adapter() {
                        @Override public void onTransformation(TypeDescription type, ClassLoader loader, JavaModule module,
                                                               boolean loaded, net.bytebuddy.dynamic.DynamicType transformed) {
                            AgentStatus.integrationTransformed("data-store");
                        }
                        @Override public void onError(String name, ClassLoader loader, JavaModule module,
                                                      boolean loaded, Throwable failure) {
                            AgentStatus.integrationFailed("data-store");
                            AgentStatus.noteFailedClass();
                            System.err.println("[tracelens] data-store instrumentation failed for " + name + ": " + failure);
                        }
                    }).installOn(instrumentation);
            AgentStatus.integrationInstalled("data-store");
        } catch (Throwable failure) {
            AgentStatus.integrationFailed("data-store");
            AgentStatus.noteFailedClass();
            System.err.println("[tracelens] data-store instrumentation unavailable: " + failure);
        }
    }
}
