package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.AgentStatus;
import com.chargebee.tracelens.bridge.ApacheHttpClientTraceRuntime;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.utility.JavaModule;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Apache HttpClient 4.3–4.5 synchronous clients; no application dependency is bundled. */
public final class ApacheHttpClientInstrumentation {
    private ApacheHttpClientInstrumentation() { }

    public static void install(Instrumentation instrumentation) {
        try {
            new AgentBuilder.Default()
                    .disableClassFormatChanges()
                    .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .ignore(nameStartsWith("net.bytebuddy.").or(nameStartsWith("com.chargebee.tracelens.")))
                    .type(named("org.apache.http.impl.client.InternalHttpClient")
                            .or(named("org.apache.http.impl.client.MinimalHttpClient")))
                    .transform((builder, type, loader, module, domain) -> builder.visit(
                            Advice.to(ApacheHttpClientAdvice.Boundary.class).on(named("doExecute")
                                    .and(takesArguments(3)).and(not(isAbstract())))))
                    .type(named("org.apache.http.impl.execchain.MainClientExec")
                            .or(named("org.apache.http.impl.execchain.MinimalClientExec")))
                    .transform((builder, type, loader, module, domain) -> builder.visit(
                            Advice.to(ApacheHttpClientAdvice.Dispatch.class).on(named("execute")
                                    .and(takesArguments(4)).and(not(isAbstract())))))
                    .with(new AgentBuilder.Listener.Adapter() {
                        @Override public void onError(String name, ClassLoader loader, JavaModule module,
                                                      boolean loaded, Throwable failure) {
                            AgentStatus.noteFailedClass();
                            System.err.println("[tracelens] Apache HTTP instrumentation failed for " + name
                                    + ": " + failure.getClass().getName());
                        }
                    })
                    .assureReadEdgeFromAndTo(instrumentation, ApacheHttpClientTraceRuntime.class)
                    .installOn(instrumentation);
        } catch (Throwable failure) {
            System.err.println("[tracelens] Apache HTTP instrumentation unavailable: "
                    + failure.getClass().getName());
        }
    }
}
