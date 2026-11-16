package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.TraceContext;
import com.chargebee.tracelens.bridge.TraceRuntime;
import com.chargebee.tracelens.bridge.TraceScope;
import com.chargebee.tracelens.bridge.TraceSnapshot;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObserverFailureWeavingTest {
    @AfterEach void cleanup() { TraceRuntime.unregisterSink(); TraceContext.clear(); }

    @Test void throwingObserversCannotChangeReturnValuesOrOriginalExceptions() throws Exception {
        TraceRuntime.registerSink(new TraceRuntime.Sink() {
            public void onLine(String s, String c, int line) { throw new AssertionError("line observer"); }
            public void onMethodEnter(String s, String c, String m) { throw new AssertionError("enter observer"); }
            public void onMethodExit(String s, String c, String m) { throw new AssertionError("exit observer"); }
            public void onException(String s, String c, String m, String t, String v) { throw new AssertionError("exception observer"); }
        });
        long before = TraceRuntime.observerFailureCount();
        for (Class<?> advice : new Class<?>[]{MethodTraceAdvice.Full.class,
                MethodTraceAdvice.NoExceptions.class, MethodTraceAdvice.ExceptionsOnly.class}) {
            Class<?> woven = new ByteBuddy().redefine(Fixture.class)
                    .visit(LineEmittingVisitor.forMethods())
                    .visit(Advice.to(advice).on(LineEmittingVisitor.INSTRUMENTABLE_METHOD))
                    .visit(Advice.to(DbRouteAdvice.class).on(named("route")))
                    .make().load(new java.net.URLClassLoader(new java.net.URL[0], getClass().getClassLoader()),
                            ClassLoadingStrategy.Default.INJECTION).getLoaded();
            Method value = woven.getDeclaredMethod("value", int.class);
            Method fail = woven.getDeclaredMethod("fail", RuntimeException.class);
            Method route = woven.getDeclaredMethod("route", Object.class);
            value.setAccessible(true); fail.setAccessible(true); route.setAccessible(true);
            try (TraceScope ignored = TraceContext.activate(new TraceSnapshot("trace", "lens", "user", null))) {
                assertThat(value.invoke(null, 40)).isEqualTo(42);
                RuntimeException original = new IllegalStateException("application failure");
                assertThatThrownBy(() -> fail.invoke(null, original)).isInstanceOf(InvocationTargetException.class)
                        .cause().isSameAs(original);
                RuntimeException poison = new RuntimeException() {
                    @Override public String getMessage() { throw new AssertionError("message accessor"); }
                };
                assertThatThrownBy(() -> fail.invoke(null, poison)).isInstanceOf(InvocationTargetException.class)
                        .cause().isSameAs(poison);
                Object key = new Object() {
                    @Override public String toString() { throw new AssertionError("route accessor"); }
                };
                assertThat(route.invoke(null, key)).isSameAs(key);
            }
        }
        assertThat(TraceRuntime.observerFailureCount()).isGreaterThan(before);
        assertThat(TraceContext.current()).isNull();
    }

    static class Fixture {
        static int value(int input) { return input + 2; }
        static void fail(RuntimeException failure) { throw failure; }
        static Object route(Object key) { return key; }
    }
}
