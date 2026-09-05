package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.AgentStatus;
import com.chargebee.tracelens.bridge.TraceRuntime;
import com.chargebee.tracelens.core.config.TraceLensConfig;
import com.chargebee.tracelens.core.config.TraceLensConfigLoader;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarFile;

import static net.bytebuddy.matcher.ElementMatchers.isDefaultFinalizer;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.nameContains;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.none;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

/**
 * tracelens agent: line-level + method-level capture for configured packages.
 *
 * Config resolution (mirrors FlowLens — no implicit discovery, premain aborts without it):
 *   1. -Dtracelens.configPath=/abs/path/tracelens.json   (full config)
 *   2. -Dtracelens.packages=com.chargebee.ems            (minimal fallback)
 *
 * The bridge (TraceRuntime & co) must be visible to instrumented classes in any
 * classloader, so the embedded bridge jar is appended to the bootstrap search path.
 */
public final class TraceLensAgent {

    private static final String PACKAGES_PROP = "tracelens.packages";

    private static final AtomicLong INSTRUMENTED_CLASSES = new AtomicLong();
    private static final AtomicLong FAILED_CLASSES = new AtomicLong();

    private TraceLensAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        long startNanos = System.nanoTime();
        log("premain starting");

        TraceLensConfig config = resolveConfig();
        injectIntoBootstrap(inst);

        ElementMatcher.Junction<TypeDescription> typeMatcher = scopeMatcher(config);
        AsmVisitorWrapper methodAdvice = selectMethodAdvice(config);

        AgentBuilder.Transformer transformer = (builder, typeDescription, classLoader, module, protectionDomain) -> {
            String debugMethods = System.getProperty("tracelens.debugMethods");
            if (debugMethods != null) {
                typeDescription.getDeclaredMethods().forEach(md -> {
                    if (md.getName().contains(debugMethods)) {
                        log("debug-method " + typeDescription.getName() + "." + md.getName()
                                + " synthetic=" + md.isSynthetic() + " isMethod=" + md.isMethod()
                                + " matches=" + LineEmittingVisitor.INSTRUMENTABLE_METHOD.matches(md));
                    }
                });
            }
            DynamicType.Builder<?> out = builder.visit(LineEmittingVisitor.forMethods());
            if (methodAdvice != null) {
                out = out.visit(methodAdvice);
            }
            return out;
        };

