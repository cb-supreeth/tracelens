package com.chargebee.tracelens.core.config;

import com.chargebee.tracelens.bridge.HttpTraceRuntime;
import com.chargebee.tracelens.bridge.TraceContext;
import com.chargebee.tracelens.bridge.TraceSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PortableEmsConfigTest {
    private static final String INCOMING_TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String INCOMING_SPAN_ID = "b7ad6b7169203331";
    // Audited against cb-app's EMS client configs and implemented EMS API mappings.
    // This is a route-filter regression matrix, not a business-flow replay.
    private static final List<String> EXPECTED_PREFIXES = List.of(
            "/features", "/feature_availability_metrics", "/features_transfer", "/items", "/custom_pricing_units",
            "/entitlements", "/entitlement_versions", "/subscriptions", "/subscription",
            "/subscription_entitlements", "/entitlement_overrides/validate",
            "/customers", "/charge_entitlements", "/meters", "/meteredFeatures",
            "/metered_features", "/usage_events", "/failed_usage_events",
            "/internal/effective_entitlements", "/internal/meters",
            "/grant_configurations", "/grant_configuration_versions",
            "/grant_configuration_overrides/validate", "/internal/effective_grant_configurations",
            "/admin/entitlement_settings", "/admin/fam_manual_calc_requests",
            "/admin/fam_auto_calc_requests", "/admin/subscription_item_sync/summary",
            "/admin/subscription_meter_entitlements/initial_sync");

    private TraceLensConfig config;
    private RecordingListener listener;

    @BeforeEach
    void loadActualPortableConfig() {
        // Gradle starts tests in the module; an IDE may start them in the repo root.
        Path relativePath = Path.of("scripts/java-bundle/ems/tracelens.json");
        Path file = Files.isRegularFile(relativePath) ? relativePath : Path.of("..").resolve(relativePath);
        assertThat(file).as("portable EMS config checked into the repository").isRegularFile();
        config = new TraceLensConfigLoader().load(file);
        listener = new RecordingListener(config);
        HttpTraceRuntime.registerListener(listener);
    }

    @AfterEach
    void cleanup() {
        HttpTraceRuntime.unregisterListener(listener);
        TraceContext.clear();
    }

    @Test
    void preservesPortableEmsScopePrivacyAndRetentionPolicy() {
        assertThat(config.getApplicationId()).isEqualTo("cb-ems");
        assertThat(config.getApplicationVersion()).isEqualTo("LOCAL");
        assertThat(config.getHttpIncludePaths())
                .containsExactlyElementsOf(EXPECTED_PREFIXES)
                .doesNotContain("/", "/internal", "/admin", "/sandbox");
        assertThat(config.getPackages()).containsExactly("com.chargebee.ems");
        assertThat(config.getExcludedPackages()).containsExactly("com.chargebee.ems.api.model");
        assertThat(config.getBlacklistedNodes()).containsExactly("ToStringBuilder");
        assertThat(config.isCapturePayloads()).isFalse();
        assertThat(config.isEvictOnExport()).isFalse();
        assertThat(config.getMaxTrackedRequests()).isEqualTo(100);
        assertThat(config.getMaxTrackedEvents()).isEqualTo(100_000);
        assertThat(config.getHttpRetentionPercent()).isEqualTo(75);
        assertThat(config.getIdleTtlMinutes()).isEqualTo(30);
        assertThat(config.isTrackMethods()).isTrue();
        assertThat(config.isTrackExceptions()).isTrue();
        assertThat(config.isInstrumentExecutors()).isTrue();
        assertThat(config.isInstrumentScheduled()).isFalse();
        assertThat(config.getChargebeeJobTypes()).isEmpty();
        assertThat(config.isInstrumentMessaging()).isFalse();
        assertThat(config.isCaptureMessagingBoundaries()).isTrue();
        assertThat(config.isCaptureDataStoreBoundaries()).isTrue();
        assertThat(config.isRequireLabels()).isFalse();
        assertThat(config.isDistributedTracingEnabled()).isTrue();
        assertThat(config.isHttpClientCaptureEnabled()).isTrue();
        assertThat(config.getHttpPropagationAllowedDestinations()).isEmpty();
        assertThat(config.getHttpIdentityHooks()).isEmpty();
        assertThat(config.getContextParams().getJvmArgs()).isEmpty();
    }

    @Test
    void capturesFeatureCreationAndBothSubscriptionEntitlementRoutes() {
        assertCaptured("POST", "/features", "");
        assertCaptured("GET", "/subscriptions/sub-test/subscription_entitlements", "");
        assertCaptured("GET", "/subscription_entitlements/ent-test", "");
    }

    @Test
    void capturesDownstreamEmsRoutesForTheShippedCbAppFlows() {
        String[][] routes = {
                // Features and item/plan entitlements.
                {"POST", "/features/search"},
                {"PUT", "/features/feature-test"},
                {"DELETE", "/features/feature-test"},
                {"POST", "/features/feature-test/activate_command"},
                {"POST", "/features/feature-test/item_entitlements"},
                {"GET", "/features/feature-test/reference_counts"},
                {"GET", "/feature_availability_metrics"},
                {"POST", "/features_transfer/check_command"},
                {"GET", "/items/item-test/item_entitlements"},
                {"POST", "/items/item-test/item_entitlements"},
                {"GET", "/custom_pricing_units"},
                {"GET", "/custom_pricing_units/unit-test"},
                {"GET", "/entitlements"},
                {"POST", "/entitlements/update_by_version"},
                {"GET", "/entitlement_versions"},
                // Subscription/customer flows, billing, estimates and ramps.
                {"GET", "/subscriptions/sub-test/entitlement_overrides"},
                {"POST", "/subscriptions/sub-test/entitlement_overrides"},
                {"POST", "/subscriptions/sub-test/subscription_entitlements/set_availability"},
                {"POST", "/subscriptions/sub-test/aggregates"},
                {"POST", "/subscription/sub-test/usage_summary"},
                {"POST", "/entitlement_overrides/validate"},
                {"GET", "/customers/customer-test/customer_entitlements"},
                {"POST", "/customers/customer-test/delete_charge_entitlements"},
                {"POST", "/subscriptions/sub-test/delete_charge_entitlements"},
                {"GET", "/charge_entitlements"},
                {"GET", "/internal/effective_entitlements"},
                {"POST", "/internal/effective_entitlements/estimate"},
                {"GET", "/internal/meters/meter-test"},
                {"GET", "/grant_configurations"},
                {"POST", "/grant_configurations"},
                {"GET", "/grant_configuration_versions"},
                {"GET", "/subscriptions/sub-test/grant_configuration_overrides"},
                {"POST", "/grant_configuration_overrides/validate"},
                {"POST", "/internal/effective_grant_configurations/estimate"},
                // Meter/usage paths are not /api/v2/... inside EMS.
                {"POST", "/meters"},
                {"GET", "/meters/meta"},
                {"POST", "/meters/preview_aggregates"},
                {"POST", "/meters/preview_usage_events"},
                {"POST", "/meters/meter-test/attach_to_features"},
                {"DELETE", "/meters/meter-test/delete"},
                {"POST", "/meteredFeatures"},
                {"POST", "/meteredFeatures/meter-test/archive_command"},
                {"PUT", "/metered_features/meter-test"},
                {"GET", "/metered_features/meter-test/usage_attribute_meter_info"},
                {"DELETE", "/metered_features/meter-test"},
                {"GET", "/usage_events"},
                {"GET", "/usage_events/meta"},
                {"GET", "/usage_events/chart"},
                {"POST", "/usage_events/arrange_properties"},
                {"GET", "/failed_usage_events"},
                // /entitlements UI actions use specific admin helper endpoints.
                {"GET", "/admin/entitlement_settings"},
                {"POST", "/admin/entitlement_settings"},
                {"GET", "/admin/fam_manual_calc_requests"},
                {"POST", "/admin/fam_manual_calc_requests"},
                {"GET", "/admin/fam_auto_calc_requests"},
                {"GET", "/admin/subscription_item_sync/summary"},
                {"POST", "/admin/subscription_meter_entitlements/initial_sync"}
        };
        for (String context : List.of("", "/ems")) {
            for (String[] route : routes) {
                assertCaptured(route[0], route[1], context);
            }
        }
    }

    @Test
    void allConfiguredPrefixesAllowHttpMethodsAndSlashDelimitedDescendants() {
        for (String prefix : EXPECTED_PREFIXES) {
            for (String method : List.of("GET", "POST", "PUT", "PATCH", "DELETE")) {
                assertCaptured(method, prefix, "");
                assertCaptured(method, prefix + "/child-test", "");
            }
        }
    }

    @Test
    void appliesExactAndDescendantPrefixesRelativeToServletContext() {
        for (String path : List.of("/features/", "/features/feature-test",
                "/subscriptions", "/subscriptions/sub-test/subscription_entitlements",
                "/subscription_entitlements", "/subscription_entitlements/ent-test")) {
            assertCaptured("GET", path, "/ems");
        }
    }

    @Test
    void unrelatedAndLookalikePathsKeepOnlyExplicitSkippedPropagationScope() {
        for (String path : List.of("/", "/api/v2/features", "/other/features",
                "/features-other", "/features_extra", "/subscriptions-other",
                "/subscription_entitlements-other", "/subscription_entitlements_extra",
                "/meteredfeatures", "/item_entitlements/item-test/list",
                "/item_families", "/item_prices", "/events", "/schemas",
                "/internal/cache_ratio_metrics", "/internal/other",
                "/admin/other", "/admin/subscription_item_sync/other",
                "/admin/subscription_meter_entitlements/other", "/sandbox",
                "/healthz", "/tracelens/summary")) {
            assertSkipped(path);
        }
        for (String prefix : EXPECTED_PREFIXES) {
            assertSkipped(prefix + "_other");
            assertSkipped(prefix + "-other");
        }
    }

    private void assertSkipped(String path) {
        assertThat(request("GET", path, "")).as(path)
                .containsEntry("captureSkipped", "path-filter")
                .containsEntry("distributedTraceId", INCOMING_TRACE_ID)
                .containsEntry("parentSpanId", INCOMING_SPAN_ID);
    }

    private void assertCaptured(String method, String path, String context) {
        assertThat(request(method, path, context)).as(method + " " + path)
                .containsEntry("httpMethod", method)
                .containsEntry("path", path)
                .doesNotContainKey("captureSkipped");
    }

    private Map<String, String> request(String method, String path, String context) {
        int count = listener.begun.size();
        Object scope = HttpTraceRuntime.enter(new Request(method, context + path, context), new Response());
        try {
            // Distributed tracing keeps a propagation scope even for excluded paths.
            assertThat(scope).as(method + " " + path).isNotNull();
            assertThat(listener.begun).hasSize(count + 1);
            assertThat(TraceContext.current()).isNotNull();
            return listener.begun.get(count);
        } finally {
            HttpTraceRuntime.exit(scope, null);
            assertThat(TraceContext.current()).isNull();
        }
    }

    private static final class RecordingListener implements HttpTraceRuntime.Listener {
        private final TraceLensConfig config;
        private final List<Map<String, String>> begun = new ArrayList<>();

        private RecordingListener(TraceLensConfig config) {
            this.config = config;
        }

        @Override
        public List<String> httpIncludePaths() {
            return config.getHttpIncludePaths();
        }

        @Override
        public boolean distributedTracingEnabled() {
            return config.isDistributedTracingEnabled();
        }

        @Override
        public TraceSnapshot begin(String label, String user, Map<String, String> attributes) {
            begun.add(new LinkedHashMap<>(attributes));
            return new TraceSnapshot(attributes.get("distributedTraceId"), "request-" + begun.size(),
                    label, user, attributes);
        }

        @Override
        public void complete(String segmentId, Map<String, String> attributes) {
        }
    }

    public static final class Request {
        private final Map<String, Object> attributes = new HashMap<>();
        private final String method;
        private final String uri;
        private final String context;

        Request(String method, String uri, String context) {
            this.method = method;
            this.uri = uri;
            this.context = context;
        }

        public String getMethod() { return method; }
        public String getRequestURI() { return uri; }
        public String getContextPath() { return context; }
        public Object getAttribute(String key) { return attributes.get(key); }
        public void setAttribute(String key, Object value) { attributes.put(key, value); }
        public String getHeader(String key) {
            return "traceparent".equals(key) ? "00-" + INCOMING_TRACE_ID + "-" + INCOMING_SPAN_ID + "-01" : null;
        }
        public boolean isAsyncStarted() { return false; }
    }

    public static final class Response {
        public void setHeader(String name, String value) { }
        public int getStatus() { return 200; }
    }
}
