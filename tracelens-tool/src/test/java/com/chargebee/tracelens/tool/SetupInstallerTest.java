package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SetupInstallerTest {
    @TempDir Path temporary;
    private Path root, jar, cbApp, ems, install, setenv;
    private SetupInstaller installer;

    @BeforeEach void fixture() throws Exception {
        root = temporary.toRealPath();
        jar = root.resolve("bundle.jar");
        writeJar(jar, true);
        cbApp = Files.createDirectories(root.resolve("chargebee-app/tomcat/bin")).getParent().getParent();
        write(cbApp.resolve("tomcat/bin/catalina.sh"), "#!/bin/sh\nexit 99\n");
        Files.setPosixFilePermissions(cbApp.resolve("tomcat/bin/catalina.sh"), PosixFilePermissions.fromString("rwx------"));
        setenv = cbApp.resolve("tomcat/bin/setenv.sh");
        write(setenv, "#!/bin/sh\n# user's startup settings\nCATALINA_OPTS=\"${CATALINA_OPTS:-} -Xmx2g\"\n");
        ems = Files.createDirectory(root.resolve("cb-ems"));
        install = root.resolve("private-install");
        installer = new SetupInstaller(jar);
    }

    private ObjectNode request() {
        ObjectNode request = installer.defaults();
        for (String key : Arrays.asList("cbApp", "ems", "plans", "ledger", "go")) ((ObjectNode) request.get(key)).remove("capture");
        request.put("installDir", install.toString());
        ((ObjectNode) request.get("cbApp")).put("sourceRoot", cbApp.toString()).set("httpIncludePaths", Json.arr("/api/v2/features"));
        ((ObjectNode) request.get("ems")).put("sourceRoot", ems.toString()).set("httpIncludePaths", Json.arr("/features"));
        return request;
    }

    @Test void emsDefaultsIncludeCustomPricingUnitCallbacksWithoutBroadeningScope() {
        JsonNode paths = installer.defaults().path("ems").path("httpIncludePaths");
        assertTrue(paths.isArray());
        assertTrue(paths.toString().contains("\"/custom_pricing_units\""));
        assertFalse(paths.toString().contains("\"/\""));
    }

    @Test void captureModeIsReviewedAndWrittenOnlyForNewServices() throws Exception {
        ObjectNode request = request();
        request.put("captureMode", "overview");
        ObjectNode plan = installer.plan(request);
        assertEquals("overview", plan.path("request").path("captureMode").asText());
        installer.apply(request, plan.path("planId").asText());
        JsonNode config = Json.parse(Files.readAllBytes(install.resolve("cb-app/tracelens.json")));
        assertEquals("overview", config.path("captureMode").asText());
    }

    @Test void rejectsUnknownCaptureModeBeforeWritingAnything() {
        ObjectNode request = request().put("captureMode", "unlimited");
        assertThrows(IllegalArgumentException.class, () -> installer.plan(request));
        assertFalse(Files.exists(install));
    }

    @Test void planIsReadOnlyDeterministicAndSqlIsOffByDefault() throws Exception {
        byte[] original = Files.readAllBytes(setenv), source = Files.readAllBytes(jar);
        ObjectNode request = request(), plan = installer.plan(request);
        assertEquals(plan, installer.plan(request));
        assertTrue(plan.path("existingInstallation").isBoolean());
        assertFalse(plan.path("existingInstallation").asBoolean());
        assertFalse(Files.exists(install));
        assertArrayEquals(original, Files.readAllBytes(setenv));
        assertArrayEquals(source, Files.readAllBytes(jar));
        assertFalse(plan.path("request").path("captureJdbcQueries").asBoolean());
        assertFalse(plan.path("request").path("captureJdbcParameters").asBoolean());
        assertEquals(install.resolve("cb-app/tracelens.json").toString(), plan.path("viewerConfig").asText());
        assertFalse(Json.stringify(plan).contains("-Xmx2g"));
        assertFalse(Json.stringify(plan).contains("viewer.json"));
        assertEquals(64, plan.path("planId").asText().length());
    }

    @Test void applyCreatesPrivateFilesBacksUpExactSetenvAndDoesNotStartAnything() throws Exception {
        byte[] before = Files.readAllBytes(setenv);
        ObjectNode request = request(), plan = installer.plan(request);
        ObjectNode result = installer.apply(request, plan.path("planId").asText());
        assertTrue(result.path("installed").asBoolean());
        assertArrayEquals(before, Files.readAllBytes(install.resolve("cb-app/setenv.sh.before-tracelens")));
        assertArrayEquals(before, Arrays.copyOf(Files.readAllBytes(setenv), before.length));
        String updated = read(setenv);
        assertTrue(updated.contains("${TRACELENS:-0}"));
        assertTrue(updated.contains("CATALINA_OPTS=\"${CATALINA_OPTS:-} -javaagent:"));
        assertFalse(updated.contains("JAVA_OPTS=\""));
        assertArrayEquals(Files.readAllBytes(jar), Files.readAllBytes(install.resolve("tracelens.jar")));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(install));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(install.resolve("tracelens.jar")));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(install.resolve("cb-app/setenv.sh.before-tracelens")));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(install.resolve("start-viewer.sh")));
        JsonNode config = Json.parse(Files.readAllBytes(install.resolve("cb-app/tracelens.json")));
        assertFalse(config.path("captureJdbcQueries").asBoolean());
        assertFalse(config.path("captureJdbcParameters").asBoolean());
        assertFalse(config.path("capturePayloads").asBoolean());
        assertEquals(Json.arr("/api/v2/features"), config.path("httpIncludePaths"));
        assertEquals(cbApp.toString(), config.path("viewer").path("services").get(0).path("sourceRoot").asText());
        assertTrue(read(install.resolve("start-cb-app.sh")).contains("version \"1.8."));
        assertTrue(read(install.resolve("start-viewer.sh")).contains("cb-app/tracelens.json' serve"));
        ToolConfig configured = ToolConfig.fromArgs(new String[]{"--config", result.path("viewerConfig").asText()});
        assertEquals(1, configured.services.size());
        assertThrows(IllegalArgumentException.class, () -> installer.apply(request, plan.path("planId").asText()));
    }

    @Test void rejectsStaleRequestJarAndSetenvWithoutWritingInstall() throws Exception {
        ObjectNode request = request();
        String old = installer.plan(request).path("planId").asText();
        request.put("viewerPort", 8899);
        assertThrows(IllegalArgumentException.class, () -> installer.apply(request, old));
        assertFalse(Files.exists(install));
        request.put("viewerPort", 7781);
        write(setenv, read(setenv) + "# edited after preview\n");
        assertThrows(IllegalArgumentException.class, () -> installer.apply(request, old));
        assertFalse(Files.exists(install));
        String second = installer.plan(request).path("planId").asText();
        writeJar(jar, true, "changed");
        assertThrows(IllegalArgumentException.class, () -> installer.apply(request, second));
        assertFalse(Files.exists(install));
    }

    @Test void rejectsExistingOutputAndExistingHooksRatherThanOverwriting() throws Exception {
        ObjectNode request = request();
        Files.createDirectory(install);
        write(install.resolve("keep.txt"), "keep me");
        assertThrows(IllegalArgumentException.class, () -> installer.plan(request));
        assertEquals("keep me", read(install.resolve("keep.txt")));
        request.put("installDir", root.resolve("other-private-install").toString());
        for (String hook : Arrays.asList(". /old/tracelens.sh\n", "CATALINA_OPTS='-javaagent:/old/TraceLens.jar'\n")) {
            write(setenv, hook);
            assertThrows(IllegalArgumentException.class, () -> installer.plan(request));
            assertEquals(hook, read(setenv));
        }
    }

    @Test void rejectsInjectionBroadRootsBadPortsAndMalformedApiPrefixes() {
        for (String suffix : Arrays.asList("/bad space", "/bad'quote", "/bad\nline", "/bad;touch", "/bad$HOME", "/bad`cmd`", "/bad=config")) {
            ObjectNode request = request(); request.put("installDir", root + suffix);
            assertThrows(IllegalArgumentException.class, () -> installer.plan(request), suffix);
        }
        for (String broad : Arrays.asList("/", System.getProperty("user.home"), "/tmp", "/Users")) {
            ObjectNode request = request(); request.put("installDir", broad);
            assertThrows(IllegalArgumentException.class, () -> installer.plan(request), broad);
        }
        for (int port : new int[]{0, -1, 80, 65536, 7778}) {
            ObjectNode request = request(); request.put("viewerPort", port);
            assertThrows(IllegalArgumentException.class, () -> installer.plan(request));
        }
        for (JsonNode invalid : Arrays.asList(Json.arr(""), Json.arr("/"), Json.arr("https://example.com/api"),
                Json.arr("/api?key=secret"), Json.arr("/api#secret"), Json.arr("/api;touch"), Json.arr("/api", "/api"), Json.arr("/../secret"))) {
            ObjectNode request = request(); ((ObjectNode) request.get("cbApp")).set("httpIncludePaths", invalid);
            assertThrows(IllegalArgumentException.class, () -> installer.plan(request), Json.stringify(invalid));
        }
        ObjectNode request = request(); request.put("captureJdbcParameters", true);
        assertThrows(IllegalArgumentException.class, () -> installer.plan(request));
        ObjectNode manyRequest = request(); com.fasterxml.jackson.databind.node.ArrayNode many = Json.arr();
        for (int index = 0; index < 65; index++) many.add("/api/" + index);
        ((ObjectNode) manyRequest.get("cbApp")).set("httpIncludePaths", many);
        assertThrows(IllegalArgumentException.class, () -> installer.plan(manyRequest));
    }

    @Test void rejectsSymlinkedOutputParentsSourceJarAndSetenv() throws Exception {
        Path link = root.resolve("link"); Files.createSymbolicLink(link, ems);
        ObjectNode request = request(); request.put("installDir", link.resolve("install").toString());
        assertThrows(IllegalArgumentException.class, () -> installer.plan(request));
        Path jarLink = root.resolve("jar-link.jar"); Files.createSymbolicLink(jarLink, jar);
        assertThrows(IllegalArgumentException.class, () -> new SetupInstaller(jarLink).plan(request()));
        Path actual = root.resolve("actual-setenv.sh"); Files.move(setenv, actual); Files.createSymbolicLink(setenv, actual);
        assertThrows(IllegalArgumentException.class, () -> installer.plan(request()));
        assertFalse(Files.exists(install));
    }

    @Test void requiresCombinedJarAndExecutableCbAppCatalina() throws Exception {
        writeJar(jar, false);
        assertThrows(IllegalArgumentException.class, () -> installer.plan(request()));
        writeJar(jar, true);
        Files.setPosixFilePermissions(cbApp.resolve("tomcat/bin/catalina.sh"), PosixFilePermissions.fromString("rw-------"));
        assertThrows(IllegalArgumentException.class, () -> installer.plan(request()));
    }

    @Test void multiserviceRegistryUsesOneJarAndNeverInventsEmsLaunchConfiguration() throws Exception {
        ObjectNode request = request(); ((ObjectNode) request.get("ems")).put("enabled", true);
        request.put("captureJdbcQueries", true).put("captureJdbcParameters", true);
        ObjectNode plan = installer.plan(request);
        installer.apply(request, plan.path("planId").asText());
        JsonNode cb = Json.parse(Files.readAllBytes(install.resolve("cb-app/tracelens.json")));
        JsonNode emsConfig = Json.parse(Files.readAllBytes(install.resolve("ems/tracelens.json")));
        assertEquals(cb.path("viewer"), emsConfig.path("viewer"));
        assertEquals(2, cb.path("viewer").path("services").size());
        assertTrue(cb.path("captureJdbcQueries").asBoolean()); assertTrue(emsConfig.path("captureJdbcParameters").asBoolean());
        assertTrue(Files.exists(install.resolve("ems/vm-options.txt"))); assertFalse(Files.exists(install.resolve("start-cb-ems.sh")));
        assertFalse(Files.exists(install.resolve("ems/tracelens.jar")));
        assertTrue(read(install.resolve("ems/vm-options.txt")).contains("-Dtracelens.port=7779"));
        assertFalse(Json.stringify(plan).contains("RDS_PASSWORD"));
        assertEquals(2, ToolConfig.fromArgs(new String[]{"--config", plan.path("viewerConfig").asText()}).services.size());
    }

    @Test void emsOnlyCanGenerateLauncherOnlyWithExplicitJarAndJavaHome() throws Exception {
        ObjectNode request = request(); ((ObjectNode) request.get("cbApp")).put("enabled", false);
        ObjectNode service = (ObjectNode) request.get("ems"); service.put("enabled", true);
        Path launch = root.resolve("ems.jar"); writeJar(launch, false);
        service.put("launchJar", launch.toString());
        assertThrows(IllegalArgumentException.class, () -> installer.plan(request));
        Path javaHome = Files.createDirectories(root.resolve("jdk21/bin")).getParent();
        write(javaHome.resolve("bin/java"), "#!/bin/sh\nexit 99\n");
        Files.setPosixFilePermissions(javaHome.resolve("bin/java"), PosixFilePermissions.fromString("rwx------"));
        service.put("javaHome", javaHome.toString());
        String previous = read(setenv);
        ObjectNode plan = installer.plan(request); installer.apply(request, plan.path("planId").asText());
        assertEquals(install.resolve("ems/tracelens.json").toString(), plan.path("viewerConfig").asText());
        assertTrue(Files.exists(install.resolve("start-cb-ems.sh")));
        String script = read(install.resolve("start-cb-ems.sh"));
        assertTrue(script.contains(javaHome + "/bin/java")); assertTrue(script.contains(launch.toString()));
        assertTrue(script.contains("version \"21.")); assertFalse(script.contains("RDS_"));
        assertEquals(previous, read(setenv)); assertFalse(Files.exists(install.resolve("start-cb-app.sh")));
    }

    @Test void largeEmsExecutableJarIsReferencedWithoutCopyingAndCombinedJarLimitIsUnchanged() throws Exception {
        Path launch = root.resolve("large-ems.jar");
        writeJar(launch, false);
        sparsePrefix(launch, 180_121_354L);
        try (JarFile archive = new JarFile(launch.toFile())) {
            assertEquals("com.example.EmsMain", archive.getManifest().getMainAttributes().getValue("Main-Class"));
        }
        long size = Files.size(launch), modified = Files.getLastModifiedTime(launch).toMillis();
        ObjectNode request = emsLauncherRequest(launch);
        ObjectNode plan = installer.plan(request);
        assertFalse(Files.exists(install));
        installer.apply(request, plan.path("planId").asText());
        assertTrue(read(install.resolve("start-cb-ems.sh")).contains(" -jar '" + launch + "'"));
        assertEquals(size, Files.size(launch));
        assertEquals(modified, Files.getLastModifiedTime(launch).toMillis());
        try (Stream<Path> outputs = Files.walk(install)) {
            assertEquals(1, outputs.filter(path -> path.toString().endsWith(".jar")).count());
        }
        assertArrayEquals(Files.readAllBytes(jar), Files.readAllBytes(install.resolve("tracelens.jar")));

        Path oversizedCombined = root.resolve("oversized-combined.jar");
        writeJar(oversizedCombined, true);
        sparsePrefix(oversizedCombined, 128L * 1024 * 1024 + 1);
        request.put("installDir", root.resolve("another-install").toString());
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new SetupInstaller(oversizedCombined).plan(request));
        assertTrue(failure.getMessage().contains("Combined JAR"));
        assertFalse(Files.exists(root.resolve("another-install")));
    }

    @Test void rejectsEmsExecutableJarOverOneGiBWithoutWritingInstall() throws Exception {
        Path launch = root.resolve("oversized-ems.jar");
        writeJar(launch, false);
        sparsePrefix(launch, 1024L * 1024 * 1024 + 1);
        ObjectNode request = emsLauncherRequest(launch);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> installer.plan(request));
        assertTrue(failure.getMessage().contains("EMS launchJar"));
        assertFalse(Files.exists(install));
    }

    @Test void emsLauncherStillRejectsUnsafeOrInvalidJarAndJavaHome() throws Exception {
        Path launch = root.resolve("ems.jar"); writeJar(launch, false);
        ObjectNode request = emsLauncherRequest(launch);
        ObjectNode service = (ObjectNode) request.get("ems");
        Path link = root.resolve("ems-link.jar"); Files.createSymbolicLink(link, launch);
        service.put("launchJar", link.toString());
        assertThrows(IllegalArgumentException.class, () -> installer.plan(request));
        service.put("launchJar", ems.toString());
        assertThrows(IllegalArgumentException.class, () -> installer.plan(request));
        service.put("launchJar", launch.toString());
        Files.setPosixFilePermissions(launch, PosixFilePermissions.fromString("---------"));
        try {
            // Privileged test runners can still read a mode-000 fixture.
            if (!Files.isReadable(launch)) assertThrows(IllegalArgumentException.class, () -> installer.plan(request));
        }
        finally { Files.setPosixFilePermissions(launch, PosixFilePermissions.fromString("rw-------")); }
        try (JarOutputStream archive = new JarOutputStream(Files.newOutputStream(launch), new Manifest())) { }
        assertThrows(IllegalArgumentException.class, () -> installer.plan(request));
        writeJar(launch, false);
        Path executable = java.nio.file.Paths.get(service.path("javaHome").asText(), "bin/java");
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rw-------"));
        assertThrows(IllegalArgumentException.class, () -> installer.plan(request));
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        Path actualJava = executable.resolveSibling("actual-java"); Files.move(executable, actualJava); Files.createSymbolicLink(executable, actualJava);
        assertThrows(IllegalArgumentException.class, () -> installer.plan(request));
        assertFalse(Files.exists(install));
    }

    @Test void missingSetenvIsCreatedAndExistingEmptyDestinationBecomesPrivate() throws Exception {
        Files.delete(setenv);
        Files.createDirectory(install); Files.setPosixFilePermissions(install, PosixFilePermissions.fromString("rwxr-xr-x"));
        ObjectNode request = request(), plan = installer.plan(request);
        installer.apply(request, plan.path("planId").asText());
        assertTrue(read(setenv).contains("TRACELENS"));
        assertFalse(Files.exists(install.resolve("cb-app/setenv.sh.before-tracelens")));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(install));
    }

    @Test void sourceCheckoutSpacesAndQuotesAreEscapedAndGeneratedShellParses() throws Exception {
        Path spaced = root.resolve("cb-app user's \"checkout\" $(no-such-command)");
        Files.move(cbApp, spaced); cbApp = spaced; setenv = cbApp.resolve("tomcat/bin/setenv.sh");
        ObjectNode request = request(), plan = installer.plan(request);
        installer.apply(request, plan.path("planId").asText());
        assertTrue(read(install.resolve("start-cb-app.sh")).contains("user'\"'\"'s \"checkout\" $(no-such-command)/tomcat/bin'"));
        for (Path script : Arrays.asList(install.resolve("start-cb-app.sh"), install.resolve("start-viewer.sh"), install.resolve("check-setup.sh"), setenv)) {
            Process syntax = new ProcessBuilder("/bin/sh", "-n", script.toString()).redirectErrorStream(true).start();
            assertEquals(0, syntax.waitFor(), script.toString());
        }
    }

    @Test void cbAppLauncherUsesTomcatBinJava8AndOptInWithoutChangingAnotherInstance() throws Exception {
        write(cbApp.resolve("tomcat/bin/catalina.sh"), "#!/bin/sh\n. \"$CATALINA_BASE/bin/setenv.sh\"\nprintf '%s\\n' \"$PWD\" \"$TRACELENS\" \"$CATALINA_OPTS\" \"$JRE_HOME\" \"$SETUP_TEST_USER_ENV\"\n");
        Path javaHome = Files.createDirectories(root.resolve("fixture-java8/bin")).getParent();
        write(javaHome.resolve("bin/java"), "#!/bin/sh\necho 'java version \"1.8.0_fixture\"' >&2\n");
        Files.setPosixFilePermissions(javaHome.resolve("bin/java"), PosixFilePermissions.fromString("rwx------"));
        ObjectNode request = request(), plan = installer.plan(request);
        installer.apply(request, plan.path("planId").asText());
        ProcessBuilder launch = new ProcessBuilder("/bin/sh", install.resolve("start-cb-app.sh").toString()).redirectErrorStream(true);
        launch.environment().put("JAVA_HOME", javaHome.toString());
        launch.environment().put("JRE_HOME", "/incorrect-jre");
        launch.environment().put("CATALINA_OPTS", "-Xms256m");
        launch.environment().put("SETUP_TEST_USER_ENV", "user-owned-env");
        for (String key : Arrays.asList("CATALINA_HOME", "CATALINA_BASE", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_OPTS")) launch.environment().remove(key);
        Process process = launch.start(); String output = output(process);
        assertEquals(0, process.waitFor(), output);
        assertTrue(output.startsWith(cbApp.resolve("tomcat/bin") + "\n1\n"));
        assertTrue(output.contains("-Xms256m -Xmx2g -javaagent:"));
        assertTrue(output.contains(javaHome + "\nuser-owned-env\n"));
        launch.environment().put("CATALINA_BASE", root.resolve("different-instance").toString());
        process = launch.start(); output = output(process);
        assertEquals(1, process.waitFor(), output); assertTrue(output.contains("another Tomcat"));
    }

    @Test void managedHookIsNoOpUnlessExplicitlyEnabledAndRefusesDuplicateAgent() throws Exception {
        ObjectNode request = request(), plan = installer.plan(request); installer.apply(request, plan.path("planId").asText());
        ProcessBuilder shell = new ProcessBuilder("/bin/sh", "-c", ". \"$1\"; printf '%s' \"$CATALINA_OPTS\"", "fixture", setenv.toString()).redirectErrorStream(true);
        shell.environment().put("CATALINA_OPTS", "-Xms256m"); shell.environment().put("TRACELENS", "0");
        Process process = shell.start(); String output = output(process);
        assertEquals(0, process.waitFor(), output); assertEquals("-Xms256m -Xmx2g", output);
        shell.environment().put("TRACELENS", "1");
        for (String key : Arrays.asList("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_OPTS")) shell.environment().remove(key);
        process = shell.start(); output = output(process);
        assertEquals(0, process.waitFor(), output); assertTrue(output.contains("-javaagent:"));
        shell.command("/bin/sh", "-c", ". \"$1\"", "fixture", setenv.toString());
        shell.environment().put("JAVA_OPTS", "-javaagent:/old/tracelens.jar");
        process = shell.start(); output = output(process);
        assertEquals(1, process.waitFor(), output); assertTrue(output.contains("already configured"));
    }

    @Test void independentServicesContinueWhenOneSelectedCheckoutIsInvalid() throws Exception {
        ObjectNode request = request();
        ((ObjectNode) request.get("cbApp")).put("sourceRoot", root.resolve("missing-app").toString());
        for (String key : Arrays.asList("ems", "plans", "ledger", "go")) {
            ((ObjectNode) request.get(key)).put("enabled", true).put("sourceRoot", Files.createDirectories(root.resolve("fixture-" + key)).toString());
        }
        ObjectNode plan = installer.plan(request);
        assertEquals("failed", serviceOutcome(plan, "cbApp").path("status").asText());
        assertEquals(5, plan.path("services").size());
        assertEquals(install.resolve("ems/tracelens.json").toString(), plan.path("viewerConfig").asText());
        ObjectNode result = installer.apply(request, plan.path("planId").asText());
        assertTrue(result.path("installed").asBoolean()); assertTrue(result.path("partial").asBoolean());
        assertEquals("installed", serviceOutcome(result, "go").path("status").asText());
        assertFalse(Files.exists(install.resolve("cb-app")));
        assertFalse(Files.exists(install.resolve("start-cb-app.sh")));
        ToolConfig config = ToolConfig.fromArgs(new String[]{"--config", result.path("viewerConfig").asText()});
        assertEquals(4, config.services.size());
        for (String key : Arrays.asList("ems", "plans", "ledger", "go")) assertTrue(Files.exists(install.resolve(key + "/vm-options.txt")));
        assertTrue(read(install.resolve("plans/tracelens.json")).contains("com.chargebee.items"));
        assertFalse(Json.stringify(result).contains("cb-ui-middleware"));
        assertTrue(Json.stringify(result.path("warnings")).contains("httpPropagationAllowedDestinations"));
        assertEquals(Json.arr(), Json.parse(Files.readAllBytes(install.resolve("plans/tracelens.json"))).path("httpPropagationAllowedDestinations"));
    }

    @Test void incrementalAddPreservesEditedSettingsLaunchersJarCapturesAndNamespace() throws Exception {
        ObjectNode initial = request(); ObjectNode first = installer.apply(initial, installer.plan(initial).path("planId").asText());
        Path primary = Paths.get(first.path("viewerConfig").asText());
        ObjectNode edited = (ObjectNode) Json.parse(Files.readAllBytes(primary));
        edited.put("trackMethods", false).set("httpIncludePaths", Json.arr("/my/custom/api"));
        ((ObjectNode) edited.path("viewer")).put("port", 8890).put("viewerUrl", "http://127.0.0.1:8890");
        write(primary, Json.stringify(edited));
        byte[] primaryBefore = Files.readAllBytes(primary), jarBefore = Files.readAllBytes(install.resolve("tracelens.jar"));
        String viewerBefore = read(install.resolve("start-viewer.sh")), cbBefore = read(install.resolve("start-cb-app.sh")), hookBefore = read(setenv);
        String namespace = ViewerServer.viewerContext(ToolConfig.fromArgs(new String[]{"--config", primary.toString()})).path("namespace").asText();
        Path saved = Files.createDirectories(install.resolve("viewer-snapshots").resolve(namespace)).resolve("keep.json");
        write(saved, "saved historical evidence");
        ObjectNode add = request(); ((ObjectNode) add.get("cbApp")).put("enabled", false);
        ((ObjectNode) add.get("ems")).put("enabled", true);
        add.put("viewerPort", 8999).put("captureJdbcQueries", true);
        ObjectNode plan = installer.plan(add), result = installer.apply(add, plan.path("planId").asText());
        assertTrue(plan.path("existingInstallation").asBoolean());
        assertEquals("add", serviceOutcome(plan, "ems").path("operation").asText());
        ObjectNode after = (ObjectNode) Json.parse(Files.readAllBytes(primary));
        assertFalse(after.path("trackMethods").asBoolean()); assertEquals(Json.arr("/my/custom/api"), after.path("httpIncludePaths"));
        assertEquals(8890, after.path("viewer").path("port").asInt());
        assertEquals(2, after.path("viewer").path("services").size());
        assertEquals(namespace, ViewerServer.viewerContext(ToolConfig.fromArgs(new String[]{"--config", primary.toString()})).path("namespace").asText());
        assertEquals("saved historical evidence", read(saved));
        assertArrayEquals(jarBefore, Files.readAllBytes(install.resolve("tracelens.jar")));
        assertEquals(viewerBefore, read(install.resolve("start-viewer.sh")));
        assertEquals(cbBefore, read(install.resolve("start-cb-app.sh"))); assertEquals(hookBefore, read(setenv));
        assertArrayEquals(primaryBefore, Files.readAllBytes(Paths.get(result.path("backupDir").asText()).resolve("viewer-config.before.json")));
        assertEquals(primary.toString(), result.path("viewerConfig").asText());
        ObjectNode repeat = installer.plan(add);
        assertEquals("existing", serviceOutcome(repeat, "ems").path("status").asText());
        assertEquals(0, repeat.path("files").size());
        assertThrows(IllegalArgumentException.class, () -> installer.apply(add, repeat.path("planId").asText()));
    }

    @Test void recognizesLegacyGuidedInstallWithoutDeletingOrRewritingItsFiles() throws Exception {
        ObjectNode initial = request(); installer.apply(initial, installer.plan(initial).path("planId").asText());
        Files.delete(install.resolve("setup-state.json")); // Simulate the pre-manifest wizard layout.
        ObjectNode add = request(); ((ObjectNode) add.get("cbApp")).put("enabled", false);
        ((ObjectNode) add.get("plans")).put("enabled", true).put("sourceRoot", Files.createDirectory(root.resolve("plans-checkout")).toString());
        String hook = read(setenv), start = read(install.resolve("start-viewer.sh"));
        ObjectNode plan = installer.plan(add); installer.apply(add, plan.path("planId").asText());
        assertEquals(hook, read(setenv)); assertEquals(start, read(install.resolve("start-viewer.sh")));
        assertTrue(Files.exists(install.resolve("setup-state.json")));
        assertEquals(2, Json.parse(Files.readAllBytes(install.resolve("cb-app/tracelens.json"))).path("viewer").path("services").size());
    }

    @Test void newerBundleUsesVersionedJarForViewerAndNewServicesWithoutChangingExistingAgents() throws Exception {
        ObjectNode initial = request(); installer.apply(initial, installer.plan(initial).path("planId").asText());
        Files.delete(install.resolve("setup-state.json")); // Recognize the earlier guided setup layout too.
        Path primary = install.resolve("cb-app/tracelens.json");
        byte[] agentBefore = Files.readAllBytes(install.resolve("tracelens.jar"));
        String hookBefore = read(setenv), appBefore = read(install.resolve("start-cb-app.sh"));
        String startBefore = read(install.resolve("start-viewer.sh")), checkBefore = read(install.resolve("check-setup.sh"));
        String namespace = ViewerServer.viewerContext(ToolConfig.fromArgs(new String[]{"--config", primary.toString()})).path("namespace").asText();
        writeJar(jar, true, "new setup and viewer");
        ObjectNode add = request(); ((ObjectNode) add.get("cbApp")).put("enabled", false); ((ObjectNode) add.get("ems")).put("enabled", true);
        ObjectNode plan = installer.plan(add), result = installer.apply(add, plan.path("planId").asText());
        Path upgraded;
        try (Stream<Path> files = Files.list(install.resolve("viewer"))) { upgraded = files.findFirst().get(); }
        assertTrue(upgraded.getFileName().toString().matches("tracelens-[0-9a-f]{64}\\.jar"));
        assertArrayEquals(Files.readAllBytes(jar), Files.readAllBytes(upgraded));
        assertArrayEquals(agentBefore, Files.readAllBytes(install.resolve("tracelens.jar")));
        assertEquals(hookBefore, read(setenv)); assertEquals(appBefore, read(install.resolve("start-cb-app.sh")));
        assertTrue(read(install.resolve("start-viewer.sh")).contains("-jar '" + upgraded + "'"));
        assertTrue(read(install.resolve("check-setup.sh")).contains("-jar '" + upgraded + "'"));
        assertTrue(read(install.resolve("ems/vm-options.txt")).contains("-javaagent:" + upgraded + "=standalone"));
        assertTrue(Json.stringify(result.path("files")).contains(upgraded.toString()));
        Path backup = Paths.get(result.path("backupDir").asText());
        assertEquals(startBefore, read(backup.resolve("start-viewer.sh.before")));
        assertEquals(checkBefore, read(backup.resolve("check-setup.sh.before")));
        assertEquals(namespace, ViewerServer.viewerContext(ToolConfig.fromArgs(new String[]{"--config", primary.toString()})).path("namespace").asText());

        // Adding another service with the same bundle reuses the versioned artifact.
        ObjectNode again = request(); ((ObjectNode) again.get("cbApp")).put("enabled", false);
        ((ObjectNode) again.get("go")).put("enabled", true).put("sourceRoot", Files.createDirectory(root.resolve("go-later")).toString());
        ObjectNode next = installer.plan(again); installer.apply(again, next.path("planId").asText());
        assertTrue(read(install.resolve("go/vm-options.txt")).contains("-javaagent:" + upgraded + "=standalone"));
        try (Stream<Path> files = Files.list(install.resolve("viewer"))) { assertEquals(1, files.count()); }
        assertEquals(3, Json.parse(Files.readAllBytes(primary)).path("viewer").path("services").size());
    }

    @Test void newerBundleRefusesToReplaceCustomViewerLaunchers() throws Exception {
        ObjectNode initial = request(); installer.apply(initial, installer.plan(initial).path("planId").asText());
        Path start = install.resolve("start-viewer.sh"); write(start, read(start) + "# user's custom launcher\n");
        String custom = read(start), primaryBefore = read(install.resolve("cb-app/tracelens.json"));
        writeJar(jar, true, "new combined bundle");
        ObjectNode add = request(); ((ObjectNode) add.get("cbApp")).put("enabled", false); ((ObjectNode) add.get("ems")).put("enabled", true);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> installer.plan(add));
        assertTrue(error.getMessage().contains("customized"));
        assertEquals(custom, read(start)); assertEquals(primaryBefore, read(install.resolve("cb-app/tracelens.json")));
        assertFalse(Files.exists(install.resolve("ems"))); assertFalse(Files.exists(install.resolve("viewer")));
    }

    @Test void existingSelectedServicesIgnoreNewInputsAndDisabledFieldsNeverBlockOtherServices() throws Exception {
        ObjectNode initial = request(); installer.apply(initial, installer.plan(initial).path("planId").asText());
        ObjectNode add = request();
        ((ObjectNode) add.get("cbApp")).put("sourceRoot", "invalid path").put("debugPort", "bad");
        ((ObjectNode) add.get("ems")).put("sourceRoot", "invalid path").put("debugPort", "bad").set("httpIncludePaths", Json.arr());
        ((ObjectNode) add.get("ledger")).put("enabled", true).put("sourceRoot", Files.createDirectory(root.resolve("ledger-checkout")).toString());
        ObjectNode plan = installer.plan(add);
        assertEquals("existing", serviceOutcome(plan, "cbApp").path("status").asText());
        assertEquals("ready", serviceOutcome(plan, "ledger").path("status").asText());
        installer.apply(add, plan.path("planId").asText());
        assertTrue(Files.exists(install.resolve("ledger/vm-options.txt")));
    }

    @Test void conflictingOutputAndPortFailOnlyTheirServiceAndKeepExistingFiles() throws Exception {
        ObjectNode initial = request(); installer.apply(initial, installer.plan(initial).path("planId").asText());
        Path keep = Files.createDirectories(install.resolve("ems")).resolve("keep.txt"); write(keep, "user-owned");
        ObjectNode add = request(); ((ObjectNode) add.get("cbApp")).put("enabled", false); ((ObjectNode) add.get("ems")).put("enabled", true);
        ((ObjectNode) add.get("plans")).put("enabled", true).put("sourceRoot", Files.createDirectory(root.resolve("plans-root")).toString()).put("debugPort", 7778);
        ((ObjectNode) add.get("go")).put("enabled", true).put("sourceRoot", Files.createDirectory(root.resolve("go-root")).toString());
        ObjectNode plan = installer.plan(add);
        assertEquals("failed", serviceOutcome(plan, "ems").path("status").asText());
        assertEquals("failed", serviceOutcome(plan, "plans").path("status").asText());
        ObjectNode result = installer.apply(add, plan.path("planId").asText());
        assertTrue(result.path("partial").asBoolean()); assertEquals("user-owned", read(keep));
        assertTrue(Files.exists(install.resolve("go/tracelens.json"))); assertFalse(Files.exists(install.resolve("ems/tracelens.json")));
    }

    @Test void changesAfterIncrementalPreviewRejectBeforeWritingNewServiceFiles() throws Exception {
        ObjectNode initial = request(); installer.apply(initial, installer.plan(initial).path("planId").asText());
        ObjectNode add = request(); ((ObjectNode) add.get("cbApp")).put("enabled", false); ((ObjectNode) add.get("ems")).put("enabled", true);
        ObjectNode plan = installer.plan(add);
        Path primary = install.resolve("cb-app/tracelens.json"); ObjectNode edited = (ObjectNode) Json.parse(Files.readAllBytes(primary));
        edited.put("userEdit", "preserve"); write(primary, Json.stringify(edited));
        assertThrows(IllegalArgumentException.class, () -> installer.apply(add, plan.path("planId").asText()));
        assertFalse(Files.exists(install.resolve("ems"))); assertEquals("preserve", Json.parse(Files.readAllBytes(primary)).path("userEdit").asText());
    }

    @Test void cbAppHookWriteFailureDoesNotPreventEmsInstallationAndRegistration() throws Exception {
        ObjectNode request = request(); ((ObjectNode) request.get("ems")).put("enabled", true);
        String before = read(setenv); ObjectNode plan = installer.plan(request);
        try (FileChannel channel = FileChannel.open(setenv, StandardOpenOption.READ, StandardOpenOption.WRITE);
             java.nio.channels.FileLock lock = channel.lock()) {
            ObjectNode result = installer.apply(request, plan.path("planId").asText());
            assertTrue(result.path("partial").asBoolean()); assertEquals("failed", serviceOutcome(result, "cbApp").path("status").asText());
            assertEquals("installed", serviceOutcome(result, "ems").path("status").asText());
            assertEquals(install.resolve("ems/tracelens.json").toString(), result.path("viewerConfig").asText());
            assertEquals(1, Json.parse(Files.readAllBytes(install.resolve("ems/tracelens.json"))).path("viewer").path("services").size());
            assertTrue(read(install.resolve("start-viewer.sh")).contains("ems/tracelens.json' serve"));
            assertFalse(Json.stringify(result.path("commands")).contains("start-cb-app.sh"));
        }
        assertEquals(before, read(setenv));
    }

    @Test void loadReportsCurrentSettingsAndLauncherAvailabilityWithoutClaimingOwnership() throws Exception {
        ObjectNode fresh = installer.load(Json.obj("installDir", install.toString()));
        assertFalse(fresh.path("configured").asBoolean()); assertFalse(Files.exists(install));
        assertEquals("detailed", fresh.path("ems").path("capture").path("captureMode").asText());
        ObjectNode initial = request(); ((ObjectNode) initial.get("ems")).put("enabled", true);
        installer.apply(initial, installer.plan(initial).path("planId").asText());
        Path configPath = install.resolve("ems/tracelens.json");
        ObjectNode config = (ObjectNode) Json.parse(Files.readAllBytes(configPath));
        config.put("captureMode", "methods").put("maxTrackedRequests", 37).set("httpIncludePaths", Json.arr("/custom-api"));
        write(configPath, Json.stringify(config));
        ObjectNode loaded = installer.load(Json.obj("installDir", install.toString()));
        assertTrue(loaded.path("configured").asBoolean()); assertTrue(loaded.path("ems").path("installed").asBoolean());
        assertEquals(ems.toString(), loaded.path("ems").path("sourceRoot").asText());
        assertEquals(7779, loaded.path("ems").path("debugPort").asInt());
        assertEquals(Json.arr("/custom-api"), loaded.path("ems").path("httpIncludePaths"));
        assertEquals("methods", loaded.path("ems").path("capture").path("captureMode").asText());
        assertEquals(37, loaded.path("ems").path("capture").path("maxTrackedRequests").asInt());
        assertEquals("manual", loaded.path("ems").path("launchMode").asText());
        assertEquals("available", loaded.path("cbApp").path("launchMode").asText());
        assertFalse(loaded.path("plans").path("enabled").asBoolean());
        assertFalse(Json.stringify(loaded).contains("owned"));
    }

    @Test void loadedUnchangedSettingsRemainByteIdenticalIncludingAbsentDefaults() throws Exception {
        ObjectNode initial = request(); installer.apply(initial, installer.plan(initial).path("planId").asText());
        Path config = install.resolve("cb-app/tracelens.json"); byte[] before = Files.readAllBytes(config);
        ObjectNode loaded = (ObjectNode) Json.parse(Json.stringify(installer.load(Json.obj("installDir", install.toString()))));
        ObjectNode plan = installer.plan(loaded);
        assertEquals("existing", serviceOutcome(plan, "cbApp").path("status").asText());
        assertEquals(0, plan.path("files").size());
        assertArrayEquals(before, Files.readAllBytes(config));
    }

    @Test void updateReviewsOnlyExplicitCaptureFieldsPreservesUnknownConfigAndBacksUpExactBytes() throws Exception {
        ObjectNode initial = request(); installer.apply(initial, installer.plan(initial).path("planId").asText());
        Path path = install.resolve("cb-app/tracelens.json");
        ObjectNode edited = (ObjectNode) Json.parse(Files.readAllBytes(path));
        edited.set("customExtension", Json.obj("keep", Json.arr(1, "value")));
        write(path, Json.stringify(edited)); byte[] before = Files.readAllBytes(path);
        Path launcher = install.resolve("start-cb-app.sh"), viewerLauncher = install.resolve("start-viewer.sh");
        write(launcher, "#!/bin/sh\n# operator-customized\nexit 42\n");
        write(viewerLauncher, "#!/bin/sh\n# custom viewer\nexit 43\n");
        byte[] scriptBefore = Files.readAllBytes(launcher), viewerBefore = Files.readAllBytes(viewerLauncher), stateBefore = Files.readAllBytes(install.resolve("setup-state.json"));
        String hook = read(setenv); writeJar(jar, true, "new bundle does not upgrade config-only edits");
        ObjectNode update = request();
        ((ObjectNode) update.get("cbApp")).put("sourceRoot", "/ignored/checkout").put("debugPort", 9999)
                .set("capture", Json.obj("captureMode", "methods", "captureJdbcQueries", true));
        ((ObjectNode) update.get("cbApp")).set("httpIncludePaths", Json.arr("/updated-api"));
        ObjectNode plan = installer.plan(update); JsonNode row = serviceOutcome(plan, "cbApp");
        assertEquals("ready", row.path("status").asText()); assertEquals("update", row.path("operation").asText());
        assertEquals(3, row.path("configChanges").size()); assertEquals(1, plan.path("files").size());
        assertArrayEquals(before, Files.readAllBytes(path));
        ObjectNode result = installer.apply(update, plan.path("planId").asText());
        assertEquals("updated", serviceOutcome(result, "cbApp").path("status").asText());
        assertTrue(serviceOutcome(result, "cbApp").path("restartRequired").asBoolean());
        ObjectNode after = (ObjectNode) Json.parse(Files.readAllBytes(path));
        ObjectNode expected = edited.deepCopy(); expected.put("captureMode", "methods").put("captureJdbcQueries", true).set("httpIncludePaths", Json.arr("/updated-api"));
        assertEquals(expected, after);
        assertArrayEquals(before, Files.readAllBytes(Paths.get(result.path("backupDir").asText()).resolve("cb-app/tracelens.json.before")));
        assertArrayEquals(scriptBefore, Files.readAllBytes(launcher)); assertArrayEquals(viewerBefore, Files.readAllBytes(viewerLauncher));
        assertArrayEquals(stateBefore, Files.readAllBytes(install.resolve("setup-state.json"))); assertEquals(hook, read(setenv));
    }

    @Test void perServiceCaptureOverridesLegacyGlobalsAndInvalidBindingsFailIndependently() throws Exception {
        ObjectNode request = request().put("captureMode", "overview").put("captureJdbcQueries", true);
        ((ObjectNode) request.get("cbApp")).set("capture", Json.obj("captureMode", "methods", "captureJdbcQueries", false));
        ((ObjectNode) request.get("ems")).put("enabled", true).set("capture", Json.obj("captureClickHouseParameters", true));
        ObjectNode plan = installer.plan(request);
        assertEquals("failed", serviceOutcome(plan, "ems").path("status").asText());
        ObjectNode result = installer.apply(request, plan.path("planId").asText()); assertTrue(result.path("partial").asBoolean());
        JsonNode config = Json.parse(Files.readAllBytes(install.resolve("cb-app/tracelens.json")));
        assertEquals("methods", config.path("captureMode").asText()); assertFalse(config.path("captureJdbcQueries").asBoolean());
        assertFalse(Files.exists(install.resolve("ems")));
    }

    @Test void updateAndAddKeepBothCaptureChangesAndSharedRegistry() throws Exception {
        ObjectNode initial = request(); installer.apply(initial, installer.plan(initial).path("planId").asText());
        ObjectNode update = request(); ((ObjectNode) update.get("cbApp")).set("capture", Json.obj("captureMode", "overview"));
        ((ObjectNode) update.get("ems")).put("enabled", true).set("capture", Json.obj("captureMode", "methods"));
        ObjectNode result = installer.apply(update, installer.plan(update).path("planId").asText());
        assertEquals("updated", serviceOutcome(result, "cbApp").path("status").asText());
        assertEquals("installed", serviceOutcome(result, "ems").path("status").asText());
        JsonNode cb = Json.parse(Files.readAllBytes(install.resolve("cb-app/tracelens.json"))), emsConfig = Json.parse(Files.readAllBytes(install.resolve("ems/tracelens.json")));
        assertEquals("overview", cb.path("captureMode").asText()); assertEquals("methods", emsConfig.path("captureMode").asText());
        assertEquals(2, cb.path("viewer").path("services").size()); assertEquals(cb.path("viewer"), emsConfig.path("viewer"));
    }

    @Test void staleNonPrimaryCaptureRejectsAllWritesAndLockedUpdateDoesNotBlockSibling() throws Exception {
        ObjectNode initial = request(); ((ObjectNode) initial.get("ems")).put("enabled", true);
        installer.apply(initial, installer.plan(initial).path("planId").asText());
        ObjectNode update = request(); ((ObjectNode) update.get("cbApp")).set("capture", Json.obj("captureMode", "overview"));
        ((ObjectNode) update.get("ems")).put("enabled", true).set("capture", Json.obj("captureMode", "methods"));
        Path cbPath = install.resolve("cb-app/tracelens.json"), emsPath = install.resolve("ems/tracelens.json");
        byte[] cbBefore = Files.readAllBytes(cbPath); ObjectNode plan = installer.plan(update);
        write(emsPath, read(emsPath) + "\n"); byte[] emsBefore = Files.readAllBytes(emsPath);
        assertThrows(IllegalArgumentException.class, () -> installer.apply(update, plan.path("planId").asText()));
        assertArrayEquals(cbBefore, Files.readAllBytes(cbPath));
        ObjectNode reviewed = installer.plan(update);
        try (FileChannel channel = FileChannel.open(emsPath, StandardOpenOption.READ, StandardOpenOption.WRITE);
             java.nio.channels.FileLock lock = channel.lock()) {
            ObjectNode result = installer.apply(update, reviewed.path("planId").asText());
            assertEquals("updated", serviceOutcome(result, "cbApp").path("status").asText());
            assertEquals("failed", serviceOutcome(result, "ems").path("status").asText()); assertTrue(result.path("partial").asBoolean());
        }
        assertArrayEquals(emsBefore, Files.readAllBytes(emsPath));
    }

    @Test void emptyHttpScopeSupportsSchedulerOnlyInstallAndSurvivesCaptureEditsAndReload() throws Exception {
        ObjectNode initial = request();
        ((ObjectNode) initial.get("cbApp")).set("httpIncludePaths", Json.arr());
        ((ObjectNode) initial.get("ems")).put("enabled", true).set("httpIncludePaths", Json.arr());
        installer.apply(initial, installer.plan(initial).path("planId").asText());
        ObjectNode loaded = installer.load(Json.obj("installDir", install.toString()));
        assertEquals(Json.arr(), loaded.path("cbApp").path("httpIncludePaths"));
        assertEquals(Json.arr(), loaded.path("ems").path("httpIncludePaths"));
        ((ObjectNode) loaded.path("cbApp").path("capture")).put("instrumentScheduled", true).put("captureMode", "methods");
        ObjectNode result = installer.apply(loaded, installer.plan(loaded).path("planId").asText());
        assertEquals("updated", serviceOutcome(result, "cbApp").path("status").asText());
        JsonNode config = Json.parse(Files.readAllBytes(install.resolve("cb-app/tracelens.json")));
        assertTrue(config.path("instrumentScheduled").asBoolean());
        assertEquals(Json.arr(), config.path("httpIncludePaths"));
        assertEquals(Json.arr(), installer.load(Json.obj("installDir", install.toString())).path("cbApp").path("httpIncludePaths"));
        ObjectNode edit = request();
        ((ObjectNode) edit.get("cbApp")).set("capture", Json.obj("trackExceptions", false));
        ((ObjectNode) edit.get("cbApp")).set("httpIncludePaths", Json.arr("/api"));
        installer.apply(edit, installer.plan(edit).path("planId").asText());
        ((ObjectNode) edit.get("cbApp")).set("httpIncludePaths", Json.arr());
        installer.apply(edit, installer.plan(edit).path("planId").asText());
        assertEquals(Json.arr(), installer.load(Json.obj("installDir", install.toString())).path("cbApp").path("httpIncludePaths"));
    }

    private static JsonNode serviceOutcome(JsonNode plan, String key) {
        for (JsonNode row : plan.path("services")) if (key.equals(row.path("key").asText())) return row;
        fail("Missing service result: " + key); return null;
    }

    private static String output(Process process) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int count;
        try (InputStream stream = process.getInputStream()) { while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count); }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private ObjectNode emsLauncherRequest(Path launch) throws IOException {
        ObjectNode request = request(); ((ObjectNode) request.get("cbApp")).put("enabled", false);
        Path javaHome = Files.createDirectories(root.resolve("jdk21/bin")).getParent();
        write(javaHome.resolve("bin/java"), "#!/bin/sh\nexit 99\n");
        Files.setPosixFilePermissions(javaHome.resolve("bin/java"), PosixFilePermissions.fromString("rwx------"));
        ((ObjectNode) request.get("ems")).put("enabled", true).put("launchJar", launch.toString()).put("javaHome", javaHome.toString());
        return request;
    }

    private static void sparsePrefix(Path path, long size) throws IOException {
        // ZIP/JAR readers support a prefix (as in self-extracting archives). A sparse
        // prefix keeps the complete tiny manifest ZIP valid without writing 100s of MiB.
        byte[] archive = Files.readAllBytes(path);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.position(size - archive.length);
            ByteBuffer bytes = ByteBuffer.wrap(archive);
            while (bytes.hasRemaining()) channel.write(bytes);
        }
    }

    private static void writeJar(Path path, boolean combined) throws IOException { writeJar(path, combined, "original"); }
    private static void writeJar(Path path, boolean combined, String marker) throws IOException {
        Manifest manifest = new Manifest(); Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue("Main-Class", combined ? "com.chargebee.tracelens.tool.ToolMain" : "com.example.EmsMain");
        attributes.putValue("Test-Marker", marker);
        if (combined) attributes.putValue("Premain-Class", "com.chargebee.tracelens.agent.TraceLensAgent");
        try (OutputStream output = Files.newOutputStream(path); JarOutputStream jar = new JarOutputStream(output, manifest)) { }
    }
    private static void write(Path path, String value) throws IOException { Files.write(path, value.getBytes(StandardCharsets.UTF_8)); }
    private static String read(Path path) throws IOException { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
}
