package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.AgentStatus;
import com.chargebee.tracelens.bridge.HttpIdentityRuntime;
import com.chargebee.tracelens.core.config.TraceLensConfig.HttpIdentityHook;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

import static net.bytebuddy.matcher.ElementMatchers.*;

/** Exact configured authentication seams, independent of line-instrumentation scope. */
public final class HttpIdentityInstrumentation {
    private HttpIdentityInstrumentation() { }

    public static void install(Instrumentation instrumentation, List<HttpIdentityHook> configured) {
        if (configured == null || configured.isEmpty()) return;
        List<HttpIdentityRuntime.Hook> runtimeHooks = new ArrayList<>();
        ElementMatcher.Junction<TypeDescription> types = none();
        for (HttpIdentityHook hook : configured) {
            types = types.or(named(hook.getClassName()));
            runtimeHooks.add(new HttpIdentityRuntime.Hook(hook.getId(), hook.getTargetArgument(),
                    hook.getRequestPath(), hook.getUserPath(), hook.getSitePath(),
                    hook.getTrustedUserHeader(), hook.getTrustedUserHeaderValues(), hook.getFallbackUser()));
        }
        HttpIdentityRuntime.configure(runtimeHooks);
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .ignore(nameStartsWith("com.chargebee.tracelens.").or(nameStartsWith("net.bytebuddy.")))
                .type(types)
                .transform((builder, type, loader, module, domain) -> {
                    DynamicType.Builder<?> transformed = builder;
                    for (HttpIdentityHook hook : configured) {
                        if (!type.getName().equals(hook.getClassName())) continue;
                        transformed = transformed.visit(Advice.withCustomMapping()
                                .bind(HttpIdentityAdvice.HookId.class, hook.getId())
                                .to(HttpIdentityAdvice.class)
                                .on(named(hook.getMethodName()).and(takesArguments(hook.getParameterCount()))
                                        .and(isMethod()).and(not(isAbstract())).and(not(isNative()))));
                    }
                    return transformed;
                })
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override public void onError(String name, ClassLoader loader, JavaModule module,
                                                  boolean loaded, Throwable failure) {
                        AgentStatus.noteFailedClass();
                        System.err.println("[tracelens] HTTP identity instrumentation failed for "
                                + name + ": " + failure);
                    }
                })
                .installOn(instrumentation);
    }
}
