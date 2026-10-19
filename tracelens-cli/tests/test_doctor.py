import copy
import io
import json
import urllib.error

import pytest

from tracelens_cli import doctor
from tracelens_cli.client import DebugApiError
from tracelens_cli.services import Federation, Service


SUMMARY = {
    "agentActive": True, "recordingEnabled": True, "instrumentedClasses": 18,
    "failedClasses": 0, "traceCount": 2, "totalEvents": 10,
    "maxTrackedRequests": 200, "maxTrackedEvents": 500000,
    "serviceInstanceId": "instance-a", "bridge": {"sinkRegistered": True, "emitted": 12,
    "droppedNoTrace": 8, "droppedNoSink": 3},
}
METADATA = {"agentActive": True, "packages": ["com.example"], "idleTtlMinutes": 60,
            "lineCaptureEnabled": True, "requireLabels": False, "serviceInstanceId": "instance-a"}


class FakeClient:
    def __init__(self, summary=None, metadata=None):
        self.values = {"summary": copy.deepcopy(SUMMARY if summary is None else summary),
                       "metadata": copy.deepcopy(METADATA if metadata is None else metadata)}
        self.calls = []

    def _get(self, name):
        self.calls.append(name)
        result = self.values[name]
        if isinstance(result, Exception):
            raise result
        return result

    def summary(self):
        return self._get("summary")

    def metadata(self):
        return self._get("metadata")

    def __getattr__(self, name):
        raise AssertionError("Doctor must not call " + name)


def run_fake(tmp_path, client=None, **service_fields):
    service = Service("app", "http://localhost:7778", **({"source_root": str(tmp_path)} | service_fields))
    discovery = Federation([service])
    discovery.clients["app"] = client or FakeClient()
    return doctor.run_doctor(discovery), discovery.clients["app"]


def checks(report):
    guidance = {step["id"]: step["instruction"] for step in report["nextSteps"]}
    return {c["id"]: c | ({"fix": guidance[c["fixId"]]} if c.get("fixId") else {})
            for c in report["services"][0]["checks"]}


def test_healthy_reports_are_read_only_and_do_not_imply_complete_coverage(tmp_path):
    report, client = run_fake(tmp_path)
    assert report["ready"] is True
    assert report["partial"] is False
    assert report["status"] == "ready"
    assert report["readOnly"] is True
    assert client.calls == ["summary", "metadata"]
    assert "does not prove" in checks(report)["agent"]["message"]
    assert "not a performance benchmark" in report["caveat"]
    assert "build/source correspondence is not verified" in checks(report)["source"]["message"]
    # Startup/untraced drop counters are facts, not proof of a current failure.
    assert report["services"][0]["facts"]["droppedNoSink"] == 3
    assert report["nextSteps"] == []


def test_one_offline_service_does_not_hide_healthy_service(tmp_path):
    discovery = Federation([Service("app", "http://localhost:7778", str(tmp_path)),
                            Service("ems", "http://localhost:7779", str(tmp_path))])
    healthy = FakeClient()
    offline = FakeClient(DebugApiError("secret server body", 503), OSError("secret network details"))
    discovery.clients = {"app": healthy, "ems": offline}
    report = doctor.run_doctor(discovery)
    assert report["ready"] is False
    assert report["partial"] is True
    assert report["status"] == "attention"
    assert [s["ready"] for s in report["services"]] == [True, False]
    assert "secret" not in json.dumps(report)
    assert offline.calls == healthy.calls == ["summary", "metadata"]
    assert any("7778" in step["instruction"] and "7779" in step["instruction"] for step in report["nextSteps"])


def test_no_agent_disabled_recording_and_missing_sink_have_actionable_fixes(tmp_path):
    summary = copy.deepcopy(SUMMARY)
    summary.update(agentActive=False, recordingEnabled=False)
    summary["bridge"]["sinkRegistered"] = False
    metadata = dict(METADATA, agentActive=False)
    report, _ = run_fake(tmp_path, FakeClient(summary, metadata))
    result = checks(report)
    assert report["ready"] is False and report["partial"] is False
    assert all(result[key]["status"] == "fail" for key in ("agent", "recording", "sink"))
    assert "-javaagent" in result["agent"]["fix"] and "restart" in result["agent"]["fix"]
    assert "never changes" in result["recording"]["fix"]


