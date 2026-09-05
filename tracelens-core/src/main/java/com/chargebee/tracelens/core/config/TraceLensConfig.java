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

    /** Registered application identity (stamped on every flow; resolved server-side later). */
    private String applicationId;

    /** Stamped on every flow. "LOCAL" for laptop runs; CI/CD may supply the build version. */
    private String applicationVersion = "LOCAL";

    /** Package prefixes to instrument (FQCN prefixes). At least one of packages/modules. */
    private List<String> packages = new ArrayList<>();

    /** Maven/JAR module fragments to instrument (multi-module projects). */
    private List<String> modules = new ArrayList<>();

    /** Prefix-matched additions to the ignore list (e.g. generated DTO packages). */
    private List<String> excludedPackages = new ArrayList<>();

    /** Substring-matched noisy classes (getters/setters, sanitizers, utility spam). */
    private List<String> blacklistedNodes = new ArrayList<>();

    /** Request/response + payload capture. PII decision — off installs no capture. */
    private boolean capturePayloads = false;

    /** Global event memory ceiling (~500k events ≈ 50 MB). */
    private long maxTrackedEvents = 500_000;

    /** Hard LRU cap on concurrently retained traces. */
    private int maxTrackedRequests = 200;

    /** Sweep a trace with no new event for this long. */
    private long idleTtlMinutes = 60;

    /** Export is destructive by default; set false where people inspect traces manually. */
    private boolean evictOnExport = true;

    /** Emit method entry/exit events (in addition to line events). */
    private boolean trackMethods = true;

    /** Turn thrown exceptions into timeline events. */
    private boolean trackExceptions = true;

    /**
     * FlowLens requires both labels before registering a flow (protects capacity on
     * shared envs). Locally we default to auto-minting labels for unlabelled traffic.
     */
    private boolean requireLabels = false;

    /** System property names resolved at startup and included in exports. */
    private ContextParams contextParams = new ContextParams();

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
        List<String> errors = new ArrayList<>();
        if (applicationId == null || applicationId.isBlank()) {
            errors.add("applicationId is mandatory");
        }
        if (applicationVersion == null || applicationVersion.isBlank()) {
            errors.add("applicationVersion is mandatory");
        }
        if (packages.isEmpty() && modules.isEmpty()) {
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
}
