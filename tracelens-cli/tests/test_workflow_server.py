"""Local workflow endpoints: strict inputs, confined source links and offline files."""

from email.message import Message
import http.client
import io
import json
from pathlib import Path
import threading
from types import SimpleNamespace
import urllib.parse
import uuid

import pytest

from tracelens_cli import captures, server, source
from tracelens_cli.services import Federation, Service
from tracelens_cli.viewer_snapshots import ViewerSnapshotStore


HOST = "127.0.0.1:7781"
TRACE = "a" * 32
SEGMENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"


def evidence(method="before"):
    segment = {"service": "app", "serviceInstanceId": "instance", "segmentId": SEGMENT,
               "traceId": TRACE, "entryChannel": "HTTP", "httpMethod": "GET",
               "path": "/api/orders?token=SECRET_QUERY", "status": "COMPLETED", "httpStatus": 200,
               "durationMs": 10, "startedAtMillis": 1000, "eventCount": 2,
               "user": "SECRET_USER", "contextParams": {"key": "SECRET_CONTEXT"},
               "events": [
                   {"type": "METHOD_ENTER", "seq": 1, "nanoTime": 1, "className": "example.OrderService",
                    "methodName": method, "sourceLine": "SECRET_SOURCE", "thread": "main"},
                   {"type": "METHOD_EXIT", "seq": 2, "nanoTime": 2, "className": "example.OrderService",
                    "methodName": method, "thread": "main"},
               ]}
    summary = {key: segment[key] for key in ("service", "serviceInstanceId", "segmentId", "traceId")}
    return {"name": method, "traceId": TRACE, "capturedAt": 2000,
            "partial": False, "warnings": [], "segments": [segment],
            "request": {"traceId": TRACE, "services": ["app"]},
            "journey": {"traceId": TRACE, "segments": [summary], "links": []}}


class Body(io.BytesIO):
    def __init__(self, data):
        super().__init__(data)
        self.read_sizes = []

    def read(self, size=-1):
        self.read_sizes.append(size)
        return super().read(size)


class Client:
    def __init__(self):
        self.calls = []

    def summary(self):
        self.calls.append("summary")
        return {"agentActive": True, "recordingEnabled": True, "httpIncludePaths": ["/api"]}

    def metadata(self):
        self.calls.append("metadata")
        return {"agentActive": True, "requireLabels": False, "packages": ["example"],
                "httpIncludePaths": ["/api"]}

    def traces(self):
        self.calls.append("traces")
        return {"traces": []}


@pytest.fixture
def state(tmp_path, monkeypatch):
    def no_network(*args, **kwargs):
        pytest.fail("Saved/local workflow unexpectedly contacted a live service")
    monkeypatch.setattr(server.urllib.request, "urlopen", no_network)
    monkeypatch.setattr(server.urllib.request, "build_opener", no_network)
    root = tmp_path / "source checkout"
    package = root / "src" / "main" / "java" / "example"
    package.mkdir(parents=True)
    file = package / "OrderService.java"
    file.write_text("package example;\nclass OrderService {}\n")
    discovery = Federation([Service("app", "http://upstream.invalid:7778", str(root))])
    discovery.clients["app"] = Client()
    return SimpleNamespace(snapshots=ViewerSnapshotStore(tmp_path / "pins", "a" * 64),
                           captures=captures.CaptureStore(tmp_path / "captures"),
                           root=root, file=file, discovery=discovery)


def request(state, path="/api/capture-tools", method="GET", doc=None, raw=None, headers=None, federated=True):
    raw = raw if raw is not None else json.dumps(doc).encode() if doc is not None else b""
    handler = server._Handler.__new__(server._Handler)
    handler.path, handler.snapshots, handler.captures = path, state.snapshots, state.captures
    handler.discovery = state.discovery
    handler.federation = state.discovery if federated else None
    handler.source_root = str(state.root)
    handler.server = SimpleNamespace(server_address=("127.0.0.1", 7781))
    handler.headers = Message()
    standard = [("Host", HOST)]
    if method == "POST":
        standard += [("Origin", "http://" + HOST), ("Content-Type", "application/json"),
                     ("Content-Length", str(len(raw)))]
    for name, value in headers if headers is not None else standard:
        handler.headers[name] = value
    handler.connection = SimpleNamespace(gettimeout=lambda: None, settimeout=lambda _: None)
    handler.rfile = Body(raw)
    results = []
    handler._send_json = lambda data, status: results.append((status, data))
    (handler.do_GET if method == "GET" else handler.do_POST)()
    assert len(results) == 1
    return *results[0], handler


