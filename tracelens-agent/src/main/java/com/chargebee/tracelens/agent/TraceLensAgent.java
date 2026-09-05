package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.TraceRuntime;
import com.chargebee.tracelens.core.config.TraceLensConfig;
import com.chargebee.tracelens.core.config.TraceLensConfigLoader;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarFile;

import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.nameContains;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.none;
import static net.bytebuddy.matcher.ElementMatchers.not;

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
            DynamicType.Builder<?> out = builder.visit(LineEmittingVisitor.forMethods());
            if (methodAdvice != null) {
                out = out.visit(methodAdvice);
            }
            return out;
        };

        new AgentBuilder.Default()
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
                    }

                    @Override
                    public void onIgnored(TypeDescription typeDescription, ClassLoader classLoader,
                                          JavaModule module, boolean loaded) {
                    }

                    @Override
                    public void onError(String typeName, ClassLoader classLoader, JavaModule module,
                                        boolean loaded, Throwable throwable) {
                        FAILED_CLASSES.incrementAndGet();
                        log("FAILED to instrument " + typeName + ": " + throwable);
                    }

                    @Override
                    public void onComplete(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
                    }
                })
                .installOn(inst);

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        log("premain done in " + elapsedMs + " ms; packages=" + config.getPackages()
                + " excluded=" + config.getExcludedPackages()
                + " trackMethods=" + config.isTrackMethods()
                + " trackExceptions=" + config.isTrackExceptions());

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

    private static void printSummary() {
        log("---- shutdown summary ----");
        log("instrumented classes: " + INSTRUMENTED_CLASSES.get());
        log("failed classes:       " + FAILED_CLASSES.get());
        log("events emitted:       " + TraceRuntime.emittedCount());
        log("dropped (no sink):    " + TraceRuntime.droppedNoSinkCount());
        log("dropped (no trace):   " + TraceRuntime.droppedNoTraceCount());
    }

    private static void log(String message) {
        System.err.println("[tracelens] " + message);
    }
}
