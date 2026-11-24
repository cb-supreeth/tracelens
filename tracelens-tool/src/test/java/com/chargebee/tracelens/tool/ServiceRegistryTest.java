package com.chargebee.tracelens.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** A shared registry must load on any workstation without editing absolute paths. */
class ServiceRegistryTest {
    @TempDir Path dir;

    private List<Service> load(String sourceRoot) throws Exception {
        Path file = dir.resolve("services.json");
        Files.write(file, ("{\"services\":[{\"name\":\"app\",\"url\":\"http://127.0.0.1:7778\",\"sourceRoot\":" + sourceRoot + "}]}")
                .getBytes(StandardCharsets.UTF_8));
        return Service.load(file);
    }

    @Test void leadingTildeSlashResolvesUnderTheUsersHome() throws Exception {
        Path home = Paths.get(System.getProperty("user.home"));
        assertEquals(home.resolve("work/chargebee-app").toString(), load("\"~/work/chargebee-app\"").get(0).sourceRoot);
    }

    @Test void absolutePathsAndMissingRootsAreUnchanged() throws Exception {
        assertEquals("/srv/checkout", load("\"/srv/checkout\"").get(0).sourceRoot);
        assertNull(load("null").get(0).sourceRoot);
    }

    @Test void onlyALeadingTildeSlashIsExpanded() {
        for (String root : new String[]{"\"~\"", "\"~other/work\"", "\"work/~/app\"", "\"$HOME/work\"", "\"relative/path\""})
            assertThrows(IllegalArgumentException.class, () -> load(root), root);
    }

    @Test void aMissingCheckoutIsReportedPerServiceWithoutFailing() throws Exception {
        Path present = Files.createDirectory(dir.resolve("present")), file = dir.resolve("two.json");
        Files.write(file, ("{\"services\":[{\"name\":\"here\",\"url\":\"http://127.0.0.1:7778\",\"sourceRoot\":\"" + present + "\"},"
                + "{\"name\":\"gone\",\"url\":\"http://127.0.0.1:7779\",\"sourceRoot\":\"" + dir.resolve("absent") + "\"},"
                + "{\"name\":\"none\",\"url\":\"http://127.0.0.1:7780\"}]}").getBytes(StandardCharsets.UTF_8));
        ToolConfig config = ToolConfig.fromArgs(new String[]{"--services", file.toString(), "serve"});
        List<String> notes = ToolMain.missingSourceRoots(config);
        assertEquals(1, notes.size());
        assertTrue(notes.get(0).startsWith("Source note: gone source root " + dir.resolve("absent")), notes.get(0));
    }

    @Test void theCheckedInRegistryLoadsOnAnyWorkstation() {
        Path file = Paths.get("../scripts/services-local.json");
        if (!Files.exists(file)) file = Paths.get("scripts/services-local.json");
        List<Service> services = Service.load(file);
        Path home = Paths.get(System.getProperty("user.home"));
        assertEquals(2, services.size());
        for (Service service : services) assertTrue(Paths.get(service.sourceRoot).startsWith(home), service.name);
    }
}