READ_PATHS = ["/api/capture-tools", "/api/guided-investigation?path=%2Fapi%2Forders",
              "/api/source-location?className=example.OrderService&line=2&service=app"]


@pytest.mark.parametrize("path", READ_PATHS)
@pytest.mark.parametrize("headers", [
    [], [("Host", "attacker.invalid:7781")], [("Host", "localhost:7782")],
    [("Host", HOST), ("Host", HOST)],
    [("Host", HOST), ("Origin", "https://attacker.invalid")],
    [("Host", HOST), ("Origin", "http://localhost:7781")],
    [("Host", HOST), ("Origin", "http://" + HOST), ("Origin", "http://" + HOST)],
    [("Host", HOST), ("Sec-Fetch-Site", "cross-site")],
])
def test_read_routes_require_exact_local_host_and_consistent_origin(state, path, headers):
    status, _, handler = request(state, path, headers=headers)
    assert status == 403
    assert handler.rfile.read_sizes == []
    assert state.discovery.clients["app"].calls == []


@pytest.mark.parametrize("path", ["/api/capture-tools/compare", "/api/capture-tools/project", "/api/capture-tools/search"])
@pytest.mark.parametrize("security", [
    [("Host", HOST)], [("Host", HOST), ("Origin", "null")],
    [("Host", HOST), ("Origin", "https://attacker.invalid")],
    [("Host", "attacker.invalid:7781"), ("Origin", "http://attacker.invalid:7781")],
    [("Host", HOST), ("Origin", "http://" + HOST), ("Sec-Fetch-Site", "cross-site")],
])
def test_saved_posts_require_same_viewer_origin_before_body_read(state, path, security):
    headers = security + [("Content-Type", "application/json"), ("Content-Length", "2")]
    status, _, handler = request(state, path, "POST", raw=b"{}", headers=headers)
    assert status == 403 and handler.rfile.read_sizes == []
    assert handler.close_connection is True
    assert not state.captures.root.exists()


@pytest.mark.parametrize("path", READ_PATHS)
@pytest.mark.parametrize("extra", [[("Content-Length", "2")], [("Transfer-Encoding", "chunked")],
                                  [("Content-Length", "0"), ("Content-Length", "0")]])
def test_workflow_gets_reject_bodies_before_local_or_upstream_reads(state, path, extra):
    status, _, handler = request(state, path, raw=b"{}", headers=[("Host", HOST), *extra])
    assert status == 400
    assert handler.rfile.read_sizes == []
    assert state.discovery.clients["app"].calls == []


@pytest.mark.parametrize("query", [
    "", "path=%2Fapi&unexpected=x", "path=%2Fapi&path=%2Fother", "path=https%3A%2F%2Fevil.example",
    "path=%2Fapi%3Ftoken%3Dsecret", "path=%2Fapi&method=GET%20%2F", "path=%2Fapi&service=missing",
    "path=%2Fapi&user=%0A", "path=%2Fapi&" + "&".join("key" + str(i) + "=x" for i in range(9)),
])
def test_diagnose_invalid_filters_fail_before_any_upstream_read(state, query):
    status, _, _ = request(state, "/api/guided-investigation?" + query)
    assert status == 400
    assert state.discovery.clients["app"].calls == []


def test_diagnose_route_passes_normalized_exact_filters_to_bounded_read_only_checks(state):
    status, result, _ = request(state, "/api/guided-investigation?path=%2Fapi%2Forders&method=get&service=app&user=Alice&site=42")
    assert status == 200 and result["kind"] == "guided-investigation"
    assert result["filters"] == {"path": "/api/orders", "method": "GET", "service": "app", "user": "Alice", "site": "42"}
    assert state.discovery.clients["app"].calls == ["summary", "metadata", "traces"]
    assert result["scope"] == "retained-only"
    assert not state.snapshots.root.exists() and not state.captures.root.exists()


@pytest.mark.parametrize("query", [
    "className=example.OrderService&line=2",
    "className=example.OrderService&line=2&service=missing",
    "className=example.OrderService&line=0&service=app",
    "className=example.OrderService&line=-2&service=app",
    "className=example.OrderService&line=1000001&service=app",
    "className=example.OrderService&line=%D9%A2&service=app",
    "className=example.OrderService&line=2&service=app&sourceRoot=%2F",
    "className=example.OrderService&line=2&line=3&service=app",
])
def test_source_route_requires_valid_coordinate_and_configured_service(state, query):
    assert request(state, "/api/source-location?" + query)[0] == 400


