package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.AgentStatus;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Narrow Vert.x 3 HTTP/1 server + callback support; not an HTTP client recorder. */
public final class VertxHttpInstrumentation {
    private VertxHttpInstrumentation() { }

    public static void install(Instrumentation instrumentation) {
        ElementMatcher.Junction<TypeDescription> server = named("io.vertx.core.http.impl.HttpServerImpl");
        ElementMatcher.Junction<TypeDescription> response = named("io.vertx.core.http.impl.HttpServerResponseImpl");
        ElementMatcher.Junction<TypeDescription> httpCallbacks = named("io.vertx.core.http.impl.HttpServerRequestImpl")
                .or(named("io.vertx.core.http.impl.HttpClientImpl"))
                .or(named("io.vertx.core.http.impl.HttpClientRequestImpl"))
                .or(named("io.vertx.core.http.impl.HttpClientResponseImpl"));
        ElementMatcher.Junction<TypeDescription> scheduling = named("io.vertx.core.impl.ContextImpl")
                .or(named("io.vertx.core.impl.VertxImpl"));
        ElementMatcher.Junction<TypeDescription> future = named("io.vertx.core.impl.FutureImpl");
        ElementMatcher.Junction<MethodDescription> eligible = isMethod().and(not(isAbstract()))
                .and(not(isNative())).and(not(isStatic())).and(not(isBridge()));
        ElementMatcher.Junction<MethodDescription> callbackMethods = isDeclaredBy(httpCallbacks)
                .and(isPublic()).and(not(nameContains("ebSocket"))).and(not(nameContains("ebsocket")))
                .or(isDeclaredBy(scheduling).and(named("runOnContext").or(named("executeBlocking"))
                        .or(named("executeBlockingInternal")).or(named("setTimer"))))
                .or(isDeclaredBy(future).and(named("onComplete")));
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .ignore(nameStartsWith("com.chargebee.tracelens.").or(nameStartsWith("net.bytebuddy.")))
                .type(server.or(response).or(httpCallbacks).or(scheduling).or(future))
                .transform((builder, type, loader, module, domain) -> builder
                        .visit(Advice.to(VertxHttpAdvice.ServerHandler.class).on(eligible.and(isDeclaredBy(server))
                                .and(named("requestHandler")).and(takesArguments(1))
                                .and(takesArgument(0, named("io.vertx.core.Handler")))))
                        .visit(Advice.to(VertxHttpAdvice.Callbacks.class).on(eligible.and(callbackMethods)
                                .and(hasParameters(whereAny(hasType(named("io.vertx.core.Handler")))))))
                        .visit(Advice.to(VertxHttpAdvice.Response.class).on(eligible.and(isDeclaredBy(response))
                                .and(named("end").or(named("sendFile")).or(named("handleClosed")).or(named("close")))))
                        .visit(Advice.to(VertxHttpAdvice.ResponseException.class).on(eligible.and(isDeclaredBy(response))
                                .and(named("handleException")).and(takesArguments(Throwable.class)))))
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override public void onError(String name, ClassLoader loader, JavaModule module,
                                                  boolean loaded, Throwable failure) {
                        AgentStatus.noteFailedClass();
                        System.err.println("[tracelens] Vert.x HTTP instrumentation failed for " + name + ": " + failure);
                    }
                }).installOn(instrumentation);
    }
}
