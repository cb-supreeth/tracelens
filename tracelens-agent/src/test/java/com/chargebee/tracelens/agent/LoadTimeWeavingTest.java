package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.TraceContext;
import com.chargebee.tracelens.bridge.TraceRuntime;
import com.chargebee.tracelens.bridge.TraceScope;
import com.chargebee.tracelens.bridge.TraceSnapshot;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.DynamicType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Faithful production replication: AgentBuilder + load-time transformation of a class
 * defined ONLY through a fresh loader after the agent is installed (no JUnit/test
 * classpath pre-loading, no retransformation). This is the premain scenario.
 */
class LoadTimeWeavingTest {

    private static final String FIXTURE = "com.chargebee.ems.fixture.LoadTimeFixture";

    private final AtomicLong lines = new AtomicLong();
    private final ConcurrentLinkedQueue<String> seen = new ConcurrentLinkedQueue<>();

    @AfterEach
    void cleanup() {
        TraceRuntime.unregisterSink();
        TraceContext.clear();
    }

    @Test
    void lambdaBodiesWovenAtLoadTime() throws Exception {
        TraceRuntime.registerSink(new TraceRuntime.Sink() {
            @Override
            public void onLine(String traceId, String fqcn, int line) {
                lines.incrementAndGet();
                seen.add(fqcn + ":" + line);
            }

            @Override
            public void onMethodEnter(String traceId, String fqcn, String method) {
                seen.add(fqcn + "." + method + " [enter]");
            }

            @Override
            public void onMethodExit(String traceId, String fqcn, String method) {
            }

            @Override
            public void onException(String traceId, String fqcn, String method, String type, String msg) {
            }
        });

        Instrumentation inst = ByteBuddyAgent.install();

        AgentBuilder.Transformer transformer =
                (builder, typeDescription, classLoader, module, protectionDomain) -> {
                    DynamicType.Builder<?> out = builder.visit(LineEmittingVisitor.forMethods());
                    out = out.visit(Advice.to(MethodTraceAdvice.Full.class)
                            .on(LineEmittingVisitor.INSTRUMENTABLE_METHOD));
                    return out;
                };

        new AgentBuilder.Default(new net.bytebuddy.ByteBuddy().ignore(net.bytebuddy.matcher.ElementMatchers.isDefaultFinalizer()))
                .ignore(nameStartsWith("com.chargebee.tracelens.")
                        .or(nameStartsWith("net.bytebuddy."))
                        .or(isSynthetic()))
                .type(nameStartsWith("com.chargebee.ems.fixture"))
                .transform(transformer)
                .installOn(inst);

        // Load the fixture's bytes from the test-classes dir and define them in a
        // fresh loader — the agent's ClassFileTransformer fires during defineClass,
        // exactly like a premain agent weaving application classes at load time.
        byte[] fixtureBytes = Files.readAllBytes(Path.of(
                "build/classes/java/test/com/chargebee/ems/fixture/LoadTimeFixture.class"));
        ClassLoader freshLoader = new ClassLoader(LoadTimeWeavingTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(FIXTURE)) {
                    Class<?> defined = defineClass(name, fixtureBytes, 0, fixtureBytes.length);
                    if (resolve) {
                        resolveClass(defined);
                    }
                    return defined;
                }
                return super.loadClass(name, resolve);
            }
        };

        Class<?> fixture = Class.forName(FIXTURE, true, freshLoader);
        try (TraceScope ignored = TraceContext.activate(new TraceSnapshot("t", "l", "u", null))) {
            fixture.getDeclaredMethod("run").invoke(null);
        }

        assertThat(lines.get())
                .as("line events via load-time weaving: " + seen)
                .isGreaterThanOrEqualTo(5L);
    }
}
