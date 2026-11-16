package com.chargebee.tracelens.agent;

import java.lang.instrument.Instrumentation;
import com.chargebee.tracelens.bridge.AgentStatus;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

import static net.bytebuddy.matcher.ElementMatchers.*;

/** Servlet 2.5+ javax / Servlet 5+ jakarta boundaries, independent of app packages. */
public final class HttpInstrumentation {
    private HttpInstrumentation() { }

    static ElementMatcher.Junction<TypeDescription> ignoredTypes() {
        return nameStartsWith("com.chargebee.tracelens.")
                .or(nameStartsWith("net.bytebuddy."))
                // Log4j core scans optional plugins whose absent Disruptor, mail,
                // Jackson XML or OSGi parents are not servlet capture failures.
                // Exclude before hasSuperType resolves that hierarchy. Deliberately
                // retain org.apache.logging.log4j.web (its real servlet filter).
                .or(nameStartsWith("org.apache.logging.log4j.core."));
    }

    public static void install(Instrumentation instrumentation) {
        ElementMatcher.Junction<TypeDescription> filters = hasSuperType(named("javax.servlet.Filter"))
                .or(hasSuperType(named("jakarta.servlet.Filter")));
        ElementMatcher.Junction<TypeDescription> servlets = hasSuperType(named("javax.servlet.http.HttpServlet"))
                .or(hasSuperType(named("jakarta.servlet.http.HttpServlet")));
        ElementMatcher.Junction<TypeDescription> asyncContexts = hasSuperType(named("javax.servlet.AsyncContext"))
                .or(hasSuperType(named("jakarta.servlet.AsyncContext")));
        ElementMatcher.Junction<MethodDescription> filterMethod = named("doFilter").and(takesArguments(3))
                .and(takesArgument(0, named("javax.servlet.ServletRequest"))
                        .and(takesArgument(1, named("javax.servlet.ServletResponse")))
                        .and(takesArgument(2, named("javax.servlet.FilterChain")))
                        .or(takesArgument(0, named("jakarta.servlet.ServletRequest"))
                                .and(takesArgument(1, named("jakarta.servlet.ServletResponse")))
                                .and(takesArgument(2, named("jakarta.servlet.FilterChain")))));
        ElementMatcher.Junction<MethodDescription> serviceMethod = named("service").and(takesArguments(2))
                .and(takesArgument(0, named("javax.servlet.ServletRequest"))
                        .and(takesArgument(1, named("javax.servlet.ServletResponse")))
                        .or(takesArgument(0, named("javax.servlet.http.HttpServletRequest"))
                                .and(takesArgument(1, named("javax.servlet.http.HttpServletResponse"))))
                        .or(takesArgument(0, named("jakarta.servlet.ServletRequest"))
                                .and(takesArgument(1, named("jakarta.servlet.ServletResponse"))))
                        .or(takesArgument(0, named("jakarta.servlet.http.HttpServletRequest"))
                                .and(takesArgument(1, named("jakarta.servlet.http.HttpServletResponse")))));
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .ignore(ignoredTypes())
                .type(filters.or(servlets).or(asyncContexts).and(not(isInterface())))
                .transform((builder, type, loader, module, domain) -> builder.visit(
                        Advice.to(HttpRequestAdvice.class).on(filterMethod.or(serviceMethod)
                                .and(not(isAbstract())).and(not(isNative())).and(not(isStatic()))))
                        .visit(Advice.to(HttpRequestAdvice.AsyncStart.class).on(named("start")
                                .and(takesArguments(Runnable.class)).and(isDeclaredBy(asyncContexts))
                                .and(not(isAbstract())).and(not(isNative())).and(not(isStatic())))))
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override public void onError(String name, ClassLoader loader, JavaModule module,
                                                  boolean loaded, Throwable failure) {
                        AgentStatus.noteFailedClass();
                        System.err.println("[tracelens] HTTP instrumentation failed for " + name + ": " + failure);
                    }
                })
                .installOn(instrumentation);
    }
}