        // CRITICAL: ByteBuddy's default ignores ALL synthetic methods for
        // instrumentation — which silently drops lambda bodies (lambda$... are
        // synthetic), exactly where modern Java business logic lives. Override to
        // ignore only the default finalizer; method-level scoping stays with
        // INSTRUMENTABLE_METHOD (lambdas in, other synthetics out).
        new AgentBuilder.Default(new ByteBuddy().ignore(isDefaultFinalizer()))
                // REDEFINE (not the default REBASE): rebase renames original methods and
                // adds delegating stubs — adding methods is illegal under retransformation
                // and needlessly complicates the woven output for pure visitors/advice.
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .ignore(nameStartsWith("com.chargebee.tracelens.")
                        .or(nameStartsWith("net.bytebuddy."))
                        .or(nameStartsWith("com.chargebee.tracelens.shaded."))
                        .or(isSynthetic()))
                .type(typeMatcher)
                .transform(transformer)
                .with(new AgentBuilder.Listener() {
                    @Override
                    public void onDiscovery(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
                    }

                    @Override
                    public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader,
                                                 JavaModule module, boolean loaded, DynamicType dynamicType) {
                        INSTRUMENTED_CLASSES.incrementAndGet();
                        AgentStatus.noteInstrumentedClass(typeDescription.getName());
                        dumpClass(typeDescription, dynamicType);
                    }

                    @Override
                    public void onIgnored(TypeDescription typeDescription, ClassLoader classLoader,
                                          JavaModule module, boolean loaded) {
                    }

                    @Override
                    public void onError(String typeName, ClassLoader classLoader, JavaModule module,
                                        boolean loaded, Throwable throwable) {
                        FAILED_CLASSES.incrementAndGet();
                        AgentStatus.noteFailedClass();
                        log("FAILED to instrument " + typeName + ": " + throwable);
                    }

                    @Override
                    public void onComplete(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
                    }
                })
                .installOn(inst);

        if (config.isInstrumentExecutors()) {
            installExecutorInstrumentation(inst);
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        AgentStatus.reportPremain(elapsedMs, config.getPackages().toString());
        log("premain done in " + elapsedMs + " ms; packages=" + config.getPackages()
                + " excluded=" + config.getExcludedPackages()
                + " trackMethods=" + config.isTrackMethods()
                + " trackExceptions=" + config.isTrackExceptions()
                + " instrumentExecutors=" + config.isInstrumentExecutors());

        Runtime.getRuntime().addShutdownHook(new Thread(TraceLensAgent::printSummary, "tracelens-summary"));
    }

    public static void agentmain(String args, Instrumentation inst) {
        premain(args, inst);
    }

    private static TraceLensConfig resolveConfig() {
        String configPath = System.getProperty(TraceLensConfigLoader.CONFIG_PATH_PROPERTY);
        if (configPath != null && !configPath.isBlank()) {
            TraceLensConfig config = new TraceLensConfigLoader().load();
            log("loaded config from " + configPath + " (applicationId=" + config.getApplicationId() + ")");
            return config;
        }
        String packages = System.getProperty(PACKAGES_PROP);
        if (packages != null && !packages.isBlank()) {
            List<String> packageList = new ArrayList<>();
            for (String part : packages.split(",")) {
                if (!part.trim().isEmpty()) {
                    packageList.add(part.trim());
                }
            }
            log("no configPath; using -D" + PACKAGES_PROP + "=" + packages);
            return new TraceLensConfig().setApplicationId("unknown").setPackages(packageList);
        }
        throw new IllegalStateException("[tracelens] no configuration: set -D"
                + TraceLensConfigLoader.CONFIG_PATH_PROPERTY + " (preferred) or -D" + PACKAGES_PROP);
    }

    private static ElementMatcher.Junction<TypeDescription> scopeMatcher(TraceLensConfig config) {
        ElementMatcher.Junction<TypeDescription> matcher = none();
        for (String pkg : config.getPackages()) {
            matcher = matcher.or(nameStartsWith(pkg.endsWith(".") ? pkg : pkg + "."));
        }
        for (String excluded : config.getExcludedPackages()) {
            matcher = matcher.and(not(nameStartsWith(excluded.endsWith(".") ? excluded : excluded + ".")));
        }
        for (String blacklisted : config.getBlacklistedNodes()) {
            matcher = matcher.and(not(nameContains(blacklisted)));
        }
        return matcher;
    }

    /**
     * Task wrapping at executor hand-off points (OTel's executors module equivalent).
     * JDK classes are already loaded when premain runs, so this needs retransformation.
     * Failure here must not kill line capture — log and move on.
     */
    private static void installExecutorInstrumentation(Instrumentation inst) {
        try {
            AgentBuilder.RedefinitionStrategy.Listener redefinitionErrors =
                    new AgentBuilder.RedefinitionStrategy.Listener() {
                        @Override
                        public void onBatch(int index, java.util.List<Class<?>> batch,
                                            java.util.List<Class<?>> types) {
                        }

                        @Override
                        public Iterable<? extends java.util.List<Class<?>>> onError(int index,
                                java.util.List<Class<?>> batch, Throwable throwable,
                                java.util.List<Class<?>> types) {
                            log("executor retransformation error for " + batch + ": " + throwable);
                            return java.util.Collections.emptyList();
                        }

                        @Override
                        public void onComplete(int amount, java.util.List<Class<?>> types,
                                               java.util.Map<java.util.List<Class<?>>, Throwable> failures) {
                            if (!failures.isEmpty()) {
                                log("executor retransformation failures: " + failures);
                            }
                        }
                    };

            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(redefinitionErrors)
                    // REDEFINE: the default REBASE adds renamed-method stubs, which
                    // retransformation forbids ("attempted to add a method").
                    .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                    // CRITICAL: AgentBuilder's default ignore excludes ALL
                    // bootstrap/extension-loaded types — which is exactly where JDK
                    // executors live. Override to keep only the self-protection rules.
                    .ignore(nameStartsWith("net.bytebuddy.").or(isSynthetic()))
                    .type(named("java.util.concurrent.ThreadPoolExecutor"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(ExecutorTraceAdvice.WrapRunnable.class)
                                    .on(named("execute").and(takesArgument(0, Runnable.class)))))
                    .installOn(inst);

            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(redefinitionErrors)
                    .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                    .ignore(nameStartsWith("net.bytebuddy.").or(isSynthetic()))
                    .type(named("java.util.concurrent.ScheduledThreadPoolExecutor"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder
                                    .visit(Advice.to(ExecutorTraceAdvice.WrapRunnable.class)
                                            .on(named("schedule").and(takesArgument(0, Runnable.class))
                                                    .or(named("scheduleAtFixedRate"))
                                                    .or(named("scheduleWithFixedDelay"))))
                                    .visit(Advice.to(ExecutorTraceAdvice.WrapCallable.class)
                                            .on(named("schedule").and(takesArgument(0, Callable.class)))))
                    .installOn(inst);
            log("executor instrumentation installed (ThreadPoolExecutor, ScheduledThreadPoolExecutor)");
        } catch (Throwable t) {
            log("executor instrumentation failed (continuing without it): " + t);
        }
    }

    private static AsmVisitorWrapper selectMethodAdvice(TraceLensConfig config) {
        boolean methods = config.isTrackMethods();
        boolean exceptions = config.isTrackExceptions();
        if (methods && exceptions) {
            return Advice.to(MethodTraceAdvice.Full.class).on(LineEmittingVisitor.INSTRUMENTABLE_METHOD);
        }
        if (methods) {
            return Advice.to(MethodTraceAdvice.NoExceptions.class).on(LineEmittingVisitor.INSTRUMENTABLE_METHOD);
        }
        if (exceptions) {
            return Advice.to(MethodTraceAdvice.ExceptionsOnly.class).on(LineEmittingVisitor.INSTRUMENTABLE_METHOD);
        }
        return null;
    }

    /**
     * The bridge classes must be visible to instrumented classes in ANY classloader,
     * so the embedded bridge jar is extracted to a temp file and appended to the
     * bootstrap search path. Injecting the whole agent jar instead would duplicate the
     * agent + shaded ByteBuddy across bootstrap and system loaders -> LinkageError
     * (learned the hard way in the spike).
     */
    private static void injectIntoBootstrap(Instrumentation inst) {
        // '.dat' because ShadowJar explodes *.jar entries in its inputs; see build.gradle.
        String resource = "bridge/tracelens-bridge.jar.dat";
        try (java.io.InputStream in = TraceLensAgent.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("[tracelens] embedded " + resource + " not found in agent jar");
            }
            File temp = File.createTempFile("tracelens-bridge", ".jar");
            temp.deleteOnExit();
            java.nio.file.Files.copy(in, temp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            inst.appendToBootstrapClassLoaderSearch(new JarFile(temp));
            log("injected bridge into bootstrap classloader (" + temp + ")");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("[tracelens] cannot inject bridge into bootstrap", e);
        }
    }

    /** Debug aid: -Dtracelens.dumpDir=/path dumps every woven class's bytes there. */
    private static void dumpClass(TypeDescription typeDescription, DynamicType dynamicType) {
        String dumpDir = System.getProperty("tracelens.dumpDir");
        if (dumpDir == null) {
            return;
        }
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(dumpDir);
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.write(dir.resolve(typeDescription.getSimpleName() + ".class"),
                    dynamicType.getBytes());
        } catch (Exception e) {
            log("dump failed for " + typeDescription.getName() + ": " + e);
        }
    }

    private static void printSummary() {
        log("---- shutdown summary ----");
        log("instrumented classes: " + INSTRUMENTED_CLASSES.get());
        log("failed classes:       " + FAILED_CLASSES.get());
        log("events emitted:       " + TraceRuntime.emittedCount());
        log("dropped (no sink):    " + TraceRuntime.droppedNoSinkCount());
        log("dropped (no trace):   " + TraceRuntime.droppedNoTraceCount());
        log("wrapped tasks:        " + TraceRuntime.wrappedTaskCount());
    }

    private static void log(String message) {
        System.err.println("[tracelens] " + message);
    }
}
