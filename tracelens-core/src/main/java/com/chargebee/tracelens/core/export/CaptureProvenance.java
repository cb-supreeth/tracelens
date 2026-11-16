package com.chargebee.tracelens.core.export;

import com.chargebee.tracelens.bridge.AgentStatus;
import com.chargebee.tracelens.core.config.TraceLensConfig;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;

/** Fingerprints configuration, never application inputs or captured values. */
public final class CaptureProvenance {
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private CaptureProvenance() { }

    public static String fingerprint(TraceLensConfig config) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(config));
            StringBuilder value = new StringBuilder(64);
            for (byte item : digest) value.append(Character.forDigit((item & 255) >>> 4, 16)).append(Character.forDigit(item & 15, 16));
            return value.toString();
        } catch (Exception unavailable) { return "unreported"; }
    }

    static ObjectNode manifest(TraceLensConfig config, String fingerprint) {
        ObjectNode result = JSON.createObjectNode();
        result.put("schemaVersion", 1);
        result.put("applicationVersion", config.getApplicationVersion());
        result.put("agentBuildId", AgentStatus.buildId());
        result.put("configurationFingerprint", fingerprint);
        return result;
    }
}
