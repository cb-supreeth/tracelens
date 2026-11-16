package com.chargebee.tracelens.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring-side knobs (tracelens.*). When tracelens.config-path (or
 * -Dtracelens.configPath) points at a tracelens.json, the file wins and these
 * property fields are ignored — one source of truth shared with the agent.
 */
@ConfigurationProperties(prefix = "tracelens")
public class TraceLensProperties {

    /** Master switch. False everywhere except local/dev profiles. */
    private boolean enabled = false;

    /** Optional path to tracelens.json; -Dtracelens.configPath / TRACELENS_CONFIG_PATH win over this. */
    private String configPath;

    private String applicationId;
    private String applicationVersion = "LOCAL";
    private List<String> packages = new ArrayList<>();
    private List<String> excludedPackages = new ArrayList<>();
    private List<String> blacklistedNodes = new ArrayList<>();
    private boolean capturePayloads = false;
    private long maxTrackedEvents = 500_000;
    private long maxTrackedBytes = 256L * 1024 * 1024;
    private long maxSegmentBytes = 32L * 1024 * 1024;
    private String captureMode = "detailed";
    private List<String> detailedCapturePaths = new ArrayList<>();
    private long detailedCaptureUntilEpochMillis;
    private int maxTrackedRequests = 200;
    private int httpRetentionPercent = 75;
    private long idleTtlMinutes = 60;
    private boolean evictOnExport = true;
    private boolean trackMethods = true;
    private boolean trackExceptions = true;
    private boolean instrumentExecutors = true;
    private boolean captureDataStoreBoundaries = true;
    private boolean captureJdbcQueries = false;
    private boolean captureJdbcParameters = false;
    private boolean captureClickHouseQueries = false;
    private boolean captureClickHouseParameters = false;
    private int maxSqlLength = 4096;
    private int maxSqlQueriesPerRequest = 100;

    /** When true, only requests carrying tracelens-id + tracelens-user are traced. */
    private boolean requireLabels = false;

    /** Paths never traced (besides the /tracelens debug API itself). */
    private List<String> ignoredPaths = new ArrayList<>(List.of("/healthz", "/favicon.ico"));

    public boolean isEnabled() {
        return enabled;
    }

    public TraceLensProperties setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public String getConfigPath() {
        return configPath;
    }

    public TraceLensProperties setConfigPath(String configPath) {
        this.configPath = configPath;
        return this;
    }

    public String getApplicationId() {
        return applicationId;
    }

    public TraceLensProperties setApplicationId(String applicationId) {
        this.applicationId = applicationId;
        return this;
    }

    public String getApplicationVersion() {
        return applicationVersion;
    }

    public TraceLensProperties setApplicationVersion(String applicationVersion) {
        this.applicationVersion = applicationVersion;
        return this;
    }

    public List<String> getPackages() {
        return packages;
    }

    public TraceLensProperties setPackages(List<String> packages) {
        this.packages = packages;
        return this;
    }

    public List<String> getExcludedPackages() {
        return excludedPackages;
    }

    public TraceLensProperties setExcludedPackages(List<String> excludedPackages) {
        this.excludedPackages = excludedPackages;
        return this;
    }

    public List<String> getBlacklistedNodes() {
        return blacklistedNodes;
    }

    public TraceLensProperties setBlacklistedNodes(List<String> blacklistedNodes) {
        this.blacklistedNodes = blacklistedNodes;
        return this;
    }

    public boolean isCapturePayloads() {
        return capturePayloads;
    }

    public TraceLensProperties setCapturePayloads(boolean capturePayloads) {
        this.capturePayloads = capturePayloads;
        return this;
    }

    public long getMaxTrackedEvents() {
        return maxTrackedEvents;
    }

    public long getMaxTrackedBytes() { return maxTrackedBytes; }
    public TraceLensProperties setMaxTrackedBytes(long value) { maxTrackedBytes = value; return this; }
    public long getMaxSegmentBytes() { return maxSegmentBytes; }
    public TraceLensProperties setMaxSegmentBytes(long value) { maxSegmentBytes = value; return this; }
    public String getCaptureMode() { return captureMode; }
    public TraceLensProperties setCaptureMode(String value) { captureMode = value; return this; }
    public List<String> getDetailedCapturePaths() { return detailedCapturePaths; }
    public TraceLensProperties setDetailedCapturePaths(List<String> value) { detailedCapturePaths = value; return this; }
    public long getDetailedCaptureUntilEpochMillis() { return detailedCaptureUntilEpochMillis; }
    public TraceLensProperties setDetailedCaptureUntilEpochMillis(long value) { detailedCaptureUntilEpochMillis = value; return this; }