def test_legacy_missing_metadata_and_fields_are_unknown_not_zeros(tmp_path):
    report, _ = run_fake(tmp_path, FakeClient({"traceCount": 3}, DebugApiError("old API", 404)))
    assert report["partial"] is True and report["ready"] is False
    facts = report["services"][0]["facts"]
    assert facts["traceCount"] == 3
    assert facts["totalEvents"] is None
    assert facts["instrumentedClasses"] is None
    assert facts["agentActive"] is None
    assert checks(report)["metadata"]["status"] == "unknown"
    assert "older adapter" in checks(report)["metadata"]["fix"]


@pytest.mark.parametrize("bad", [True, "12", -1, 1.1, float("nan"), 1 << 70, [], {}])
def test_malformed_counts_remain_unknown(tmp_path, bad):
    summary = dict(SUMMARY, traceCount=bad, instrumentedClasses=bad)
    report, _ = run_fake(tmp_path, FakeClient(summary))
    assert report["services"][0]["facts"]["traceCount"] is None
    assert checks(report)["captures"]["status"] == "unknown"
    assert report["partial"] is True
    json.dumps(report, allow_nan=False)


@pytest.mark.parametrize("bad", ["true", 1, [], {}, "false"])
def test_malformed_booleans_not_coerced_to_true(tmp_path, bad):
    report, _ = run_fake(tmp_path, FakeClient(dict(SUMMARY, recordingEnabled=bad)))
    assert checks(report)["recording"]["status"] == "unknown"
    assert report["ready"] is False


def test_fresh_store_still_capture_ready_with_reproduction_guidance(tmp_path):
    report, _ = run_fake(tmp_path, FakeClient(dict(SUMMARY, traceCount=0, totalEvents=0)))
    assert report["ready"] is True
    assert report["status"] == "attention"
    assert report["partial"] is False
    assert checks(report)["captures"]["status"] == "warn"
    assert "line-number debug" in checks(report)["captures"]["fix"]


@pytest.mark.parametrize("changed", [{"instrumentedClasses": 0}, {"failedClasses": 2}])
def test_zero_classes_or_failed_transformation_does_not_report_ready(tmp_path, changed):
    report, _ = run_fake(tmp_path, FakeClient(SUMMARY | changed))
    assert report["ready"] is False
    assert checks(report)["instrumentation"]["status"] == "warn"
    assert "have not loaded" in checks(report)["instrumentation"]["fix"]


def test_retention_limits_warn_without_changing_or_exporting_traces(tmp_path):
    report, client = run_fake(tmp_path, FakeClient(SUMMARY | {"traceCount": 200, "totalEvents": 500000}))
    assert checks(report)["request_retention"]["status"] == "warn"
    assert checks(report)["event_retention"]["status"] == "warn"
    assert "reading a trace does not keep it alive" in checks(report)["retention_ttl"]["message"]
    assert client.calls == ["summary", "metadata"]


def test_source_root_optional_missing_file_or_unreadable(tmp_path, monkeypatch):
    unconfigured, _ = run_fake(tmp_path, source_root=None)
    assert checks(unconfigured)["source"]["status"] == "warn"
    assert unconfigured["ready"] is True
    missing, _ = run_fake(tmp_path, source_root=str(tmp_path / "missing"))
    assert checks(missing)["source"]["status"] == "fail"
    file = tmp_path / "file"
    file.write_text("not a directory")
    not_directory, _ = run_fake(tmp_path, source_root=str(file))
    assert checks(not_directory)["source"]["status"] == "fail"
    monkeypatch.setattr(doctor.os, "access", lambda *args: False)
    unreadable, _ = run_fake(tmp_path)
    assert checks(unreadable)["source"]["status"] == "fail"


def test_empty_package_scope_is_reported_without_assuming_global_capture(tmp_path):
    report, _ = run_fake(tmp_path, FakeClient(metadata=METADATA | {"packages": [], "lineCaptureEnabled": False}))
    assert report["ready"] is False
    assert checks(report)["scope"]["status"] == "warn"


