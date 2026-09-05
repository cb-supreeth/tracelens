package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.TraceContext;
import com.chargebee.tracelens.bridge.TraceRuntime;
import com.chargebee.tracelens.bridge.TraceSnapshot;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces the Phase 3 finding: lambda bodies (synthetic lambda$ methods) must be
 * woven — cb-ems controllers return Callable lambdas, so missing them blinds the
 * tracer exactly where business logic lives.
 */
class LineEmittingVisitorTest {

    private final AtomicLong lines = new AtomicLong();

    @AfterEach
    void cleanup() {
        TraceRuntime.unregisterSink();
        TraceContext.clear();
    }

    @Test
    void lambdaBodiesAreInstrumented() throws Exception {
        TraceRuntime.registerSink(new CountingSink(lines));

        // Redefine the fixture with the line visitor and load it into a fresh
        // classloader (parent = test loader, so the bridge resolves) — we test the
        // woven bytecode, not the already-loaded original.
        java.net.URLClassLoader fresh = new java.net.URLClassLoader(new java.net.URL[0],
                LineEmittingVisitorTest.class.getClassLoader());
        Class<?> woven = new ByteBuddy()
                .redefine(LambdaFixture.class)
                .visit(LineEmittingVisitor.forMethods())
                .make()
                .load(fresh, ClassLoadingStrategy.Default.INJECTION)
                .getLoaded();

        try (com.chargebee.tracelens.bridge.TraceScope ignored =
                     TraceContext.activate(new TraceSnapshot("trace-test", "test", "test", null))) {
            Method run = woven.getDeclaredMethod("run");
            run.setAccessible(true); // woven copy lives in another loader (runtime package differs)
            run.invoke(null); // static method: streams a list through a lambda
        }

        // The fixture's lambda body executes 3 times (3 list elements) plus the
        // run() method's own lines. Anything less means the lambda was skipped.
        long lambdaLineHits = lines.get();
        assertThat(lambdaLineHits)
                .as("lambda body line events (3 invocations) + run() lines")
                .isGreaterThanOrEqualTo(5L);
    }

    @Test
    void lambdaBodiesSurviveCompositionWithMethodAdvice() throws Exception {
        TraceRuntime.registerSink(new CountingSink(lines));

        // The agent's exact composition: line visitor + method advice on one builder.
        java.net.URLClassLoader fresh = new java.net.URLClassLoader(new java.net.URL[0],
                LineEmittingVisitorTest.class.getClassLoader());
        Class<?> woven = new ByteBuddy()
                .redefine(LambdaFixture.class)
                .visit(LineEmittingVisitor.forMethods())
                .visit(net.bytebuddy.asm.Advice.to(MethodTraceAdvice.Full.class)
                        .on(LineEmittingVisitor.INSTRUMENTABLE_METHOD))
                .make()
                .load(fresh, ClassLoadingStrategy.Default.INJECTION)
                .getLoaded();

        try (com.chargebee.tracelens.bridge.TraceScope ignored =
                     TraceContext.activate(new TraceSnapshot("trace-test", "test", "test", null))) {
            Method run = woven.getDeclaredMethod("run");
            run.setAccessible(true);
            run.invoke(null);
        }

        assertThat(lines.get())
                .as("line events incl. lambda body when method advice is also applied")
                .isGreaterThanOrEqualTo(5L);
    }

    private final class CountingSink implements TraceRuntime.Sink {
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
        public void onException(String traceId, String fqcn, String method, String type, String message) {
        }
    }

    /** Fixture: a lambda body with its own line. Kept as a separate top-level class
     *  so the test can reference it by name without loading it early. */
    static final class LambdaFixture {
        static long run() {
            List<String> names = List.of("alpha", "beta", "gamma");
            return names.stream()
                    .filter(name -> name.length() > 4) // lambda body line
                    .count();
        }
    }
}
