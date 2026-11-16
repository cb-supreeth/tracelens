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
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarFile;

import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isAnnotatedWith;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isDefaultFinalizer;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.nameContains;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.none;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * tracelens agent: line-level + method-level capture for configured packages.
 *
 * Config resolution (adapter mode requires scope; standalone can record HTTP metadata alone):
 *   1. -Dtracelens.configPath=/abs/path/tracelens.json   (full config)
 *   2. -Dtracelens.packages=com.chargebee.ems            (minimal fallback)
 *   3. -javaagent:...=standalone without scope          (request metadata only)
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
        boolean standalone = "standalone".equals(args) || Boolean.getBoolean("tracelens.standalone");
        if (standalone) System.setProperty("tracelens.standalone", "true");
        try {
            initialize(inst, standalone);
        } catch (Throwable failure) {
            if (!standalone) throw new IllegalStateException("TraceLens initialization failed", failure);
            // An optional observer must not keep the host from starting (for example
            // on an unsupported VM, an invalid config, or an unavailable debug port).
            try { StandaloneRuntime.stop(); } catch (Throwable ignored) { }
            log("standalone initialization failed; continuing application without tracing: " + failure);
        }
    }

    private static void initialize(Instrumentation inst, boolean standalone) {
        long startNanos = System.nanoTime();
        log("premain starting");

        TraceLensConfig config = resolveConfig(standalone);
        injectIntoBootstrap(inst);
        reportBuildIdentity();
        AgentStatus.configureIntegration("application-code", !config.getPackages().isEmpty());
        if (standalone && !StandaloneRuntime.start(config)) return;

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
            if (config.isInstrumentScheduled()) {
                // synthetic traces for @Scheduled jobs (line/method events still apply)
                out = out.visit(Advice.to(ScheduledJobAdvice.class)
                        .on(isAnnotatedWith(named("org.springframework.scheduling.annotation.Scheduled"))
                                .and(LineEmittingVisitor.INSTRUMENTABLE_METHOD)));
            }
            // DB_ROUTE events from any Spring routing datasource in scope (EMS's
            // RoutingDataSource -> live/test x master/slave x RLS visibility)
            out = out.visit(Advice.to(DbRouteAdvice.class)
                    .on(named("determineCurrentLookupKey")
                            .and(isDeclaredBy(hasSuperType(named(
                                    "org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource"))))));
            // EXTERNAL_CALL events for configured client-wrapper classes
            if (!config.getExternalCallClasses().isEmpty()) {
                ElementMatcher.Junction<TypeDescription> externalTypes = none();
                for (String className : config.getExternalCallClasses()) {
                    externalTypes = externalTypes.or(named(className));
                }
                out = out.visit(Advice.to(ExternalCallAdvice.class)
                        .on(LineEmittingVisitor.INSTRUMENTABLE_METHOD.and(isDeclaredBy(externalTypes))));
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
                        AgentStatus.integrationTransformed("application-code");
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
                        AgentStatus.integrationFailed("application-code");
                        log("FAILED to instrument " + typeName + ": " + throwable);
                    }

                    @Override
                    public void onComplete(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
                    }
                })
                .installOn(inst);

        AgentStatus.integrationInstalled("application-code");

        if (config.isInstrumentExecutors()) {
            installExecutorInstrumentation(inst);
        }
        ChargebeeJobInstrumentation.install(inst, config.getChargebeeJobTypes());
        com.chargebee.tracelens.bridge.MessagingBoundaryRuntime.configure(
                config.isCaptureMessagingBoundaries(), config.isInstrumentMessaging());
        if (config.isInstrumentMessaging() || config.isCaptureMessagingBoundaries()) {
            installMessagingInstrumentation(inst);
        }
        DataStoreInstrumentation.install(inst, config.isCaptureDataStoreBoundaries());
        JdbcInstrumentation.install(inst, config.isCaptureJdbcQueries(),
                config.getMaxSqlLength(), config.getMaxSqlQueriesPerRequest(), config.isCaptureJdbcParameters());
        ClickHouseInstrumentation.install(inst, config.isCaptureClickHouseQueries(),
                config.getMaxSqlLength(), config.getMaxSqlQueriesPerRequest(), config.isCaptureClickHouseParameters());
        if (config.isCaptureClickHouseQueries()) {
            log("RAW native ClickHouse SQL capture enabled for local debugging; SQL literals may contain sensitive data. "
                    + (config.isCaptureClickHouseParameters() ? "Named binding capture is enabled. " : "Bindings are not captured. ")
                    + "Query results are not captured.");
        }
        if (config.isCaptureJdbcQueries()) {
            log("RAW JDBC SQL capture enabled for local debugging; SQL literals may contain sensitive data. "
                    + (config.isCaptureJdbcParameters() ? "Bound parameter capture is enabled. " : "Bound parameters are not captured. ")
                    + "Result rows are not captured.");
        }
        if (standalone) {
            if (config.isCaptureHttpQueryParameters() || config.isCaptureHttpRequestBodies()) {
                log("Incoming HTTP request evidence capture enabled for local debugging. "
                        + (config.isRedactHttpPayloads()
                        ? "Sensitive-field redaction is best-effort; review captures before sharing."
                        : "UNREDACTED query/body capture: credentials and other sensitive fields may be retained in exports and saved snapshots. Keep captures local."));
            }
            HttpInstrumentation.install(inst);
            if (config.isCaptureHttpRequestBodies()) {
                try { HttpRequestBodyInstrumentation.install(inst); }
                catch (Throwable unavailable) {
                    AgentStatus.noteFailedClass();
                    log("HTTP request body instrumentation unavailable: " + unavailable.getClass().getName());
                }
            }
            VertxHttpInstrumentation.install(inst);
            HttpIdentityInstrumentation.install(inst, config.getHttpIdentityHooks());
            if (config.isHttpClientCaptureEnabled()) {
                HttpClientInstrumentation.install(inst);
                ApacheHttpClientInstrumentation.install(inst);
            }
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

    private static void reportBuildIdentity() {
        try {
            java.security.CodeSource source = TraceLensAgent.class.getProtectionDomain().getCodeSource();
            if (source == null || !"file".equals(source.getLocation().getProtocol())) return;
            java.nio.file.Path path = java.nio.file.Paths.get(source.getLocation().toURI());
            if (!java.nio.file.Files.isRegularFile(path) || java.nio.file.Files.size(path) > 128L * 1024 * 1024) return;
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            long deadline = System.nanoTime() + 2_000_000_000L;
            try (java.io.InputStream input = java.nio.file.Files.newInputStream(path)) {
                byte[] buffer = new byte[8192]; int count; long bytes = 0;
                while ((count = input.read(buffer)) != -1) {
                    bytes += count;
                    if (bytes > 128L * 1024 * 1024 || System.nanoTime() > deadline) return;
                    digest.update(buffer, 0, count);
                }
            }
            StringBuilder value = new StringBuilder(64);
            for (byte item : digest.digest()) value.append(Character.forDigit((item & 255) >>> 4, 16)).append(Character.forDigit(item & 15, 16));
            AgentStatus.reportBuildId(value.toString());
        } catch (Exception unavailable) { /* Unreported provenance must not prevent application startup. */ }
    }

    public static void agentmain(String args, Instrumentation inst) {
        premain(args, inst);
    }

    private static TraceLensConfig resolveConfig(boolean standalone) {
        String configPath = System.getProperty(TraceLensConfigLoader.CONFIG_PATH_PROPERTY);
        if (configPath != null && !configPath.trim().isEmpty()) {
            TraceLensConfig config = new TraceLensConfigLoader().load(standalone);
            log("loaded config from " + configPath + " (applicationId=" + config.getApplicationId() + ")");
            return config;
        }
        String packages = System.getProperty(PACKAGES_PROP);
        if (packages != null && !packages.trim().isEmpty()) {
            List<String> packageList = new ArrayList<>();
            for (String part : packages.split(",")) {
                if (!part.trim().isEmpty()) {
                    packageList.add(part.trim());
                }
            }
            log("no configPath; using -D" + PACKAGES_PROP + "=" + packages);
            return new TraceLensConfig()
                    .setApplicationId(System.getProperty("tracelens.applicationId", "cb-app"))
                    .setApplicationVersion(System.getProperty("tracelens.applicationVersion", "LOCAL"))
                    .setEvictOnExport(!standalone)
                    .setPackages(packageList);
        }
        if (standalone) {
            log("no package scope supplied: HTTP metadata only; set -Dtracelens.packages for line capture");
            return new TraceLensConfig()
                    .setApplicationId(System.getProperty("tracelens.applicationId", "cb-app"))
                    .setApplicationVersion(System.getProperty("tracelens.applicationVersion", "LOCAL"))
                    .setEvictOnExport(false);
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
     * Identity-preserving context association at supported executor hand-off points.
     * JDK classes are already loaded when premain runs, so this needs retransformation.
     * Failure here must not kill line capture — log and move on.
     */
    private static void installExecutorInstrumentation(Instrumentation inst) {
        try {
            // Verify bootstrap helper visibility before modifying JDK execution paths.
            Class.forName("com.chargebee.tracelens.bridge.ExecutorTraceRuntime", true, null);
            Class.forName("java.util.concurrent.ThreadPoolExecutor", true, null);
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
                    .with(new AgentBuilder.Listener.Adapter() {
                        @Override public void onTransformation(TypeDescription type, ClassLoader loader,
                                                               JavaModule module, boolean loaded, DynamicType dynamicType) {
                            log("executor hooks transformed " + type.getName());
                        }
                        @Override public void onError(String typeName, ClassLoader loader, JavaModule module,
                                                      boolean loaded, Throwable failure) {
                            AgentStatus.noteFailedClass();
                            log("executor instrumentation error for " + typeName + ": " + failure);
                        }
                    })
                    .type(named("java.util.concurrent.ThreadPoolExecutor"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(ExecutorTraceAdvice.Submit.class)
                                    .on(named("execute").and(takesArgument(0, Runnable.class))))
                                    .visit(Advice.to(ExecutorTraceAdvice.Before.class).on(named("beforeExecute")))
                                    .visit(Advice.to(ExecutorTraceAdvice.After.class).on(named("afterExecute")))
                                    .visit(Advice.to(ExecutorTraceAdvice.Reject.class).on(named("reject")))
                                    .visit(Advice.to(ExecutorTraceAdvice.Remove.class).on(named("remove")))
                                    .visit(Advice.to(ExecutorTraceAdvice.Purge.class).on(named("purge")))
                                    .visit(Advice.to(ExecutorTraceAdvice.Stop.class).on(named("shutdownNow"))))
                    .installOn(inst);

            log("executor instrumentation installed (exact ThreadPoolExecutor with exact LinkedBlockingQueue/ArrayBlockingQueue/SynchronousQueue; other executors/queues require explicit context wrappers)");
        } catch (Throwable t) {
            log("executor instrumentation failed (continuing without it): " + t);
        }
    }

    /**
     * Metadata-only SQS/Kafka boundaries, existing SQS attribute injection, and
     * queueman consumer continuation. App-loaded classes, so plain ignore rules are
     * fine; RETRANSFORMATION covers anything loaded before premain completed.
     */
    private static void installMessagingInstrumentation(Instrumentation inst) {
        try {
            new AgentBuilder.Default()
                    .disableClassFormatChanges()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                    .ignore(nameStartsWith("net.bytebuddy.").or(isSynthetic()))
                    .type(hasSuperType(named("com.amazonaws.services.sqs.AmazonSQS"))
                            .and(not(isInterface())).and(not(isAbstract())))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(SqsProducerAdvice.class)
                                    .on(named("sendMessage").and(takesArgument(0,
                                                    named("com.amazonaws.services.sqs.model.SendMessageRequest")))
                                            .or(named("sendMessageBatch").and(takesArgument(0,
                                                    named("com.amazonaws.services.sqs.model.SendMessageBatchRequest"))))
                                            .and(takesArguments(1)).and(not(isAbstract())))))
                    .installOn(inst);

            new AgentBuilder.Default()
                    .disableClassFormatChanges()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                    .ignore(nameStartsWith("net.bytebuddy.").or(isSynthetic()))
                    .type(named("com.chargebee.queueman.listener.AbstractQueuemanMessageHandler"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(QueuemanConsumerAdvice.class)
                                    .on(named("processMessage").and(takesArguments(3)))))
                    .installOn(inst);

            new AgentBuilder.Default()
                    .disableClassFormatChanges()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                    .ignore(nameStartsWith("net.bytebuddy.").or(isSynthetic()))
                    .type(named("org.apache.kafka.clients.producer.KafkaProducer"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(KafkaBoundaryAdvice.Producer.class)
                                    .on(named("send").and(takesArguments(1).or(takesArguments(2)))
                                            .and(takesArgument(0, named("org.apache.kafka.clients.producer.ProducerRecord")))
                                            .and(not(isAbstract())))))
                    .installOn(inst);

            new AgentBuilder.Default()
                    .disableClassFormatChanges()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                    .ignore(nameStartsWith("net.bytebuddy.").or(isSynthetic()))
                    .type(named("org.apache.kafka.clients.consumer.KafkaConsumer"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(KafkaBoundaryAdvice.Consumer.class)
                                    .on(named("poll").and(takesArguments(1))
                                            .and(returns(named("org.apache.kafka.clients.consumer.ConsumerRecords")))
                                            .and(not(isAbstract())))))
                    .installOn(inst);
            log("messaging instrumentation installed (sqs sends, queueman handlers, kafka send/poll)");
        } catch (Throwable t) {
            log("messaging instrumentation failed (continuing without it): " + t);
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
            java.nio.file.Path dir = java.nio.file.Paths.get(dumpDir);
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
