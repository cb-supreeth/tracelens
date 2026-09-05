package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.TraceContext;
import com.chargebee.tracelens.bridge.TraceRuntime;
import com.chargebee.tracelens.bridge.TraceScope;
import com.chargebee.tracelens.bridge.TraceSnapshot;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.DynamicType;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replicates the agent's executor instrumentation: TPE.execute task wrapping must
 * carry the trace context onto pool threads. Fails when the wrap advice doesn't fire
 * (e.g. retransformation silently no-ops).
 */
class ExecutorWeavingTest {

    /** Jar up the compiled bridge classes and append to the bootstrap search path. */
    private static void injectBridgeIntoBootstrap(Instrumentation inst) throws Exception {
        java.io.File classesDir = java.nio.file.Path.of("..", "tracelens-bridge", "build",
                "classes", "java", "main").toAbsolutePath().normalize().toFile();
        if (!classesDir.isDirectory()) {
            throw new IllegalStateException("bridge classes not found at " + classesDir);
        }
        java.io.File jar = java.io.File.createTempFile("tracelens-bridge-test", ".jar");
        jar.deleteOnExit();
        try (java.util.jar.JarOutputStream out = new java.util.jar.JarOutputStream(
                new java.io.FileOutputStream(jar))) {
            java.nio.file.Files.walk(classesDir.toPath())
                    .filter(java.nio.file.Files::isRegularFile)
                    .forEach(p -> {
                        String entry = classesDir.toPath().relativize(p).toString()
                                .replace(java.io.File.separatorChar, '/');
                        try {
                            out.putNextEntry(new java.util.jar.JarEntry(entry));
                            out.write(java.nio.file.Files.readAllBytes(p));
                            out.closeEntry();
                        } catch (java.io.IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        inst.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(jar));
    }

    @Test
    void threadPoolExecutorTasksCarryTraceContext() throws Exception {
        // This test class runs in its own JVM (forkEvery=1). The FIRST touch of the
        // bridge must be this injection — after this, every TraceContext/TraceRuntime
        // reference (this test class, the woven JDK class) resolves to the bootstrap
        // copy, exactly like production where the agent injects the bridge at premain.
        Instrumentation inst = ByteBuddyAgent.install();
        injectBridgeIntoBootstrap(inst);

        boolean tpeLoadedBefore = java.util.Arrays.stream(inst.getAllLoadedClasses())
                .anyMatch(c -> c.getName().equals("java.util.concurrent.ThreadPoolExecutor"));
        System.out.println("[exec-test] TPE loaded before install: " + tpeLoadedBefore);

        java.io.PrintWriter diag = new java.io.PrintWriter("/tmp/exec-test.log");
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new AgentBuilder.RedefinitionStrategy.Listener() {
                    @Override
                    public void onBatch(int index, java.util.List<Class<?>> batch,
                                        java.util.List<Class<?>> types) {
                        diag.println("redef batch: " + batch);
                        diag.flush();
                    }

                    @Override
                    public Iterable<? extends java.util.List<Class<?>>> onError(int index,
                            java.util.List<Class<?>> batch, Throwable throwable,
                            java.util.List<Class<?>> types) {
                        diag.println("redef ERROR: " + batch + " -> " + throwable);
                        diag.flush();
                        return java.util.Collections.emptyList();
                    }

                    @Override
                    public void onComplete(int amount, java.util.List<Class<?>> types,
                                           java.util.Map<java.util.List<Class<?>>, Throwable> failures) {
                        diag.println("redef complete: amount=" + amount + " types=" + types
                                + " failures=" + failures);
                        diag.flush();
                    }
                })
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .ignore(nameStartsWith("net.bytebuddy.").or(isSynthetic()))
                .type(named("java.util.concurrent.ThreadPoolExecutor"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(ExecutorTraceAdvice.WrapRunnable.class)
                                .on(named("execute").and(takesArgument(0, Runnable.class)))))
                .with(new AgentBuilder.Listener() {
                    @Override
                    public void onDiscovery(String typeName, ClassLoader classLoader,
                                            net.bytebuddy.utility.JavaModule module, boolean loaded) {
                        if (typeName.contains("ThreadPoolExecutor")) {
                            System.out.println("[exec-test] discovery: " + typeName + " loaded=" + loaded);
                        }
                    }

                    @Override
                    public void onTransformation(net.bytebuddy.description.type.TypeDescription typeDescription,
                                                 ClassLoader classLoader, net.bytebuddy.utility.JavaModule module,
                                                 boolean loaded, DynamicType dynamicType) {
                        try {
                            java.nio.file.Files.write(java.nio.file.Path.of(
                                    "/tmp/tracelens-tpe-transformed.class"), dynamicType.getBytes());
                            diag.println("transformed: " + typeDescription.getName() + " (bytes dumped)");
                            diag.flush();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }

                    @Override
                    public void onIgnored(net.bytebuddy.description.type.TypeDescription typeDescription,
                                          ClassLoader classLoader, net.bytebuddy.utility.JavaModule module,
                                          boolean loaded) {
                        System.out.println("[exec-test] IGNORED: " + typeDescription.getName());
                    }

                    @Override
                    public void onError(String typeName, ClassLoader classLoader,
                                        net.bytebuddy.utility.JavaModule module, boolean loaded, Throwable throwable) {
                        System.out.println("[exec-test] ERROR: " + typeName + " -> " + throwable);
                    }

                    @Override
                    public void onComplete(String typeName, ClassLoader classLoader,
                                           net.bytebuddy.utility.JavaModule module, boolean loaded) {
                    }
                })
                .installOn(inst);

        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            AtomicReference<String> traceIdOnPoolThread = new AtomicReference<>();
            TraceSnapshot snapshot = new TraceSnapshot("trace-exec-test", "lens", "user", null);
            try (TraceScope ignored = TraceContext.activate(snapshot)) {
                pool.submit(() -> traceIdOnPoolThread.set(TraceContext.currentTraceId())).get();
            }

            assertThat(traceIdOnPoolThread.get())
                    .as("trace id on pool thread | tpeLoadedBefore=" + tpeLoadedBefore
                            + " wrappedTasks=" + TraceRuntime.wrappedTaskCount())
                    .isEqualTo("trace-exec-test");
            assertThat(TraceRuntime.wrappedTaskCount()).isGreaterThan(0);
        } finally {
            pool.shutdown();
        }
    }
}