def test_standalone_summary_and_metadata_shapes_and_legacy_scope_fallback(tmp_path):
    summary = SUMMARY | {"lineCaptureEnabled": True, "mode": "standalone"}
    metadata = METADATA | {"mode": "standalone", "modules": [], "excludedPackages": []}
    report, _ = run_fake(tmp_path, FakeClient(summary, metadata))
    assert checks(report)["scope"]["details"] == {"lineCaptureEnabled": True, "packageCount": 1}
    metadata.pop("lineCaptureEnabled")
    summary_fallback, _ = run_fake(tmp_path, FakeClient(summary, metadata))
    assert checks(summary_fallback)["scope"]["status"] == "pass"
    legacy_summary = dict(summary)
    legacy_summary.pop("lineCaptureEnabled")
    packages_fallback, _ = run_fake(tmp_path, FakeClient(legacy_summary, metadata))
    assert checks(packages_fallback)["scope"]["status"] == "pass"


def test_malformed_agent_and_line_flags_not_hidden_by_fallbacks(tmp_path):
    report, _ = run_fake(tmp_path, FakeClient(SUMMARY | {"agentActive": "false", "lineCaptureEnabled": True},
                                            METADATA | {"lineCaptureEnabled": "true"}))
    assert checks(report)["agent"]["status"] == "unknown"
    assert checks(report)["scope"]["status"] == "unknown"


def test_payloads_config_values_and_exception_text_are_not_reflected(tmp_path):
    summary = SUMMARY | {"password": "secret", "scope": "secret", "applicationVersion": "secret"}
    metadata = METADATA | {"httpIdentityHooks": ["secret"], "packages": ["secret\nvalue"]}
    report, _ = run_fake(tmp_path, FakeClient(summary, metadata))
    assert "secret" not in json.dumps(report)
    assert checks(report)["scope"]["details"]["packageCount"] is None


def test_changed_instance_and_conflicting_agent_reports_require_retry(tmp_path):
    report, _ = run_fake(tmp_path, FakeClient(metadata=METADATA | {"serviceInstanceId": "other", "agentActive": False}))
    assert report["ready"] is False and report["partial"] is True
    assert checks(report)["instance"]["status"] == "unknown"
    assert checks(report)["agent"]["status"] == "unknown"
    assert "other" not in json.dumps(report)


def test_require_labels_is_explained_without_enabling_recording(tmp_path):
    report, client = run_fake(tmp_path, FakeClient(metadata=METADATA | {"requireLabels": True}))
    assert "tracelens-id" in checks(report)["http_labels"]["fix"]
    assert client.calls == ["summary", "metadata"]


@pytest.mark.parametrize("doc", [[], "text", 1, {}, {"message": "Not the debug API"}])
def test_malformed_endpoint_responses_are_graceful(tmp_path, doc):
    report, _ = run_fake(tmp_path, FakeClient(doc, doc))
    assert report["status"] == "unavailable"
    assert report["partial"] is True and report["ready"] is False
    assert checks(report)["debug_api"]["details"]["kind"] == "malformed"


def test_access_denied_does_not_recommend_disabling_security(tmp_path):
    report, _ = run_fake(tmp_path, FakeClient(DebugApiError("private auth body", 403)))
    assert "do not disable authentication" in checks(report)["debug_api"]["fix"]
    assert "private auth body" not in json.dumps(report)


class FakeResponse(io.BytesIO):
    def __init__(self, body):
        super().__init__(body)
        self.sizes = []

    def read(self, size=-1):
        self.sizes.append(size)
        return super().read(size)