    public TraceLensProperties setMaxTrackedEvents(long maxTrackedEvents) {
        this.maxTrackedEvents = maxTrackedEvents;
        return this;
    }

    public int getMaxTrackedRequests() {
        return maxTrackedRequests;
    }

    public TraceLensProperties setMaxTrackedRequests(int maxTrackedRequests) {
        this.maxTrackedRequests = maxTrackedRequests;
        return this;
    }

    public int getHttpRetentionPercent() {
        return httpRetentionPercent;
    }

    public TraceLensProperties setHttpRetentionPercent(int httpRetentionPercent) {
        this.httpRetentionPercent = httpRetentionPercent;
        return this;
    }

    public long getIdleTtlMinutes() {
        return idleTtlMinutes;
    }

    public TraceLensProperties setIdleTtlMinutes(long idleTtlMinutes) {
        this.idleTtlMinutes = idleTtlMinutes;
        return this;
    }

    public boolean isEvictOnExport() {
        return evictOnExport;
    }

    public TraceLensProperties setEvictOnExport(boolean evictOnExport) {
        this.evictOnExport = evictOnExport;
        return this;
    }

    public boolean isTrackMethods() {
        return trackMethods;
    }

    public TraceLensProperties setTrackMethods(boolean trackMethods) {
        this.trackMethods = trackMethods;
        return this;
    }

    public boolean isTrackExceptions() {
        return trackExceptions;
    }

    public TraceLensProperties setTrackExceptions(boolean trackExceptions) {
        this.trackExceptions = trackExceptions;
        return this;
    }

    public boolean isInstrumentExecutors() {
        return instrumentExecutors;
    }

    public TraceLensProperties setInstrumentExecutors(boolean instrumentExecutors) {
        this.instrumentExecutors = instrumentExecutors;
        return this;
    }

    public boolean isCaptureDataStoreBoundaries() {
        return captureDataStoreBoundaries;
    }

    public TraceLensProperties setCaptureDataStoreBoundaries(boolean captureDataStoreBoundaries) {
        this.captureDataStoreBoundaries = captureDataStoreBoundaries;
        return this;
    }

    public boolean isRequireLabels() {
        return requireLabels;
    }

    public boolean isCaptureJdbcQueries() { return captureJdbcQueries; }

    public boolean isCaptureJdbcParameters() { return captureJdbcParameters; }

    public TraceLensProperties setCaptureJdbcParameters(boolean captureJdbcParameters) {
        this.captureJdbcParameters = captureJdbcParameters;
        return this;
    }

    public TraceLensProperties setCaptureJdbcQueries(boolean captureJdbcQueries) {
        this.captureJdbcQueries = captureJdbcQueries;
        return this;
    }

    public boolean isCaptureClickHouseQueries() { return captureClickHouseQueries; }
    public TraceLensProperties setCaptureClickHouseQueries(boolean value) {
        captureClickHouseQueries = value; return this;
    }
    public boolean isCaptureClickHouseParameters() { return captureClickHouseParameters; }
    public TraceLensProperties setCaptureClickHouseParameters(boolean value) {
        captureClickHouseParameters = value; return this;
    }

    public int getMaxSqlLength() { return maxSqlLength; }

    public TraceLensProperties setMaxSqlLength(int maxSqlLength) {
        this.maxSqlLength = maxSqlLength;
        return this;
    }

    public int getMaxSqlQueriesPerRequest() { return maxSqlQueriesPerRequest; }

    public TraceLensProperties setMaxSqlQueriesPerRequest(int maxSqlQueriesPerRequest) {
        this.maxSqlQueriesPerRequest = maxSqlQueriesPerRequest;
        return this;
    }

    public TraceLensProperties setRequireLabels(boolean requireLabels) {
        this.requireLabels = requireLabels;
        return this;
    }

    public List<String> getIgnoredPaths() {
        return ignoredPaths;
    }

    public TraceLensProperties setIgnoredPaths(List<String> ignoredPaths) {
        this.ignoredPaths = ignoredPaths;
        return this;
    }
}
