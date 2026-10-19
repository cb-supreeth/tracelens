package com.chargebee.tracelens.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Loads tracelens.json. Precedence (highest first), mirroring FlowLens:
 *   1. -D system property overrides (e.g. -Dtracelens.applicationId=...)
 *   2. the file at -Dtracelens.configPath
 * No implicit discovery: without configPath the loader fails fast — FlowLens's
 * "Package Module specification is mandatory" error exists because silent
 * working-directory-dependent discovery bit people.
 */
public final class TraceLensConfigLoader {

    public static final String CONFIG_PATH_PROPERTY = "tracelens.configPath";
    public static final String APPLICATION_ID_OVERRIDE = "tracelens.applicationId";
    public static final String APPLICATION_VERSION_OVERRIDE = "tracelens.applicationVersion";

    private final ObjectMapper mapper = new ObjectMapper();

    public TraceLensConfig load() {
        return load(false);
    }

    public TraceLensConfig load(boolean allowEmptyScope) {
        String path = System.getProperty(CONFIG_PATH_PROPERTY);
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalStateException(
                    "-" + CONFIG_PATH_PROPERTY + " is not set and no implicit discovery is done. "
                            + "Point it at a tracelens.json (absolute path).");
        }
        return load(Paths.get(path), allowEmptyScope);
    }

    public TraceLensConfig load(Path path) {
        return load(path, false);
    }

    public TraceLensConfig load(Path path, boolean allowEmptyScope) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("tracelens config not found: " + path.toAbsolutePath());
        }
        TraceLensConfig config;
        try {
            config = mapper.readValue(path.toFile(), TraceLensConfig.class);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot parse tracelens config " + path, e);
        }
        applyOverrides(config);
        List<String> errors = config.validate(allowEmptyScope);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("invalid tracelens config " + path + ": " + String.join("; ", errors));
        }
        return config;
    }

    private void applyOverrides(TraceLensConfig config) {
        String applicationId = System.getProperty(APPLICATION_ID_OVERRIDE);
        if (applicationId != null && !applicationId.trim().isEmpty()) {
            config.setApplicationId(applicationId);
        }
        String version = System.getProperty(APPLICATION_VERSION_OVERRIDE);
        if (version != null && !version.trim().isEmpty()) {
            config.setApplicationVersion(version);
        }
    }
}
