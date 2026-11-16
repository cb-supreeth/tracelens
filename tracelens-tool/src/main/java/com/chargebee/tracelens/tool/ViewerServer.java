package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Local-only Java viewer. This entry point never initializes the tracing agent. */
public final class ViewerServer implements AutoCloseable {
    private final ToolConfig config;
    private final Federation discovery;
    private final DebugClient client;
    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final ObjectNode context;
    private final ViewerSnapshotStore snapshots;
    private final CaptureStore captures;
    private final RequestSearch requestSearch;
    private final SegmentDetails details;

    public ViewerServer(ToolConfig config) throws IOException {
        this.config = config;
        this.discovery = new Federation(config.services);
        this.client = new DebugClient(config.upstream);
        this.context = viewerContext(config);
        this.snapshots = new ViewerSnapshotStore(config.snapshotDir, context.path("namespace").asText());
        this.captures = new CaptureStore(config.captureDir);
        this.requestSearch = new RequestSearch(config.federated, discovery.clients, client);
        this.details = new SegmentDetails(config, discovery, client);
        // JDK HttpServer timeouts are read at initialization. Bound slow bodies as
        // well as wire size; the viewer is not a public multi-tenant web server.
        System.setProperty("sun.net.httpserver.maxReqTime", "5");
        System.setProperty("sun.net.httpserver.maxRspTime", "90");
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", config.port), 16);
        this.executor = new ThreadPoolExecutor(2, 8, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(32), runnable -> {
                    Thread thread = new Thread(runnable, "tracelens-viewer");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        this.server.setExecutor(executor);
        this.server.createContext("/", this::handle);
    }
    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    public void await() throws InterruptedException { stopped.await(); }
    @Override public void close() {
        server.stop(0);
        executor.shutdownNow();
        requestSearch.close();
        stopped.countDown();
    }
    public static ObjectNode viewerContext(ToolConfig config) {
        List<Service> configured = config.federated ? new ArrayList<>(config.services)
                : new ArrayList<>(Collections.singletonList(new Service("local", config.upstream,
                config.sourceRoot != null ? config.sourceRoot : Source.defaultRoot().toString())));
        configured.sort((a, b) -> a.name.compareTo(b.name));
        ArrayNode mappings = Json.arr(), names = Json.arr();
        for (Service service : configured) {
            URI endpoint = URI.create(service.url);
            String scheme = endpoint.getScheme().toLowerCase(java.util.Locale.ROOT);
            String hostname = endpoint.getHost().toLowerCase(java.util.Locale.ROOT).replaceAll("^\\[|\\]$", "");
            ArrayNode target = Json.arr(scheme, hostname,
                    endpoint.getPort() < 0 ? ("https".equals(scheme) ? 443 : 80) : endpoint.getPort(),
                    endpoint.getRawPath().replaceAll("/+$", ""), "", "", null, null);
            String source = null;
            if (service.sourceRoot != null) {
                source = resolvedSourceRoot(Paths.get(service.sourceRoot)).toString();
            }
            mappings.add(Json.arr(service.name, target, source));
            names.add(service.name);
        }
        String mode = config.federated ? "federated" : "single";
        String namespace = config.viewerNamespace;
        if (namespace != null && !namespace.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid viewer namespace");
        if (namespace == null) namespace = Json.sha256(Json.canonical(Json.obj("mode", mode, "mappings", mappings)));
        return Json.obj("namespace", namespace,
                "mode", mode, "services", names, "largeTraceProtocol", 1, "capabilities", Json.obj("largeTraceDetails", true));
    }
    private static Path resolvedSourceRoot(Path path) {
        // Match Path.resolve(strict=False): resolve existing ancestors even when
        // the checkout is temporarily missing, keeping its namespace stable.
        Path absolute = path.toAbsolutePath().normalize(), existing = absolute;
        List<Path> tail = new ArrayList<>();
        while (existing != null) {
            try {
                Path resolved = existing.toRealPath();
                for (int i = tail.size() - 1; i >= 0; i--) resolved = resolved.resolve(tail.get(i));
                return resolved;
            } catch (IOException exc) {
                if (existing.getFileName() != null) tail.add(existing.getFileName());
                existing = existing.getParent();
            }
        }
        return absolute;
    }
    private void handle(HttpExchange exchange) {
        try {
            String method = exchange.getRequestMethod();
            localOrigin(exchange, !"GET".equals(method));
            if (exchange.getRequestURI().toString().length() > 8192)
                throw new ToolException(414, "Request URL exceeds the limit");
            if ("GET".equals(method)) get(exchange);
            else if ("POST".equals(method)) post(exchange);
            else if ("DELETE".equals(method) && exchange.getRequestURI().getPath().startsWith("/api/snapshots/"))
                snapshot(exchange, method);
            else send(exchange, Json.obj("error", "not found"), 404);
        } catch (ToolException exc) {
            safeSend(exchange, Json.obj("error", exc.getMessage()), exc.status >= 400 && exc.status <= 599 ? exc.status : 502);
        } catch (IllegalArgumentException exc) {
            // Invalid JSON and coordinates may include private evidence; never echo parser excerpts.
            safeSend(exchange, Json.obj("error", "Invalid request or local configuration. No live evidence was substituted."), 400);
        } catch (Exception exc) {
            safeSend(exchange, Json.obj("error", "Configured service or private evidence storage is unavailable."), 503);
        } finally { exchange.close(); }
    }
    private void get(HttpExchange exchange) throws IOException {
        noBody(exchange);
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/") || path.equals("/index.html")) { staticFile(exchange, "index.html"); return; }
        if (path.startsWith("/static/")) { staticFile(exchange, path.substring(8)); return; }
        Map<String, String> params = query(exchange, 32);
        String service = params.get("service"), instance = params.get("instance");
        ObjectNode result;
        if (path.equals("/api/viewer-context")) result = context;
        else if (path.equals("/api/summary")) result = config.federated ? discovery.summary() : client.summary();
        else if (path.equals("/api/traces")) result = config.federated ? discovery.traces(Json.obj()) : client.traces();
        else if (path.equals("/api/error-groups")) {
            // Reject invalid filters before contacting any configured agent.
            ObjectNode validated = ErrorGroups.validateFilters(Json.node(params));
            result = ErrorGroups.build(discovery.traces(Json.obj("limit", 1000)), validated);
        }
        else if (path.equals("/api/distributed-traces"))
            result = discovery.traces(filters(params));
        else if (path.startsWith("/api/distributed-trace/")) {
            result = discovery.getDistributedTrace(path.substring("/api/distributed-trace/".length()));
            result.set("visualization", Visualization.build(result));
        } else if (path.startsWith("/api/trace-visualization/"))
            result = Visualization.build(discovery.getDistributedTrace(path.substring("/api/trace-visualization/".length())));
        else if (path.startsWith("/api/segment-overview/") || path.startsWith("/api/segment-events/")
                || path.startsWith("/api/segment-request-data/") || path.startsWith("/api/segment-execution/")) {
            String[] parts = path.split("/", -1);
            if (parts.length != 4) throw new IllegalArgumentException("Invalid segment route");
            result = segmentDetail(null, parts[3], parts[2].substring("segment-".length()), params);
        }
        else if (path.startsWith("/api/export/"))
            result = export(path.substring("/api/export/".length()), service, instance);
        else if (path.startsWith("/api/render-data/")) {
            result = export(path.substring("/api/render-data/".length()), service, instance);
            String root = config.federated ? discovery.sourceRoot(Json.text(result, "service")) : config.sourceRoot;
            if (root != null) result = new Source(Paths.get(root)).enrich(result);
            else if (!config.federated) result = new Source(Source.defaultRoot()).enrich(result);
        } else if (path.equals("/api/snapshots") || path.startsWith("/api/snapshots/")) {
            snapshot(exchange, "GET"); return;
        } else if (path.equals("/api/guided-investigation")) {
            allowed(params.keySet(), "path", "method", "service", "user", "site");
            if (!params.containsKey("path")) throw new IllegalArgumentException("Supply exact path");
            result = GuidedInvestigation.diagnoseRequest(discovery, (ObjectNode) Json.node(params));
        } else if (path.equals("/api/source-location")) {
            allowed(params.keySet(), "className", "line", "service");
            if (!params.containsKey("className") || !params.containsKey("line") || !params.get("line").matches("[0-9]{1,7}"))
                throw new IllegalArgumentException("Supply source coordinates");
            String root = config.sourceRoot;
            if (config.federated) {
                if (service == null) throw new IllegalArgumentException("Choose a configured service");
                root = discovery.sourceRoot(service);
            } else if (service != null && !discovery.services.containsKey(service))
                throw new IllegalArgumentException("Unknown configured service");
            if (root == null && !config.federated) root = Source.defaultRoot().toString();
            if (root == null) throw new IllegalArgumentException("No local source root configured");
            result = new Source(Paths.get(root)).location(params.get("className"), Integer.parseInt(params.get("line")));
            if (result == null) throw new ToolException(404, "Source location is unavailable in the configured checkout.");
        } else if (path.equals("/api/capture-tools")) {
            if (!params.isEmpty()) throw new IllegalArgumentException("No query parameters accepted");
            ObjectNode saved = snapshots.list(), cli = captures.list();
            ArrayNode warnings = Json.arr();
            for (JsonNode row : Json.rows(saved, "warnings")) warnings.add(row);
            for (JsonNode row : Json.rows(cli, "warnings")) warnings.add(row);
            result = Json.obj("snapshots", saved.get("snapshots"), "captures", cli.get("captures"), "warnings", warnings);
        } else { send(exchange, Json.obj("error", "not found"), 404); return; }
        send(exchange, result, 200);
    }
    private ObjectNode export(String id, String service, String instance) {
        return config.federated ? discovery.export(id, service, instance) : client.export(id);
    }
    private void post(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/api/snapshots") || path.startsWith("/api/snapshots/")) {
            snapshot(exchange, "POST"); return;
        }
        if (path.startsWith("/api/capture-tools/")) { captureTools(exchange); return; }
        if (path.equals("/api/request-search")) {
            if (exchange.getRequestURI().getRawQuery() != null) throw new IllegalArgumentException("No query parameters accepted");
            send(exchange, requestSearch.search(objectBody(exchange, RequestSearch.MAX_REQUEST_BYTES)), 200);
            return;
        }
        if (path.equals("/api/segment-execution")) {
            ObjectNode body = objectBody(exchange, 5 * 1024 * 1024);
            allowed(fields(body), "trace", "maxNodes");
            if (!body.path("trace").isObject()) throw new IllegalArgumentException("Expected trace object");
            int max = Execution.DEFAULT_MAX_NODES;
            if (body.has("maxNodes")) {
                if (!body.path("maxNodes").isIntegralNumber() || !body.path("maxNodes").canConvertToInt())
                    throw new IllegalArgumentException("Invalid maxNodes");
                max = body.path("maxNodes").intValue();
            }
            send(exchange, Execution.build(body.get("trace"), max, Execution.VIEWER_MODEL_MAX_BYTES), 200);
            return;
        }
        noBody(exchange);
        if (config.federated)
            throw new ToolException(405, "Multi-service UI is read-only; use a single-service client for mutations.");
        if (path.equals("/api/enable") || path.equals("/api/disable") || path.equals("/api/reset"))
            send(exchange, client.request("POST", path.substring(4)), 200);
        else if (path.startsWith("/api/reset/"))
            send(exchange, client.reset(path.substring("/api/reset/".length())), 200);
        else send(exchange, Json.obj("error", "not found"), 404);
    }
    private void snapshot(HttpExchange exchange, String method) throws IOException {
        String suffix = exchange.getRequestURI().getPath().substring("/api/snapshots".length());
        if("POST".equals(method)&&"/import".equals(suffix)) {
            if(exchange.getRequestURI().getRawQuery()!=null)throw new IllegalArgumentException("Archive import accepts no query");
            Headers headers=exchange.getRequestHeaders();List<String>lengths=headers.get("Content-Length"),types=headers.get("Content-Type");
            if(lengths==null||lengths.size()!=1||!lengths.get(0).matches("[0-9]{1,10}")||types==null||types.size()!=1
                    ||!"application/zip".equalsIgnoreCase(types.get(0))||headers.containsKey("Transfer-Encoding")||headers.containsKey("Content-Encoding"))
                throw new IllegalArgumentException("Supply a ZIP archive with one Content-Length");
            long length=Long.parseLong(lengths.get(0));
            if(length<1||length>ChunkedSnapshotStore.MAX_COMPRESSED+2L*1024*1024)throw new ToolException(413,"Archive exceeds the upload byte limit.");
            send(exchange,snapshots.chunked.importArchive(exchange.getRequestBody()),201);return;
        }
        String[] parts = suffix.split("/", -1);
        if ("GET".equals(method) && parts.length == 5 && "segments".equals(parts[2])) {
            noBody(exchange); send(exchange, segmentDetail(parts[1], parts[3], parts[4], query(exchange, 16)), 200); return;
        }
        if (exchange.getRequestURI().getRawQuery() != null)
            throw new ToolException(404, "Snapshot routes do not accept query parameters.");
        if ("GET".equals(method) && parts.length == 3 && "download".equals(parts[2])) {
            noBody(exchange); ObjectNode saved = snapshots.get(parts[1]);
            if (saved.path("schemaVersion").asInt() != 2) {
                sendBytes(exchange, Captures.bytes(saved), "application/json; charset=utf-8", 200); return;
            }
            Headers headers = exchange.getResponseHeaders(); headers.set("Content-Type", "application/zip");
            headers.set("Content-Disposition", "attachment; filename=\"tracelens-" + parts[1] + ".zip\"");
            headers.set("Cache-Control", "no-store"); headers.set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(200, 0); snapshots.chunked.download(parts[1], exchange.getResponseBody()); return;
        }
        ObjectNode result;
        int status = 200;
        if ("GET".equals(method)) {
            noBody(exchange);
            result = suffix.isEmpty() ? snapshots.list() : snapshots.get(suffix.substring(1));
        } else if ("DELETE".equals(method)) {
            noBody(exchange);
            result = snapshots.delete(suffix.substring(1));
        } else {
            ObjectNode body = objectBody(exchange, "/capture".equals(suffix) ? 16 * 1024 : ViewerSnapshotStore.MAX_BYTES);
            if ("/capture".equals(suffix)) {
                allowed(fields(body), "traceId", "name", "request"); optionalText(body, "traceId", "name");
                String traceId = Json.text(body, "traceId"); SegmentDetails.id(traceId);
                if (body.has("request") && !body.path("request").isObject()) throw new IllegalArgumentException("Invalid capture request summary");
                ObjectNode journey = discovery.getDistributedTrace(traceId); journey.set("visualization", Visualization.build(journey));
                if (journey.path("segments").size() == 0) throw new ToolException(404, "No retained segment summaries were found; nothing was saved.");
                ObjectNode request = body.has("request") ? RequestEvidence.summary(body.path("request")) : Json.obj(); request.put("traceId", traceId);
                result = snapshots.chunked.capture(traceId, Json.text(body, "name"), request, journey, details); status = 201;
            } else if (suffix.isEmpty() && fields(body).equals(Collections.singleton("snapshot"))) {
                result = snapshots.save(body.get("snapshot"));
                status = 201;
            } else if (suffix.endsWith("/rename") && fields(body).equals(Collections.singleton("name"))) {
                result = snapshots.rename(suffix.substring(1, suffix.length() - 7), Json.text(body, "name"));
            } else throw new IllegalArgumentException("Invalid snapshot request");
        }
        send(exchange, result, status);
    }
    private ObjectNode segmentDetail(String snapshotId, String id, String kind, Map<String, String> params) {
        allowed(params.keySet(), "service", "instance", "after", "through", "limit", "maxBytes", "kind", "aroundSeq", "maxNodes");
        SegmentDetails.id(id); String service = params.get("service"), instance = params.get("instance");
        if (!Arrays.asList("overview", "events", "request-data", "execution").contains(kind)) throw new ToolException(404, "Unknown saved detail route");
        if ("request-data".equals(kind)) return snapshotId == null ? details.requestData(id, service, instance, null)
                : snapshots.chunked.detail(snapshotId, id, service, instance, "request-data");
        BoundedReads.Budget readBudget = "execution".equals(kind) ? new BoundedReads.Budget() : null;
        java.util.function.Supplier<ObjectNode> readOverview = () -> snapshotId == null ? details.overview(id, service, instance)
                : snapshots.chunked.detail(snapshotId, id, service, instance, "overview");
        ObjectNode overview = readBudget == null ? readOverview.get() : readBudget.read(readOverview::get, SegmentDetails.MAX_PAGE_BYTES);
        if ("overview".equals(kind)) return overview;
        long highWater = SegmentDetails.number(overview.get("through"), "high-water");
        long through = SegmentDetails.parameter(params, "through", highWater, 9007199254740991L);
        if (through > highWater) throw new ToolException(409, "Requested high-water is no longer available.");
        final String traceId = Json.text(overview, "traceId"), pinnedInstance = Json.text(overview, "serviceInstanceId");
        if ("execution".equals(kind)) {
            int maxNodes = (int) SegmentDetails.parameter(params, "maxNodes", Execution.DEFAULT_MAX_NODES, Execution.MAX_NODES);
            BoundedEventPages pages = new BoundedEventPages(readBudget, after -> {
                ObjectNode page = snapshotId == null ? details.events(id, service, pinnedInstance, traceId, after, through, SegmentDetails.PAGE_EVENTS, SegmentDetails.PAGE_BYTES, "all")
                        : snapshots.chunked.events(snapshotId, id, service, pinnedInstance, after, through, SegmentDetails.PAGE_EVENTS, SegmentDetails.PAGE_BYTES, "all");
                SegmentDetails.validatePage(page, id, pinnedInstance, traceId, after, through, SegmentDetails.PAGE_EVENTS, "all");
                return page;
            });
            return pages.describeLimit(Execution.buildPages(overview, maxNodes, Execution.VIEWER_MODEL_MAX_BYTES, pages));
        }
        long after = SegmentDetails.parameter(params, "after", 0, 9007199254740991L);
        if (params.containsKey("aroundSeq")) after = Math.max(0, SegmentDetails.parameter(params, "aroundSeq", 0, 9007199254740991L) - 100);
        int limit = (int) SegmentDetails.parameter(params, "limit", SegmentDetails.PAGE_EVENTS, 1000);
        int maxBytes = (int) SegmentDetails.parameter(params, "maxBytes", SegmentDetails.PAGE_BYTES, SegmentDetails.MAX_PAGE_BYTES);
        String filter = params.getOrDefault("kind", "all");
        ObjectNode page = snapshotId == null ? details.events(id, service, pinnedInstance, traceId, after, through, limit, maxBytes, filter)
                : snapshots.chunked.events(snapshotId, id, service, pinnedInstance, after, through, limit, maxBytes, filter);
        if (snapshotId == null) {
            String root = config.federated ? discovery.sourceRoot(service) : config.sourceRoot;
            if (root != null) {
                ObjectNode enriched = new Source(Paths.get(root)).enrichBounded(page,maxBytes);
                if (Captures.bytes(enriched).length <= maxBytes) page = enriched;
            }
        }
        return page;
    }
    private void captureTools(HttpExchange exchange) throws IOException {
        if (exchange.getRequestURI().getRawQuery() != null) throw new IllegalArgumentException("No query accepted");
        ObjectNode body = objectBody(exchange, 16 * 1024), result;
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/api/capture-tools/compare") && fields(body).equals(new HashSet<>(Arrays.asList("before", "after"))))
            result = CaptureTools.compareSavedSnapshots(snapshots, Json.text(body, "before"), Json.text(body, "after"), config.services);
        else if (path.equals("/api/capture-tools/project")) {
            allowed(fields(body), "snapshotId", "label", "revision");
            optionalText(body, "label", "revision");
            result = CaptureTools.exportToCaptureStore(snapshots, captures, Json.text(body, "snapshotId"),
                    Json.text(body, "label"), Json.text(body, "revision"));
        } else if (path.equals("/api/capture-tools/search")) {
            allowed(fields(body), "query", "category", "limit", "snapshotIds");
            optionalText(body, "category");
            if (body.has("limit") && (!body.path("limit").isIntegralNumber() || !body.path("limit").canConvertToInt()))
                throw new IllegalArgumentException("Invalid limit");
            result = CaptureTools.searchSavedEvidence(snapshots, captures, Json.text(body, "query"),
                    Json.text(body, "category"), body.path("limit").asInt(50), body.get("snapshotIds"));
        } else throw new IllegalArgumentException("Invalid capture tools route");
        send(exchange, result, 200);
    }
    private static void optionalText(JsonNode body, String... names) {
        for (String name : names)
            if (body.has(name) && !body.path(name).isNull() && !body.path(name).isTextual())
                throw new IllegalArgumentException("Expected text metadata");
    }
    private void localOrigin(HttpExchange exchange, boolean mutation) {
        Headers headers = exchange.getRequestHeaders();
        List<String> hosts = headers.get("Host"), origins = headers.get("Origin");
        String host = hosts != null && hosts.size() == 1 ? hosts.get(0) : null;
        boolean allowedHost = ("127.0.0.1:" + port()).equals(host) || ("localhost:" + port()).equals(host)
                || (port() == 80 && ("127.0.0.1".equals(host) || "localhost".equals(host)));
        if (!allowedHost || (origins != null && origins.size() != 1)
                || (mutation && origins == null)
                || (origins != null && !("http://" + host).equals(origins.get(0)))
                || "cross-site".equals(headers.getFirst("Sec-Fetch-Site")))
            throw new ToolException(403, "Access requires this viewer's local origin.");
    }
    private static void noBody(HttpExchange exchange) {
        Headers headers = exchange.getRequestHeaders();
        List<String> lengths = headers.get("Content-Length");
        if (headers.containsKey("Transfer-Encoding") || headers.containsKey("Content-Encoding")
                || (lengths != null && (lengths.size() != 1 || !"0".equals(lengths.get(0)))))
            throw new IllegalArgumentException("This route does not accept a body");
    }
    private static ObjectNode objectBody(HttpExchange exchange, int maxBytes) throws IOException {
        Headers headers = exchange.getRequestHeaders();
        List<String> lengths = headers.get("Content-Length"), types = headers.get("Content-Type");
        List<String> encodings = headers.get("Content-Encoding");
        if (lengths == null || lengths.size() != 1 || !lengths.get(0).matches("[0-9]{1,20}")
                || types == null || types.size() != 1
                || !types.get(0).toLowerCase(java.util.Locale.ROOT).matches("application/json(?:\\s*;\\s*charset\\s*=\\s*\"?utf-?8\"?)?\\s*")
                || headers.containsKey("Transfer-Encoding")
                || (encodings != null && (encodings.size() != 1 || !"identity".equalsIgnoreCase(encodings.get(0)))))
            throw new IllegalArgumentException("Expected UTF-8 JSON with one Content-Length");
        long length;
        try { length = Long.parseLong(lengths.get(0)); }
        catch (NumberFormatException exc) { throw new ToolException(413, "Request exceeds the byte limit"); }
        if (length > maxBytes) throw new ToolException(413, "Request exceeds the byte limit; nothing was saved.");
        if (length == 0) throw new IllegalArgumentException("Body required");
        byte[] bytes = DebugClient.readBounded(exchange.getRequestBody(), (int) length, System.nanoTime() + 5_000_000_000L);
        if (bytes.length != length) throw new IllegalArgumentException("Incomplete request body");
        JsonNode body = Json.parse(bytes);
        if (!body.isObject()) throw new IllegalArgumentException("Expected request object");
        return (ObjectNode) body;
    }
    private static Map<String, String> query(HttpExchange exchange, int maximum) {
        Map<String, String> params = new LinkedHashMap<>();
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isEmpty()) return params;
        String[] pairs = query.split("&", -1);
        if (pairs.length > maximum) throw new IllegalArgumentException("Too many parameters");
        for (String pair : pairs) {
            String[] parts = pair.split("=", 2);
            try {
                String key = URLDecoder.decode(parts[0], "UTF-8"), value = URLDecoder.decode(parts.length == 2 ? parts[1] : "", "UTF-8");
                if (params.put(key, value) != null) throw new IllegalArgumentException("Supply each parameter once");
            } catch (IOException exc) { throw new IllegalArgumentException("Invalid query"); }
        }
        return params;
    }
    private static ObjectNode filters(Map<String, String> params) {
        allowed(params.keySet(), "service", "method", "path", "user", "since_minutes", "limit");
        ObjectNode result = (ObjectNode) Json.node(params);
        if (params.containsKey("since_minutes")) result.put("since_minutes", Double.parseDouble(params.get("since_minutes")));
        if (params.containsKey("limit")) result.put("limit", Integer.parseInt(params.get("limit")));
        return result;
    }
    private static Set<String> fields(JsonNode doc) {
        Set<String> fields = new HashSet<>();
        doc.fieldNames().forEachRemaining(fields::add);
        return fields;
    }
    private static void allowed(Set<String> fields, String... names) {
        if (!new HashSet<>(Arrays.asList(names)).containsAll(fields)) throw new IllegalArgumentException("Unexpected fields");
    }
    private static void staticFile(HttpExchange exchange, String name) throws IOException {
        if (!name.matches("[A-Za-z0-9_.-]+") || name.contains("..")) throw new ToolException(404, "Not found");
        byte[] bytes;
        try (InputStream input = ViewerServer.class.getResourceAsStream("/tracelens-web/" + name)) {
            if (input == null) throw new ToolException(404, "Not found");
            bytes = DebugClient.readBounded(input, 4 * 1024 * 1024, System.nanoTime() + 5_000_000_000L);
        }
        String type = name.endsWith(".js") ? "text/javascript" : name.endsWith(".css") ? "text/css"
                : name.endsWith(".html") ? "text/html" : "application/octet-stream";
        sendBytes(exchange, bytes, type + "; charset=utf-8", 200);
    }
    private static void safeSend(HttpExchange exchange, JsonNode doc, int status) {
        try { send(exchange, doc, status); } catch (IOException ignored) { }
    }
    private static void send(HttpExchange exchange, JsonNode doc, int status) throws IOException {
        sendBytes(exchange, Json.stringify(doc).getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8", status);
    }
    private static void sendBytes(HttpExchange exchange, byte[] bytes, String type, int status) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", type);
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Connection", "close");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
