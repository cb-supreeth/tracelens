package com.chargebee.tracelens.core.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * tracelens.json model. Field names follow FlowLens's flowlens.json where the
 * concept is the same, so the returns team's operational docs transfer over.
 *
 * Example:
 * {
 *   "applicationId": "cb-ems",
 *   "applicationVersion": "LOCAL",
 *   "packages": ["com.chargebee.ems"],
 *   "excludedPackages": ["com.chargebee.ems.api.model"],
 *   "blacklistedNodes": ["ToStringBuilder"],
 *   "capturePayloads": false,
 *   "maxTrackedEvents": 500000,
 *   "maxTrackedRequests": 200,
 *   "idleTtlMinutes": 60,
 *   "evictOnExport": true,
 *   "requireLabels": false,
 *   "contextParams": { "jvmArgs": ["aws.profile.name"] }
 * }
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class TraceLensConfig {

    /** Application identity stamped on exports; no central application registry exists. */
    private String applicationId;

    /** Stamped on every flow. "LOCAL" for laptop runs; CI/CD may supply the build version. */
    private String applicationVersion = "LOCAL";

    /** Effective application class scope. Standalone permits empty scope for metadata-only capture. */
    private List<String> packages = new ArrayList<>();

    /** Legacy configuration field; accepted by validation but not used by the current agent matcher. */
    private List<String> modules = new ArrayList<>();

    /** Prefix-matched additions to the ignore list (e.g. generated DTO packages). */
    private List<String> excludedPackages = new ArrayList<>();

    /** Substring-matched noisy classes (getters/setters, sanitizers, utility spam). */
    private List<String> blacklistedNodes = new ArrayList<>();

    /** Legacy payload option; current HTTP hooks do not read bodies. Not an exception/source PII filter. */
    private boolean capturePayloads = false;

    /** Global retained-event count ceiling; not a measured or exact heap-byte budget. */
    private long maxTrackedEvents = 500_000;

    /** Retained-segment count cap; eviction uses last recorded activity, not inspection reads. */
    private int maxTrackedRequests = 200;

    /** Sweep a trace with no new event for this long. */
    private long idleTtlMinutes = 60;

    /** Export is destructive by default; set false where people inspect traces manually. */
    private boolean evictOnExport = true;

    /** Emit method entry/exit events (in addition to line events). */
    private boolean trackMethods = true;

    /** Record exceptions escaping instrumented methods, not every throw or caught exception. */
    private boolean trackExceptions = true;

    /** Wrap tasks at executor hand-off points so traces survive thread pools. */
    private boolean instrumentExecutors = true;

    /** SQS producer attribute injection + queueman consumer continuation. */
    private boolean instrumentMessaging = true;

    /** Metadata-only messaging observation; null preserves the legacy messaging switch. */
    private Boolean captureMessagingBoundaries;

    /** Synthetic traces for @Scheduled jobs. */
    private boolean instrumentScheduled = true;

    /** Class names whose method entries become EXTERNAL_CALL events (client wrappers). */
    private List<String> externalCallClasses = new ArrayList<>();

    /**
     * Gate retained HTTP entry capture on resolved labels. Locally, unlabelled HTTP
     * traffic is auto-labelled by default. Not a scheduler/message gate or authentication.
     */
    private boolean requireLabels = false;

    /** System property names resolved at startup and included in exports. */
    private ContextParams contextParams = new ContextParams();

    /** Optional post-authentication identity hooks; no framework is assumed by default. */
    private List<HttpIdentityHook> httpIdentityHooks = new ArrayList<>();

    /** Opt-in HTTP correlation; local-only installs require no other service. */
    private boolean distributedTracingEnabled;
    /** Null preserves the former distributed opt-in; true also observes local-only calls. */
    private Boolean httpClientCaptureEnabled;
    /** Exact destination authorities, including non-default ports; never wildcarded. */
    private List<String> httpPropagationAllowedDestinations = new ArrayList<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class HttpIdentityHook {
        private String id;
        private String className;
        private String methodName;
        private int parameterCount;
        private int targetArgument = -1;
        private List<String> requestPath = new ArrayList<>();
        private List<String> userPath = new ArrayList<>();
        private List<String> sitePath = new ArrayList<>();
        private String trustedUserHeader;
        private List<String> trustedUserHeaderValues = new ArrayList<>();
        private String fallbackUser;

        public String getId() { return id; }
        public HttpIdentityHook setId(String value) { id = value; return this; }
        public String getClassName() { return className; }
        public HttpIdentityHook setClassName(String value) { className = value; return this; }
        public String getMethodName() { return methodName; }
        public HttpIdentityHook setMethodName(String value) { methodName = value; return this; }
        public int getParameterCount() { return parameterCount; }
        public HttpIdentityHook setParameterCount(int value) { parameterCount = value; return this; }
        public int getTargetArgument() { return targetArgument; }
        public HttpIdentityHook setTargetArgument(int value) { targetArgument = value; return this; }
        public List<String> getRequestPath() { return requestPath; }
        public HttpIdentityHook setRequestPath(List<String> value) { requestPath = list(value); return this; }
        public List<String> getUserPath() { return userPath; }
        public HttpIdentityHook setUserPath(List<String> value) { userPath = list(value); return this; }
        public List<String> getSitePath() { return sitePath; }
        public HttpIdentityHook setSitePath(List<String> value) { sitePath = list(value); return this; }
        public String getTrustedUserHeader() { return trustedUserHeader; }
        public HttpIdentityHook setTrustedUserHeader(String value) { trustedUserHeader = value; return this; }
        public List<String> getTrustedUserHeaderValues() { return trustedUserHeaderValues; }
        public HttpIdentityHook setTrustedUserHeaderValues(List<String> value) { trustedUserHeaderValues = list(value); return this; }
        public String getFallbackUser() { return fallbackUser; }
        public HttpIdentityHook setFallbackUser(String value) { fallbackUser = value; return this; }

        private static List<String> list(List<String> value) {
            return value == null ? new ArrayList<String>() : new ArrayList<>(value);
        }

        private boolean valid() {
            if (!token(id) || !id.matches("[A-Za-z0-9_.-]+")
                    || !token(className) || !className.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
                    || !getter(methodName) || parameterCount < 0 || parameterCount > 32
                    || targetArgument < -1 || targetArgument >= parameterCount
                    || !path(requestPath, true) || !path(sitePath, true) || !path(userPath, false)) return false;
            if (fallbackUser != null && !token(fallbackUser)) return false;
            if (userPath.isEmpty() && fallbackUser == null) return false;
            if (trustedUserHeader == null) return trustedUserHeaderValues.isEmpty();
            if (!token(trustedUserHeader) || !trustedUserHeader.matches("[A-Za-z0-9-]+")
                    || trustedUserHeader.equalsIgnoreCase("Authorization")
                    || trustedUserHeader.equalsIgnoreCase("Proxy-Authorization")
                    || trustedUserHeader.equalsIgnoreCase("Cookie")
                    || trustedUserHeader.equalsIgnoreCase("Set-Cookie")
                    || trustedUserHeaderValues.isEmpty() || trustedUserHeaderValues.size() > 16) return false;
            for (String value : trustedUserHeaderValues) if (!token(value)) return false;
            return true;
        }

        private static boolean path(List<String> path, boolean required) {
            if (path == null || path.size() > 8 || (required && path.isEmpty())) return false;
            for (String method : path) if (!getter(method)) return false;
            return true;
        }

        private static boolean getter(String method) {
            return token(method) && method.length() <= 128 && method.matches("[A-Za-z_$][A-Za-z0-9_$]*")
                    && !method.matches("getClass|toString|hashCode|equals|wait|notify|notifyAll|clone|finalize");
        }

        private static boolean token(String value) {
            if (value == null || value.trim().isEmpty() || value.length() > 256 || !value.equals(value.trim())) return false;
            for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 32 || value.charAt(i) > 126) return false;
            return true;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ContextParams {
        private List<String> jvmArgs = new ArrayList<>();

        public List<String> getJvmArgs() {
            return jvmArgs;
        }

        public ContextParams setJvmArgs(List<String> jvmArgs) {
            this.jvmArgs = jvmArgs;
            return this;
        }
    }

    /** FlowLens rule: identity + at least one instrumentable scope must resolve. */
    public List<String> validate() {
        return validate(false);
    }

    /** Standalone mode may deliberately record only transport metadata. */
    public List<String> validate(boolean allowEmptyScope) {
        List<String> errors = new ArrayList<>();
        errors.addAll(validateDistributedTracing());
        if (applicationId == null || applicationId.trim().isEmpty()) {
            errors.add("applicationId is mandatory");
        }
        if (applicationVersion == null || applicationVersion.trim().isEmpty()) {
            errors.add("applicationVersion is mandatory");
        }
        if (!allowEmptyScope && packages.isEmpty() && modules.isEmpty()) {
            errors.add("at least one of packages/modules must be specified");
        }
        if (maxTrackedRequests < 1) {
            errors.add("maxTrackedRequests must be >= 1");
        }
        if (maxTrackedEvents < 1_000) {
            errors.add("maxTrackedEvents must be >= 1000");
        }
        if (idleTtlMinutes < 1) {
            errors.add("idleTtlMinutes must be >= 1");
        }
        if (httpIdentityHooks.size() > 16) errors.add("httpIdentityHooks must contain at most 16 hooks");
        java.util.Set<String> hookIds = new java.util.HashSet<>();
        java.util.Set<String> hookTargets = new java.util.HashSet<>();
        for (HttpIdentityHook hook : httpIdentityHooks) {
            if (hook == null || !hook.valid()) {
                errors.add("invalid httpIdentityHooks entry (requires exact method, bounded getter paths and safe labels)");
            } else if (!hookIds.add(hook.getId()) || !hookTargets.add(hook.getClassName() + "#"
                    + hook.getMethodName() + "/" + hook.getParameterCount())) {
                errors.add("httpIdentityHooks ids and method targets must be unique");
            }
        }
        return errors;
    }

    public String getApplicationId() {
        return applicationId;
    }

    public TraceLensConfig setApplicationId(String applicationId) {
        this.applicationId = applicationId;
        return this;
    }

    public String getApplicationVersion() {
        return applicationVersion;
    }

    public TraceLensConfig setApplicationVersion(String applicationVersion) {
        this.applicationVersion = applicationVersion;
        return this;
    }

    public List<String> getPackages() {
        return packages;
    }

    public TraceLensConfig setPackages(List<String> packages) {
        this.packages = packages;
        return this;
    }

    public List<String> getModules() {
        return modules;
    }

    public TraceLensConfig setModules(List<String> modules) {
        this.modules = modules;
        return this;
    }

    public List<String> getExcludedPackages() {
        return excludedPackages;
    }

    public TraceLensConfig setExcludedPackages(List<String> excludedPackages) {
        this.excludedPackages = excludedPackages;
        return this;
    }

    public List<String> getBlacklistedNodes() {
        return blacklistedNodes;
    }

    public TraceLensConfig setBlacklistedNodes(List<String> blacklistedNodes) {
        this.blacklistedNodes = blacklistedNodes;
        return this;
    }

    public boolean isCapturePayloads() {
        return capturePayloads;
    }

    public TraceLensConfig setCapturePayloads(boolean capturePayloads) {
        this.capturePayloads = capturePayloads;
        return this;
    }

    public long getMaxTrackedEvents() {
        return maxTrackedEvents;
    }

    public TraceLensConfig setMaxTrackedEvents(long maxTrackedEvents) {
        this.maxTrackedEvents = maxTrackedEvents;
        return this;
    }

    public int getMaxTrackedRequests() {
        return maxTrackedRequests;
    }

    public TraceLensConfig setMaxTrackedRequests(int maxTrackedRequests) {
        this.maxTrackedRequests = maxTrackedRequests;
        return this;
    }

    public long getIdleTtlMinutes() {
        return idleTtlMinutes;
    }

    public TraceLensConfig setIdleTtlMinutes(long idleTtlMinutes) {
        this.idleTtlMinutes = idleTtlMinutes;
        return this;
    }

    public boolean isEvictOnExport() {
        return evictOnExport;
    }

    public TraceLensConfig setEvictOnExport(boolean evictOnExport) {
        this.evictOnExport = evictOnExport;
        return this;
    }

    public boolean isTrackMethods() {
        return trackMethods;
    }

    public TraceLensConfig setTrackMethods(boolean trackMethods) {
        this.trackMethods = trackMethods;
        return this;
    }

    public boolean isTrackExceptions() {
        return trackExceptions;
    }

    public TraceLensConfig setTrackExceptions(boolean trackExceptions) {
        this.trackExceptions = trackExceptions;
        return this;
    }

    public boolean isInstrumentExecutors() {
        return instrumentExecutors;
    }

    public TraceLensConfig setInstrumentExecutors(boolean instrumentExecutors) {
        this.instrumentExecutors = instrumentExecutors;
        return this;
    }

    public boolean isInstrumentMessaging() {
        return instrumentMessaging;
    }

    public TraceLensConfig setInstrumentMessaging(boolean instrumentMessaging) {
        this.instrumentMessaging = instrumentMessaging;
        return this;
    }

    public boolean isCaptureMessagingBoundaries() {
        return captureMessagingBoundaries == null ? instrumentMessaging : captureMessagingBoundaries;
    }

    public TraceLensConfig setCaptureMessagingBoundaries(Boolean captureMessagingBoundaries) {
        this.captureMessagingBoundaries = captureMessagingBoundaries;
        return this;
    }

    public boolean isInstrumentScheduled() {
        return instrumentScheduled;
    }

    public TraceLensConfig setInstrumentScheduled(boolean instrumentScheduled) {
        this.instrumentScheduled = instrumentScheduled;
        return this;
    }

    public List<String> getExternalCallClasses() {
        return externalCallClasses;
    }

    public TraceLensConfig setExternalCallClasses(List<String> externalCallClasses) {
        this.externalCallClasses = externalCallClasses;
        return this;
    }

    public boolean isRequireLabels() {
        return requireLabels;
    }

    public TraceLensConfig setRequireLabels(boolean requireLabels) {
        this.requireLabels = requireLabels;
        return this;
    }

    public ContextParams getContextParams() {
        return contextParams;
    }

    public TraceLensConfig setContextParams(ContextParams contextParams) {
        this.contextParams = contextParams;
        return this;
    }

    public List<HttpIdentityHook> getHttpIdentityHooks() { return httpIdentityHooks; }

    public boolean isDistributedTracingEnabled() { return distributedTracingEnabled; }
    public TraceLensConfig setDistributedTracingEnabled(boolean enabled) {
        distributedTracingEnabled = enabled;
        return this;
    }
    public boolean isHttpClientCaptureEnabled() {
        return httpClientCaptureEnabled == null ? distributedTracingEnabled : httpClientCaptureEnabled;
    }
    public TraceLensConfig setHttpClientCaptureEnabled(Boolean enabled) {
        httpClientCaptureEnabled = enabled;
        return this;
    }
    public List<String> getHttpPropagationAllowedDestinations() { return httpPropagationAllowedDestinations; }
    public TraceLensConfig setHttpPropagationAllowedDestinations(List<String> destinations) {
        httpPropagationAllowedDestinations = destinations == null ? new ArrayList<String>() : new ArrayList<>(destinations);
        return this;
    }

    public List<String> validateDistributedTracing() {
        List<String> errors = new ArrayList<>();
        if (httpPropagationAllowedDestinations.size() > 64) errors.add("at most 64 HTTP propagation destinations allowed");
        for (String destination : httpPropagationAllowedDestinations) {
            try {
                if (destination == null || destination.length() > 255 || !destination.equals(destination.trim())
                        || destination.contains("*") || destination.contains("/") || destination.contains("?")) throw new IllegalArgumentException();
                java.net.URI uri = new java.net.URI("http://" + destination);
                if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                        || uri.getPort() > 65535 || uri.getPort() == 0 || !destination.equals(uri.getRawAuthority())) {
                    throw new IllegalArgumentException();
                }
            } catch (Exception invalid) {
                errors.add("HTTP propagation destinations must be exact host[:port] authorities, without credentials or wildcards");
            }
        }
        return errors;
    }

    public TraceLensConfig setHttpIdentityHooks(List<HttpIdentityHook> hooks) {
        this.httpIdentityHooks = hooks == null ? new ArrayList<HttpIdentityHook>() : new ArrayList<>(hooks);
        return this;
    }
}