def test_source_location_is_confined_and_does_not_return_source_contents(state):
    query = urllib.parse.urlencode({"className": "example.OrderService", "line": 2, "service": "app"})
    status, result, _ = request(state, "/api/source-location?" + query)
    assert status == 200
    assert result["path"] == str(state.file.resolve())
    assert result["relativePath"] == "src/main/java/example/OrderService.java"
    assert result["sourceCorrespondence"] == "unverified"
    assert "class OrderService" not in json.dumps(result)
    assert "%20" in result["editors"]["vscode"]
    assert state.discovery.clients["app"].calls == []
    assert request(state, "/api/source-location?className=..%2Foutside&line=2&service=app")[0] == 404
    assert request(state, "/api/source-location?className=example.OrderService&line=3&service=app")[0] == 404


def test_source_symlink_outside_root_is_not_resolved(state, tmp_path):
    outside = tmp_path / "Outside.java"
    outside.write_text("SECRET OUTSIDE SOURCE\n")
    (state.file.parent / "Alias.java").symlink_to(outside)
    status, result, _ = request(state, "/api/source-location?className=example.Alias&line=1&service=app")
    assert status == 404
    assert str(outside) not in json.dumps(result)


def test_single_service_source_route_rejects_wrong_service_identity(state):
    status, _, _ = request(state, "/api/source-location?className=example.OrderService&line=2&service=missing", federated=False)
    assert status == 400


@pytest.mark.parametrize("framing", [
    [("Content-Type", "text/plain"), ("Content-Length", "2")],
    [("Content-Type", "application/json"), ("Content-Length", "2"), ("Content-Length", "2")],
    [("Content-Type", "application/json"), ("Content-Length", "2"), ("Transfer-Encoding", "chunked")],
    [("Content-Type", "application/json"), ("Content-Length", "2"), ("Content-Encoding", "gzip")],
    [("Content-Type", "application/json; charset=latin-1"), ("Content-Length", "2")],
    [("Content-Type", "application/json"), ("Content-Length", "-2")],
    [("Content-Type", "application/json"), ("Content-Length", "٢")],
])
def test_saved_post_body_framing_is_strict_without_read(state, framing):
    headers = [("Host", HOST), ("Origin", "http://" + HOST), *framing]
    status, _, handler = request(state, "/api/capture-tools/search", "POST", raw=b"{}", headers=headers)
    assert status == 400 and handler.rfile.read_sizes == []


@pytest.mark.parametrize("length", ["16385", "9" * 5000])
def test_saved_post_16kib_budget_rejects_before_reading(state, length):
    headers = [("Host", HOST), ("Origin", "http://" + HOST), ("Content-Type", "application/json"),
               ("Content-Length", length)]
    status, _, handler = request(state, "/api/capture-tools/search", "POST", raw=b"{}", headers=headers)
    assert status == 413 and handler.rfile.read_sizes == []


@pytest.mark.parametrize("path,body", [
    ("/api/capture-tools/compare", {"before": "../outside", "after": str(uuid.uuid4())}),
    ("/api/capture-tools/compare", {"before": "x", "after": "y", "path": "/tmp/secret"}),
    ("/api/capture-tools/project", {"snapshotId": str(uuid.uuid4()), "directory": "/tmp/elsewhere"}),
    ("/api/capture-tools/project", {"snapshotId": str(uuid.uuid4()), "label": ""}),
    ("/api/capture-tools/search", {"query": "OrderService", "category": "source"}),
    ("/api/capture-tools/search", {"query": "", "limit": 10}),
    ("/api/capture-tools/search", {"query": "x", "limit": True}),
    ("/api/capture-tools/search", {"query": "x", "limit": 101}),
    ("/api/capture-tools/search?query=bad", {"query": "OrderService"}),
    ("/api/capture-tools/unknown", {"query": "OrderService"}),
    ("/api/capture-tools/search", []),
])
def test_saved_invalid_route_fields_and_values_never_write_or_fetch(state, path, body):
    status, _, _ = request(state, path, "POST", doc=body)
    assert status == (404 if isinstance(body, dict) and body.get("before") == "../outside" else 400)
    assert not state.snapshots.root.exists() and not state.captures.root.exists()
    assert state.discovery.clients["app"].calls == []


