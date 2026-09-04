package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.TraceRecorder;
import net.bytebuddy.agent.builder.AgentBuilder;
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
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.none;

/**
 * tracelens spike agent: line-level capture for configured packages.
 *
 * Config (system properties):
 *   -Dtracelens.packages=com.chargebee.ems,com.example   (default: com.chargebee.ems)
 *
 * The bridge (TraceRecorder) must be visible to instrumented classes in any
 * classloader, so the agent jar is appended to the bootstrap search path.
 */
public final class TraceLensAgent {

    private static final String PACKAGES_PROP = "tracelens.packages";
    private static final String DEFAULT_PACKAGE = "com.chargebee.ems";

    private static final AtomicLong INSTRUMENTED_CLASSES = new AtomicLong();
    private static final AtomicLong FAILED_CLASSES = new AtomicLong();

    private TraceLensAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        long startNanos = System.nanoTime();
        log("premain starting");

        injectIntoBootstrap(inst);

        ElementMatcher.Junction<TypeDescription> typeMatcher = none();
        for (String pkg : configuredPackages()) {
            typeMatcher = typeMatcher.or(nameStartsWith(pkg.endsWith(".") ? pkg : pkg + "."));
        }

        new AgentBuilder.Default()
                .ignore(nameStartsWith("com.chargebee.tracelens.")
                        .or(nameStartsWith("net.bytebuddy."))
                        .or(nameStartsWith("com.chargebee.tracelens.shaded."))
                        .or(isSynthetic()))
                .type(typeMatcher)
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(LineEmittingVisitor.forMethods()))
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
        log("premain done in " + elapsedMs + " ms; packages=" + configuredPackages());

        Runtime.getRuntime().addShutdownHook(new Thread(TraceLensAgent::printSummary, "tracelens-summary"));
    }

    public static void agentmain(String args, Instrumentation inst) {
        premain(args, inst);
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

    private static List<String> configuredPackages() {
        String raw = System.getProperty(PACKAGES_PROP, DEFAULT_PACKAGE);
        List<String> packages = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                packages.add(trimmed);
            }
        }
        return packages;
    }

    private static void printSummary() {
        log("---- shutdown summary ----");
        log("instrumented classes: " + INSTRUMENTED_CLASSES.get());
        log("failed classes:       " + FAILED_CLASSES.get());
        log("line events captured: " + TraceRecorder.lineEventCount());
        String[] sample = TraceRecorder.sample();
        int shown = 0;
        for (String entry : sample) {
            if (entry == null) {
                break;
            }
            if (shown < 20) {
                log("  sample " + entry);
            }
            shown++;
        }
        log("sampled " + shown + " of first " + TraceRecorder.SAMPLE_CAP + " events");
    }

    private static void log(String message) {
        System.err.println("[tracelens] " + message);
    }
}
