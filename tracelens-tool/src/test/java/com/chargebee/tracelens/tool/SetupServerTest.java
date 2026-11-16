package com.chargebee.tracelens.tool;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class SetupServerTest {
    @TempDir Path temp;
    SetupServer server;
    String origin, token;
    HttpClient client = HttpClient.newHttpClient();
    AtomicLong nanoTime = new AtomicLong(TimeUnit.HOURS.toNanos(1));
    long started = nanoTime.get();

    @BeforeEach void start() throws Exception {
        server = new SetupServer(0, temp.resolve("tracelens.jar"), nanoTime::get); server.start();
        origin = server.url().replaceAll("/$", "");
        HttpResponse<String> page = request("GET", "/", null, null, null, null);
        assertThat(page.statusCode()).isEqualTo(200);
        java.util.regex.Matcher matcher = Pattern.compile("content=\"([A-Za-z0-9_-]{43})\"").matcher(page.body());
        assertThat(matcher.find()).isTrue(); token = matcher.group(1);
        assertThat(page.body()).doesNotContain("__TRACELENS_SETUP_TOKEN__");
        assertThat(page.body()).contains("Setup session expires 15 minutes after launch.")
                .doesNotContain("__TRACELENS_SETUP_MINUTES__");
    }
    @AfterEach void stop() { server.close(); }

    HttpResponse<String> request(String method, String path, String auth, String requestOrigin, String type, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(10));
        if (auth != null) builder.header("X-TraceLens-Setup", auth);
        if (requestOrigin != null) builder.header("Origin", requestOrigin);
        if (type != null) builder.header("Content-Type", type);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> result = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(result.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(result.headers().firstValue("X-Frame-Options")).contains("DENY");
        assertThat(result.headers().firstValue("Content-Security-Policy").orElse("")).contains("frame-ancestors 'none'");
        return result;
    }
    @Test void onlyBundlesStaticAssetsAndUsesPrivateDefaults() throws Exception {
        assertThat(request("GET", "/setup.js", null, null, null, null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/setup.css", null, null, null, null).statusCode()).isEqualTo(200);
        String page = request("GET", "/", null, null, null, null).body();
        assertThat(page).contains("id=\"landing-view\"", "id=\"choose-setup\"", "id=\"choose-existing\"",
                "id=\"dashboard-view\"", "id=\"dashboard-services\"");
        assertThat(request("GET", "/../cb-app.json", null, null, null, null).statusCode()).isEqualTo(404);
        assertThat(request("GET", "/api/setup/defaults", null, null, null, null).statusCode()).isEqualTo(403);
        HttpResponse<String> defaults = request("GET", "/api/setup/defaults", token, null, null, null);
        assertThat(defaults.statusCode()).isEqualTo(200);
        assertThat(Json.parse(defaults.body()).path("captureJdbcQueries").asBoolean()).isFalse();
        assertThat(Json.parse(defaults.body()).path("captureJdbcParameters").asBoolean()).isFalse();
    }
    @Test void rejectsCrossOriginAndMissingConfirmationBeforeFileAccess() throws Exception {
        assertThat(request("GET", "/", null, "https://other.example", null, null).statusCode()).isEqualTo(403);
        assertThat(request("POST", "/api/setup/apply", null, origin, "application/json", "{}").statusCode()).isEqualTo(403);
        assertThat(request("POST", "/api/setup/apply", token, null, "application/json", "{}").statusCode()).isEqualTo(403);
        assertThat(request("POST", "/api/setup/apply", token, "http://localhost:" + server.port(), "application/json", "{}").statusCode()).isEqualTo(403);
        assertThat(request("POST", "/api/setup/apply", token, origin, "text/plain", "{}").statusCode()).isEqualTo(415);
        assertThat(request("POST", "/api/setup/apply", token, origin, "application/json", "{}").statusCode()).isEqualTo(400);
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(temp)) { assertThat(files.count()).isZero(); }
    }
    @Test void rejectsOversizedMalformedAndUnknownRoutes() throws Exception {
        assertThat(request("POST", "/api/setup/plan", token, origin, "application/json", "{} {}").statusCode()).isEqualTo(400);
        assertThat(request("POST", "/api/setup/plan", token, origin, "application/json", "{\"a\":1,\"a\":2}").statusCode()).isEqualTo(400);
        assertThat(request("POST", "/api/setup/plan", token, origin, "application/json", new String(new char[65537]).replace('\0',' ')).statusCode()).isEqualTo(413);
        assertThat(request("POST", "/api/setup/unknown", token, origin, "application/json", "{}").statusCode()).isEqualTo(404);
        assertThat(request("GET", "/api/setup/defaults?path=secret", token, null, null, null).statusCode()).isEqualTo(400);
        assertThat(request("DELETE", "/api/setup/apply", token, origin, null, null).statusCode()).isEqualTo(405);
    }
    @Test void rejectsCrossSiteFetchAndInvalidPort() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(origin + "/")).header("Sec-Fetch-Site", "cross-site").build();
        assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        assertThatThrownBy(() -> new SetupServer(65536, temp.resolve("jar"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void dashboardLoadAndLifecycleRequireSameOriginTokenAndExplicitConsent() throws Exception {
        for (String path : new String[]{"/api/setup/load", "/api/setup/lifecycle/plan", "/api/setup/lifecycle/apply", "/api/setup/lifecycle/status"}) {
            assertThat(request("POST", path, null, origin, "application/json", "{}").statusCode()).isEqualTo(403);
            assertThat(request("POST", path, token, "https://external.example", "application/json", "{}").statusCode()).isEqualTo(403);
        }
        assertThat(request("POST", "/api/setup/lifecycle/apply", token, origin, "application/json", "{}").body()).contains("Confirm");
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(temp)) { assertThat(files.count()).isZero(); }
    }

    @Test void expiresAtFifteenMinutesEvenWithRecentActivity() throws Exception {
        nanoTime.set(started + TimeUnit.MINUTES.toNanos(14));
        assertThat(request("GET", "/api/setup/defaults", token, null, null, null).statusCode()).isEqualTo(200);
        nanoTime.set(started + TimeUnit.MINUTES.toNanos(15) - 1);
        assertThat(request("GET", "/", null, null, null, null).statusCode()).isEqualTo(200);

        nanoTime.incrementAndGet();
        assertExpired(request("GET", "/", null, null, null, null));
        assertExpired(request("GET", "/api/setup/defaults", token, null, null, null));
        assertExpired(request("POST", "/api/setup/plan", token, origin, "application/json", "{}"));
        assertExpired(request("POST", "/api/setup/apply", token, origin, "application/json", "{}"));
        assertExpired(request("POST", "/api/setup/load", token, origin, "application/json", "{}"));
        assertExpired(request("POST", "/api/setup/lifecycle/status", token, origin, "application/json", "{}"));
        assertExpired(request("POST", "/api/setup/lifecycle/plan", token, origin, "application/json", "{}"));
        assertExpired(request("POST", "/api/setup/lifecycle/apply", token, origin, "application/json", "{}"));
        nanoTime.addAndGet(TimeUnit.MINUTES.toNanos(1));
        assertExpired(request("GET", "/", null, null, null, null));
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(temp)) { assertThat(files.count()).isZero(); }
    }

    private void assertExpired(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(410);
        assertThat(Json.parse(response.body()).path("error").asText())
                .isEqualTo("This setup session expired after 15 minutes. Stop it and run setup again.");
    }
}
