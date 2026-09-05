package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.TraceContext;
import com.chargebee.tracelens.bridge.TraceRuntime;
import com.chargebee.tracelens.bridge.TraceScope;
import com.chargebee.tracelens.bridge.TraceSnapshot;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Bisects why AgentBuilder-path weaving skips lambda bodies while redefine(Class) works. */
class BisectTest {

    private final AtomicLong lines = new AtomicLong();

    @AfterEach
    void cleanup() {
        TraceRuntime.unregisterSink();
        TraceContext.clear();
    }

    @Test
    void variantB_typePoolRedefine() throws Exception {
        TraceRuntime.registerSink(new CountingSink(lines));

        // The agent's exact builder path: TypePool description + ClassFileLocator.
        ClassLoader loader = BisectTest.class.getClassLoader();
        TypePool typePool = TypePool.Default.of(loader);
        TypeDescription typeDescription = typePool.describe("com.chargebee.ems.fixture.BisectFixture").resolve();
        ClassFileLocator locator = ClassFileLocator.ForClassLoader.of(loader);

        DynamicType.Builder<?> builder = new ByteBuddy().redefine(typeDescription, locator);
        builder = builder.visit(LineEmittingVisitor.forMethods());

        java.net.URLClassLoader fresh = new java.net.URLClassLoader(new java.net.URL[0], loader);
        Class<?> woven = builder.make().load(fresh, ClassLoadingStrategy.Default.INJECTION).getLoaded();

        invokeRun(woven);

        assertThat(lines.get()).as("line events with TypePool-based redefine").isGreaterThanOrEqualTo(5L);
    }

    @Test
    void variantC_typePoolRedefinePlusMethodAdvice() throws Exception {
        TraceRuntime.registerSink(new CountingSink(lines));

        ClassLoader loader = BisectTest.class.getClassLoader();
        TypePool typePool = TypePool.Default.of(loader);
        TypeDescription typeDescription = typePool.describe("com.chargebee.ems.fixture.BisectFixture").resolve();
        ClassFileLocator locator = ClassFileLocator.ForClassLoader.of(loader);

        DynamicType.Builder<?> builder = new ByteBuddy().redefine(typeDescription, locator);
        builder = builder.visit(LineEmittingVisitor.forMethods());
        builder = builder.visit(net.bytebuddy.asm.Advice.to(MethodTraceAdvice.Full.class)
                .on(LineEmittingVisitor.INSTRUMENTABLE_METHOD));

        java.net.URLClassLoader fresh = new java.net.URLClassLoader(new java.net.URL[0], loader);
        Class<?> woven = builder.make().load(fresh, ClassLoadingStrategy.Default.INJECTION).getLoaded();

        invokeRun(woven);

        assertThat(lines.get()).as("line events with TypePool redefine + method advice")
                .isGreaterThanOrEqualTo(5L);
    }

    private void invokeRun(Class<?> woven) throws Exception {
        try (TraceScope ignored = TraceContext.activate(new TraceSnapshot("t", "l", "u", null))) {
            Method run = woven.getDeclaredMethod("run");
            run.setAccessible(true);
            run.invoke(null);
        }
    }

    private static final class CountingSink implements TraceRuntime.Sink {
        private final AtomicLong counter;

        CountingSink(AtomicLong counter) {
            this.counter = counter;
        }

        @Override
        public void onLine(String traceId, String fqcn, int line) {
            counter.incrementAndGet();
        }

        @Override
        public void onMethodEnter(String traceId, String fqcn, String method) {
        }

        @Override
        public void onMethodExit(String traceId, String fqcn, String method) {
        }

        @Override
        public void onException(String traceId, String fqcn, String method, String type, String msg) {
        }
    }
}
