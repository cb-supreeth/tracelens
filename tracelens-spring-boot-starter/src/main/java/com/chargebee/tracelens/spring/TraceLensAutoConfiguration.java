package com.chargebee.tracelens.spring;

import com.chargebee.tracelens.core.config.TraceLensConfig;
import com.chargebee.tracelens.core.config.TraceLensConfigLoader;
import com.chargebee.tracelens.core.export.TraceExporter;
import com.chargebee.tracelens.core.record.TraceRecording;
import com.chargebee.tracelens.core.record.Tracer;
import com.chargebee.tracelens.core.store.TraceStore;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

/**
 * Wires tracelens into a Spring Boot app. Entirely gated on tracelens.enabled=true —
 * with the property absent/false (prod) no bean exists and the app is untouched.
 *
 * Config precedence: -Dtracelens.configPath / TRACELENS_CONFIG_PATH / tracelens.config-path
 * (the JSON file shared with the agent) → otherwise synthesized from tracelens.*
 * properties (applicationId defaults to spring.application.name, then cb-app).
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "tracelens", name = "enabled", havingValue = "true")
@ConditionalOnExpression("!${tracelens.standalone:false}")
@EnableConfigurationProperties(TraceLensProperties.class)
public class TraceLensAutoConfiguration {

    @Bean
    TraceLensConfig traceLensConfig(TraceLensProperties props, Environment env) {
        String path = firstNonBlank(
                System.getProperty(TraceLensConfigLoader.CONFIG_PATH_PROPERTY),
                System.getenv("TRACELENS_CONFIG_PATH"),
                props.getConfigPath());
        if (path != null) {
            return new TraceLensConfigLoader().load(Path.of(path));
        }
        TraceLensConfig config = new TraceLensConfig()
                .setApplicationId(firstNonBlank(props.getApplicationId(),
                        env.getProperty("spring.application.name"), "cb-app"))
                .setApplicationVersion(props.getApplicationVersion())
                .setPackages(props.getPackages())
                .setExcludedPackages(props.getExcludedPackages())
                .setBlacklistedNodes(props.getBlacklistedNodes())
                .setCapturePayloads(props.isCapturePayloads())
                .setMaxTrackedEvents(props.getMaxTrackedEvents())
                .setMaxTrackedBytes(props.getMaxTrackedBytes())
                .setMaxSegmentBytes(props.getMaxSegmentBytes())
                .setCaptureMode(props.getCaptureMode())
                .setDetailedCapturePaths(props.getDetailedCapturePaths())
                .setDetailedCaptureUntilEpochMillis(props.getDetailedCaptureUntilEpochMillis())
                .setMaxTrackedRequests(props.getMaxTrackedRequests())
                .setHttpRetentionPercent(props.getHttpRetentionPercent())
                .setIdleTtlMinutes(props.getIdleTtlMinutes())
                .setEvictOnExport(props.isEvictOnExport())
                .setTrackMethods(props.isTrackMethods())
                .setTrackExceptions(props.isTrackExceptions())
                .setInstrumentExecutors(props.isInstrumentExecutors())
                .setCaptureDataStoreBoundaries(props.isCaptureDataStoreBoundaries())
                .setCaptureJdbcQueries(props.isCaptureJdbcQueries())
                .setCaptureJdbcParameters(props.isCaptureJdbcParameters())
                .setCaptureClickHouseQueries(props.isCaptureClickHouseQueries())
                .setCaptureClickHouseParameters(props.isCaptureClickHouseParameters())
                .setMaxSqlLength(props.getMaxSqlLength())
                .setMaxSqlQueriesPerRequest(props.getMaxSqlQueriesPerRequest())
                .setRequireLabels(props.isRequireLabels());
        List<String> errors = config.validate();
        if (!errors.isEmpty()) {
            throw new IllegalStateException("invalid tracelens configuration: " + String.join("; ", errors));
        }
        return config;
    }

    @Bean
    @ConditionalOnMissingBean
    TraceStore traceStore(TraceLensConfig config) {
        return new TraceStore(config);
    }

    @Bean
    @ConditionalOnMissingBean
    Tracer tracer(TraceStore store) {
        return new Tracer(store);
    }

    @Bean
    @ConditionalOnMissingBean
    TraceExporter traceExporter(TraceLensConfig config) {
        return new TraceExporter(config, true);
    }

    @Bean
    FilterRegistrationBean<TraceLensFilter> traceLensFilter(Tracer tracer, TraceStore store,
                                                            TraceLensConfig config,
                                                            TraceLensProperties props) {
        FilterRegistrationBean<TraceLensFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TraceLensFilter(tracer, store, config.isRequireLabels(),
                props.getIgnoredPaths()));
        registration.addUrlPatterns("/*");
        // Run after Spring Security has established cb-ms request context, but still
        // before normal application filters/controllers. This lets auto-labelled
        // traces use <logged-in-user>_<site-id> from their first event onward.
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 100);
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC,
                DispatcherType.ERROR));
        return registration;
    }

    @Bean
    ServletRegistrationBean<TraceLensDebugServlet> traceLensDebugServlet(TraceStore store,
                                                                         TraceExporter exporter,
                                                                         TraceLensConfig config) {
        return new ServletRegistrationBean<>(new TraceLensDebugServlet(store, exporter, config),
                "/tracelens/*");
    }

    /** Installs the core sink + lifecycle listener into the bridge; starts the sweeper. */
    @Bean
    TraceLensLifecycle traceLensLifecycle(TraceStore store, Tracer tracer, TraceLensConfig config) {
        return new TraceLensLifecycle(store, tracer, config);
    }

    static final class TraceLensLifecycle implements org.springframework.context.SmartLifecycle,
            DisposableBean {

        private final TraceStore store;
        private final Tracer tracer;
        private final TraceLensConfig config;
        private volatile boolean running;

        TraceLensLifecycle(TraceStore store, Tracer tracer, TraceLensConfig config) {
            this.store = store;
            this.tracer = tracer;
            this.config = config;
        }

        @Override
        public void start() {
            com.chargebee.tracelens.bridge.DataStoreBoundaryRuntime.configure(config.isCaptureDataStoreBoundaries());
            com.chargebee.tracelens.bridge.JdbcQueryRuntime.configure(config.isCaptureJdbcQueries(),
                    config.getMaxSqlLength(), config.getMaxSqlQueriesPerRequest(), config.isCaptureJdbcParameters());
            com.chargebee.tracelens.bridge.ClickHouseQueryRuntime.configure(config.isCaptureClickHouseQueries(),
                    config.getMaxSqlLength(), config.getMaxSqlQueriesPerRequest(), config.isCaptureClickHouseParameters());
            TraceRecording.install(store);
            // agent-driven entry points (queueman consumer, @Scheduled jobs)
            com.chargebee.tracelens.bridge.TraceRuntime.registerLifecycleListener(
                    new com.chargebee.tracelens.core.record.TraceLifecycle(tracer));
            store.startSweeper();
            running = true;
        }

        @Override
        public void stop() {
            com.chargebee.tracelens.bridge.TraceRuntime.unregisterLifecycleListener();
            TraceRecording.uninstall();
            store.close();
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public void destroy() {
            stop();
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