@pytest.mark.parametrize("raw", [b'{"query":"one","query":"two"}', b'{"query": NaN}', b"\xff", b"[]broken"])
def test_saved_post_uses_strict_json(state, raw):
    assert request(state, "/api/capture-tools/search", "POST", raw=raw)[0] == 400


def test_compare_project_and_search_round_trip_saved_files_without_live_calls(state):
    first = state.snapshots.save(evidence())
    second = state.snapshots.save(evidence("after"))
    before_bytes = {path.name: path.read_bytes() for path in state.snapshots.root.glob("*.json")}
    status, compared, _ = request(state, "/api/capture-tools/compare", "POST",
                                  {"before": first["id"], "after": second["id"]})
    assert status == 200
    assert compared["methods"]["added"][0]["methodName"] == "after"
    assert compared["methods"]["noLongerObserved"][0]["methodName"] == "before"
    assert compared["sourceSnapshots"]["before"]["snapshotId"] == first["id"]
    assert not state.captures.root.exists()
    status, projected, _ = request(state, "/api/capture-tools/project", "POST",
                                   {"snapshotId": second["id"], "label": "Reviewed", "revision": "build-2"})
    assert status == 200 and projected["saved"] is True
    capture_id = projected["capture"]["captureId"]
    saved = state.captures.get(capture_id)
    assert capture_id != second["id"] and saved["revision"] == "build-2"
    status, found, _ = request(state, "/api/capture-tools/search", "POST",
                               {"query": "OrderService", "category": "method", "limit": 10})
    assert status == 200 and found["returned"] == 3
    assert {item["storage"] for item in found["results"]} == {"viewer", "capture"}
    assert "SECRET" not in json.dumps([compared, projected, saved, found])
    assert before_bytes == {path.name: path.read_bytes() for path in state.snapshots.root.glob("*.json")}
    listing = request(state)[1]
    assert len(listing["snapshots"]) == 2 and len(listing["captures"]) == 1
    assert "SECRET_SOURCE" not in json.dumps(listing)
    assert state.discovery.clients["app"].calls == []


def test_missing_saved_id_never_falls_back_to_live_evidence(state):
    status, result, _ = request(state, "/api/capture-tools/project", "POST", {"snapshotId": str(uuid.uuid4())})
    assert status == 404
    assert "no live data" in result["error"]
    assert state.discovery.clients["app"].calls == []
    assert not state.captures.root.exists()


def test_http_routes_use_injected_private_stores_security_headers_and_no_cors(state, tmp_path):
    viewer = server.make_server(0, "http://upstream.invalid:7778", str(state.root),
                                snapshot_dir=tmp_path / "wire-pins", capture_dir=tmp_path / "wire-captures")
    thread = threading.Thread(target=viewer.serve_forever, daemon=True)
    thread.start()
    port = viewer.server_address[1]
    def wire(method, path, doc=None, bad_origin=False):
        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
        headers = {"Host": f"127.0.0.1:{port}"}
        body = None
        if doc is not None:
            body = json.dumps(doc)
            headers.update({"Content-Type": "application/json",
                            "Origin": "https://attacker.invalid" if bad_origin else f"http://127.0.0.1:{port}"})
        try:
            conn.request(method, path, body, headers)
            response = conn.getresponse()
            assert response.getheader("Cache-Control") == "no-store"
            assert response.getheader("X-Content-Type-Options") == "nosniff"
            assert response.getheader("Access-Control-Allow-Origin") is None
            return response.status, json.load(response)
        finally:
            conn.close()
    try:
        saved = viewer.RequestHandlerClass.snapshots.save(evidence())
        assert wire("GET", "/api/capture-tools")[0] == 200
        assert wire("POST", "/api/capture-tools/project", {"snapshotId": saved["id"]}, bad_origin=True)[0] == 403
        status, projected = wire("POST", "/api/capture-tools/project", {"snapshotId": saved["id"]})
        assert status == 200
        assert (tmp_path / "wire-captures" / (projected["capture"]["captureId"] + ".json")).is_file()
        assert wire("POST", "/api/capture-tools/search", {"query": "OrderService"})[1]["returned"] >= 2
    finally:
        viewer.shutdown()
        viewer.server_close()
        thread.join()
