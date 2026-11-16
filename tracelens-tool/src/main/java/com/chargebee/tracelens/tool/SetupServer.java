package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Explicit, short-lived local setup authority, separate from the read-only viewer. */
public final class SetupServer implements AutoCloseable {
    private static final int MAX_BODY = 65536;
    static final int SESSION_MINUTES = 15;
    private static final long SESSION_NANOS = TimeUnit.MINUTES.toNanos(SESSION_MINUTES);
    private final HttpServer server;
    private final SetupInstaller installer;
    private final SetupServiceLifecycle lifecycle;
    private final ThreadPoolExecutor executor;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final String token;
    private final LongSupplier nanoTime;
    private final long started;

    public SetupServer(int port, Path sourceJar) throws IOException {
        this(port, sourceJar, System::nanoTime);
    }

    SetupServer(int port, Path sourceJar, LongSupplier nanoTime) throws IOException {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Setup port must be 0–65535");
        this.nanoTime = java.util.Objects.requireNonNull(nanoTime);
        started = nanoTime.getAsLong();
        installer = new SetupInstaller(sourceJar);
        lifecycle = new SetupServiceLifecycle(installer, sourceJar);
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        // Bound both slow clients and worker count; never listen on a LAN interface.
        System.setProperty("sun.net.httpserver.maxReqTime", "15");
        System.setProperty("sun.net.httpserver.maxRspTime", "30");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 8);
        executor = new ThreadPoolExecutor(1, 4, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(16), runnable -> {
                    Thread thread = new Thread(runnable, "tracelens-setup-http");
                    thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    public String url() { return "http://127.0.0.1:" + port() + "/"; }
    public void await() throws InterruptedException { stopped.await(); }
    public void close() { server.stop(0); executor.shutdownNow(); stopped.countDown(); }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String authority = "127.0.0.1:" + port();
            String origin = "http://" + authority;
            String requestOrigin = exchange.getRequestHeaders().getFirst("Origin");
            String fetchSite = exchange.getRequestHeaders().getFirst("Sec-Fetch-Site");
            if (!authority.equals(exchange.getRequestHeaders().getFirst("Host"))
                    || (requestOrigin != null && !origin.equals(requestOrigin))
                    || (fetchSite != null && !"same-origin".equals(fetchSite) && !"none".equals(fetchSite))) {
                error(exchange, 403, "Open the setup URL printed in your terminal on this machine."); return;
            }
            if (rejectExpired(exchange)) return;
            String path = exchange.getRequestURI().getRawPath();
            if (exchange.getRequestURI().getRawQuery() != null) {
                error(exchange, 400, "Setup routes do not accept query parameters."); return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && "/".equals(path)) {
                String html = new String(resource("setup.html"), StandardCharsets.UTF_8)
                        .replace("__TRACELENS_SETUP_TOKEN__", token)
                        .replace("__TRACELENS_SETUP_MINUTES__", Integer.toString(SESSION_MINUTES));
                send(exchange, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8)); return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && ("/setup.js".equals(path) || "/setup.css".equals(path))) {
                send(exchange, 200, path.endsWith(".js") ? "text/javascript; charset=utf-8" : "text/css; charset=utf-8",
                        resource(path.substring(1))); return;
            }
            if (!path.startsWith("/api/setup/")) { error(exchange, 404, "Not found"); return; }
            String provided = exchange.getRequestHeaders().getFirst("X-TraceLens-Setup");
            if (provided == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
                error(exchange, 403, "Setup authorization is missing. Open the wizard from its terminal URL."); return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && "/api/setup/defaults".equals(path)) {
                json(exchange, 200, installer.defaults()); return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) { error(exchange, 405, "Use the setup wizard to review and apply changes."); return; }
            if (!origin.equals(requestOrigin)) { error(exchange, 403, "Setup changes require the same-origin browser wizard."); return; }
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !"application/json".equalsIgnoreCase(contentType.split(";", 2)[0].trim())) {
                error(exchange, 415, "Setup requests must contain JSON."); return;
            }
            JsonNode input = Json.parse(readBounded(exchange.getRequestBody()));
            if (!input.isObject()) throw new IllegalArgumentException("Setup input must be an object");
            // Serialize review/apply so one session cannot interleave filesystem changes.
            synchronized (this) {
                // Reading a body or waiting for another request must not extend setup authority.
                if (rejectExpired(exchange)) return;
                if ("/api/setup/load".equals(path)) json(exchange, 200, installer.load(input));
                else if ("/api/setup/plan".equals(path)) json(exchange, 200, installer.plan(input));
                else if ("/api/setup/apply".equals(path)) {
                    if (!input.path("request").isObject() || !input.path("planId").isTextual())
                        throw new IllegalArgumentException("Review setup before applying it");
                    JsonNode result = installer.apply(input.path("request"), input.path("planId").asText());
                    json(exchange, 200, result);
                } else if ("/api/setup/lifecycle/plan".equals(path)) json(exchange, 200, lifecycle.plan(input));
                else if ("/api/setup/lifecycle/apply".equals(path)) json(exchange, 200, lifecycle.apply(input));
                else if ("/api/setup/lifecycle/status".equals(path)) json(exchange, 200, lifecycle.status(input));
                else error(exchange, 404, "Not found");
            }
        } catch (BodyTooLarge error) {
            error(exchange, 413, "Setup input is too large (maximum 64 KiB).");
        } catch (IllegalArgumentException error) {
            error(exchange, 400, error.getMessage() == null ? "Invalid setup input" : error.getMessage());
        } catch (Exception error) {
            // Do not serialize stack traces, request bodies, existing settings or credentials.
            error(exchange, 500, "Setup could not complete. " + (error instanceof IllegalStateException
                    ? error.getMessage() : "Check directory permissions and restart setup to review the files again."));
        } finally { exchange.close(); }
    }

    private boolean rejectExpired(HttpExchange exchange) throws IOException {
        if (nanoTime.getAsLong() - started < SESSION_NANOS) return false;
        error(exchange, 410, "This setup session expired after " + SESSION_MINUTES
                + " minutes. Stop it and run setup again.");
        return true;
    }

    private static byte[] readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int n;
        while ((n = in.read(buffer)) != -1) {
            if (out.size() + n > MAX_BODY) throw new BodyTooLarge();
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
    private static byte[] resource(String name) throws IOException {
        try (InputStream input = SetupServer.class.getResourceAsStream("/tracelens-setup/" + name)) {
            if (input == null) throw new IOException("Missing bundled setup resource");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return bytes.toByteArray();
        }
    }
    private static void error(HttpExchange exchange, int status, String message) throws IOException {
        json(exchange, status, Json.obj("error", message));
    }
    private static void json(HttpExchange exchange, int status, JsonNode body) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", Json.stringify(body).getBytes(StandardCharsets.UTF_8));
    }
    private static void send(HttpExchange exchange, int status, String type, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
    private static final class BodyTooLarge extends IOException { private static final long serialVersionUID = 1L; }
}
