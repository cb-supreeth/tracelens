package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.AgentStatus;
import com.chargebee.tracelens.bridge.ChargebeeJobRuntime;
import java.lang.instrument.Instrumentation;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.utility.JavaModule;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Exact Chargebee dispatcher seam, independent of application line-capture packages. */
public final class ChargebeeJobInstrumentation {
    private ChargebeeJobInstrumentation() { }

    public static void install(Instrumentation instrumentation, List<String> jobTypes) {
        ChargebeeJobRuntime.configure(jobTypes);
        if (jobTypes == null || jobTypes.isEmpty()) return;
        try {
            new AgentBuilder.Default()
                    .disableClassFormatChanges()
                    .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .type(named("com.chargebee.app.scheduler.internal.AppJobExecutor"))
                    .transform((builder, type, loader, module, domain) -> builder
                            .visit(Advice.to(ChargebeeJobAdvice.Dispatch.class).on(named("exec")
                                    .and(takesArguments(2)).and(returns(void.class))
                                    .and(takesArgument(0, named("com.chargebee.app.models.SchJob")))
                                    .and(takesArgument(1, Long.class)).and(not(isAbstract())).and(not(isNative()))))
                            .visit(Advice.to(ChargebeeJobAdvice.Audit.class).on(named("audit")
                                    .and(takesArguments(5)).and(returns(void.class))
                                    .and(takesArgument(0, named("com.chargebee.app.models.SchJob")))
                                    .and(takesArgument(1, named("com.chargebee.app.models.SchJobRegion")))
                                    .and(takesArgument(2, named("com.chargebee.app.scheduler.JobContext")))
                                    .and(takesArgument(3, named("com.chargebee.app.models.base.SchJobAuditBase$Status")))
                                    .and(takesArgument(4, Throwable.class)).and(not(isAbstract())).and(not(isNative())))))
                    .with(new AgentBuilder.Listener.Adapter() {
                        @Override public void onError(String name, ClassLoader loader, JavaModule module,
                                                      boolean loaded, Throwable failure) {
                            AgentStatus.noteFailedClass();
                            System.err.println("[tracelens] Chargebee scheduler instrumentation failed for " + name + ": " + failure);
                        }
                    }).installOn(instrumentation);
        } catch (Throwable failure) {
            AgentStatus.noteFailedClass();
            System.err.println("[tracelens] Chargebee scheduler instrumentation unavailable: " + failure);
        }
    }
}