def test_real_client_transport_is_bounded_get_only_and_disables_redirects(tmp_path, monkeypatch):
    calls, responses, handlers = [], [], []

    class Opener:
        def open(self, req, timeout):
            calls.append((req.get_method(), req.full_url, timeout))
            value = SUMMARY if req.full_url.endswith("/summary") else METADATA
            response = FakeResponse(json.dumps(value).encode())
            responses.append(response)
            return response

    def build_opener(handler):
        handlers.append(handler)
        return Opener()

    monkeypatch.setattr(doctor.urllib.request, "build_opener", build_opener)
    discovery = Federation([Service("app", "http://localhost:7778", str(tmp_path))])
    discovery.clients["app"].timeout = 300
    report = doctor.run_doctor(discovery)
    assert report["ready"] is True
    assert calls == [("GET", "http://localhost:7778/tracelens/summary", 5.0),
                     ("GET", "http://localhost:7778/tracelens/metadata", 5.0)]
    assert all(r.sizes == [doctor.MAX_RESPONSE_BYTES + 1] for r in responses)
    assert all(h.redirect_request(None, None, 302, None, None, "http://other") is None for h in handlers)


def test_oversized_response_rejected_and_not_reflected(tmp_path, monkeypatch):
    class Opener:
        def open(self, req, timeout):
            return FakeResponse(b"x" * (doctor.MAX_RESPONSE_BYTES + 1))
    monkeypatch.setattr(doctor.urllib.request, "build_opener", lambda *args: Opener())
    report = doctor.run_doctor(Federation([Service("app", "http://localhost:7778", str(tmp_path))]))
    assert report["status"] == "unavailable"
    assert len(json.dumps(report)) < 14000


def test_endpoint_display_never_reflects_credentials_query_or_path():
    assert doctor._endpoint("http://user:secret@localhost:7778/token?apiKey=secret#secret") == "http://localhost:7778"
    assert doctor._endpoint("http://[::1]:7778") == "http://[::1]:7778"
    assert doctor._endpoint("http://localhost:bad") == "configured debug endpoint"


def test_no_files_or_trace_data_are_written(tmp_path):
    before = list(tmp_path.iterdir())
    run_fake(tmp_path)
    assert list(tmp_path.iterdir()) == before


@pytest.mark.parametrize("offline", [True, False])
def test_32_service_report_is_bounded_and_keeps_all_service_statuses(tmp_path, offline):
    services = [Service("service-" + str(i) + "x" * 50, "http://" + "h" * 200 + ":" + str(10000 + i))
                for i in range(32)]
    discovery = Federation(services)
    for service in services:
        discovery.clients[service.name] = (FakeClient(DebugApiError("offline"), DebugApiError("offline"))
                                           if offline else FakeClient(SUMMARY | {"traceCount": 0, "totalEvents": 0},
                                                                      METADATA | {"requireLabels": True}))
    report = doctor.run_doctor(discovery)
    encoded = json.dumps(report, ensure_ascii=True, allow_nan=False)
    assert len(encoded.encode("utf-8")) <= 96 * 1024
    assert len(report["services"]) == 32
    assert {s["service"] for s in report["services"]} == set(discovery.services)
    assert all("ready" in s and "status" in s and "checks" in s for s in report["services"])
    instructions = {step["id"] for step in report["nextSteps"]}
    assert all(check["fixId"] in instructions for s in report["services"] for check in s["checks"] if "fixId" in check)
    assert "omissions" in report
    # Simulate the actual MCP model mirrored in compact text and structuredContent.
    envelope = {"content": [{"type": "text", "text": json.dumps(report, ensure_ascii=True, separators=(",", ":"))}],
                "structuredContent": report}
    assert len(json.dumps(envelope, ensure_ascii=True).encode("utf-8")) < 256 * 1024


def test_report_budget_elision_is_explicit_and_preserves_probe_details():
    services = [Service(("service-" + str(i)).ljust(64, "x"), "http://" + "h" * 243 + ":" + str(10000 + i))
                for i in range(32)]
    discovery = Federation(services)
    discovery.clients = {s.name: FakeClient(DebugApiError("offline", 503), DebugApiError("offline", 503))
                         for s in services}
    report = doctor.run_doctor(discovery)
    assert len(json.dumps(report, ensure_ascii=True).encode("utf-8")) <= doctor.MAX_REPORT_BYTES
    assert report["omissions"]["checkDetails"] > 0
    assert all(s["checks"][0]["details"]["httpStatus"] == 503 for s in report["services"])
    assert all(s["facts"]["traceCount"] is None for s in report["services"])
