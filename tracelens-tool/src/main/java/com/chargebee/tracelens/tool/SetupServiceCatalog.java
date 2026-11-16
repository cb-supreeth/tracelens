package com.chargebee.tracelens.tool;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Supported guided-setup identities; adding a viewer registry entry does not add setup support. */
final class SetupServiceCatalog {
    static final class Entry {
        final String key, name, folder, checkout;
        final int debugPort;
        final boolean enabledByDefault;
        private Entry(String key, String name, String folder, String checkout, int debugPort, boolean enabledByDefault) {
            this.key = key; this.name = name; this.folder = folder; this.checkout = checkout;
            this.debugPort = debugPort; this.enabledByDefault = enabledByDefault;
        }
    }
    private static final List<Entry> SERVICES = Collections.unmodifiableList(Arrays.asList(
            new Entry("cbApp", "cb-app", "cb-app", "chargebee-app", 7778, true),
            new Entry("ems", "cb-ems", "ems", "cb-ems", 7779, false),
            new Entry("plans", "cb-plans", "plans", "cb-plans", 7786, false),
            new Entry("ledger", "cb-consumption-ledger", "ledger", "cb-consumption-ledger", 7783, false),
            new Entry("go", "cb-grant-orchestrator", "go", "cb-grant-orchestrator", 7784, false)));
    private static final Map<String, Entry> BY_KEY;
    private static final Map<String, String> FOLDERS;
    static {
        Map<String, Entry> entries = new LinkedHashMap<>(); Map<String, String> folders = new LinkedHashMap<>();
        for (Entry entry : SERVICES) {
            if (entries.put(entry.key, entry) != null) throw new IllegalStateException("Duplicate setup service key");
            folders.put(entry.key, entry.folder);
        }
        BY_KEY = Collections.unmodifiableMap(entries); FOLDERS = Collections.unmodifiableMap(folders);
    }
    private SetupServiceCatalog() {}
    static List<Entry> services() { return SERVICES; }
    static Set<String> keys() { return BY_KEY.keySet(); }
    static Map<String, String> folders() { return FOLDERS; }
    static Entry get(String key) {
        Entry result = BY_KEY.get(key);
        if (result == null) throw new IllegalArgumentException("Unsupported setup service: " + key);
        return result;
    }
}
