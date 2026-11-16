"""JDBC bindings stay bounded in local evidence and out of compact/MCP projections."""

import copy
import json

import pytest

from tracelens_cli.dependencies import boundary_summary, sanitize_summary, without_sql
from tracelens_cli.execution import build_segment_execution
from tracelens_cli.mcp_server import TraceLensMcpServer
from tracelens_cli.services import join_trace
from tracelens_cli.visualization import build_visualization
from test_captures import capture, segment as capture_segment
from test_dependencies import ReadOnlyClient, TRACE_ID, segment


def parameter(index=1, kind="String", value="private-binding", status="captured"):
    return {"index": index, "type": kind, "value": value, "status": status}


def jdbc(**attrs):
    return {"seq": 1, "type": "EXTERNAL_CALL", "thread": "worker-1", "nanoTime": 1,
            "className": "com.example.Repository", "methodName": "find", "line": 42,
            "attributes": {"boundaryKind": "database", "system": "postgresql", "direction": "outbound",
                           "operation": "executeQuery", "evidenceKind": "jdbc-execution", "status": "OBSERVED",
                           "sql": "SELECT id FROM features WHERE name = ?", "sqlSource": "prepared", "durationMs": "12.25",
                           "sqlParametersStatus": "captured", "sqlParameters": json.dumps([parameter()]), **attrs}}


def test_bound_parameters_preserve_local_meaning_and_round_trip_canonical_rows():
    expected = [parameter(value="O'Reilly ? <script>\n"), parameter(2, "Null", None, "null"),
                parameter(3, "BinaryStream", None, "unsupported"), parameter(4, value="")]
    event = jdbc(sqlParameters=json.dumps(expected))
    result = boundary_summary(event)
    assert result["sqlParameters"] == expected
    assert result["sql"] == event["attributes"]["sql"]
    assert result["durationMs"] == 12.25
    assert result["sqlParametersStatus"] == "captured" and result["sqlParametersTruncated"] is False
    assert sanitize_summary(result) == {**result, "destinationName": None}
    assert boundary_summary(jdbc(sqlParameters=expected)) == result
    assert boundary_summary(jdbc(sqlParameters=expected, sqlParametersStatus="unavailable"))["sqlParameters"] == expected
    assert boundary_summary(jdbc(sqlParameters=expected, sqlParametersStatus="not-applicable"))["sqlParameters"] is None


@pytest.mark.parametrize("invalid", [
    {"private": "payload"}, [1], [parameter(0)], [parameter(129)], [parameter(kind="bad-type")],
    [parameter(value="private-binding", status="unsupported")], [parameter(kind="Null", status="null")],
    [parameter(value=None)], [parameter(status="invalid")], [parameter(), parameter()],
    [parameter(value="x" * 513)], [parameter(i, value="x" * 512) for i in range(1, 21)],
    [parameter(i) for i in range(1, 130)], "not-json",
    '[{"index":1,"index":2,"type":"String","value":"private-binding","status":"captured"}]',
])
def test_invalid_bindings_fail_closed_with_explicit_incompleteness(invalid):
    result = boundary_summary(jdbc(sqlParameters=invalid))
    assert result["sqlParameters"] is None and result["sqlParameterSets"] is None
    assert result["sqlParametersStatus"] == "unavailable" and result["sqlParametersTruncated"] is True
    assert "private-binding" not in json.dumps(result)


def test_batch_sets_and_cap_markers_do_not_become_more_queries():
    expected = [[parameter(value="first")], [parameter(value="second")]]
    result = boundary_summary(jdbc(operation="executeBatch", sqlParametersStatus="batch",
                                  sqlParameters=None, sqlParameterSets=json.dumps(expected), sqlParameterSetsOmitted="3"))
    assert result["sqlParameterSets"] == expected and result["sqlParameters"] is None
    assert result["sqlParametersTruncated"] and result["sqlParameterSetsOmitted"] == 3
    assert sanitize_summary(result) == {**result, "destinationName": None}
    assert boundary_summary(jdbc(sqlParametersStatus="unavailable", sqlParameters=None, sqlParameterSets=expected))["sqlParameterSets"] == expected
    excess = boundary_summary(jdbc(sqlParametersStatus="batch", sqlParameters=None, sqlParameterSets=expected * 6))
    assert excess["sqlParameterSets"] is None and excess["sqlParametersStatus"] == "unavailable"
    marker = boundary_summary(jdbc(evidenceKind="jdbc-query-limit", operation="query-limit", status="TRUNCATED", queryLimit="100"))
    assert marker["queryLimit"] == 100 and "sqlParameters" not in marker and "sql" not in marker
    assert "sqlParameters" not in boundary_summary(jdbc(boundaryKind="cache"))


@pytest.mark.parametrize("status", ["captured", "batch", "unavailable", "not-applicable", "unknown"])
def test_contradictory_binding_metadata_fails_closed(status):
    result = boundary_summary(jdbc(sqlParametersStatus=status, sqlParameterSets=[[parameter()]]))
    assert result["sqlParameters"] is None and result["sqlParameterSets"] is None
    assert result["sqlParametersStatus"] == "unavailable" and result["sqlParametersTruncated"] is True


@pytest.mark.parametrize("omitted", [-1, "1.5", "2147483648", "invalid", True])
def test_invalid_omitted_count_marks_parameters_incomplete(omitted):
    result = boundary_summary(jdbc(sqlParameterSetsOmitted=omitted))
    assert result["sqlParameters"] is None and result["sqlParametersTruncated"] is True


def test_parameter_control_characters_removed_and_empty_string_kept():
    result = boundary_summary(jdbc(sqlParameters=[parameter(value="a\u202eb\x1bc", status="truncated")]))
    assert result["sqlParameters"][0]["value"] == "a b c"


@pytest.mark.parametrize("batch", [False, True])
@pytest.mark.parametrize("serialized", [False, True])
def test_display_sanitization_marks_single_and_batch_bindings_incomplete(batch, serialized):
    source = [parameter(value="a\u202eb\x1bc"), parameter(2, value=""), parameter(3, value="line\n\r\t\U0001f600"),
              parameter(4, "Null", None, "null"), parameter(5, "BinaryStream", None, "unsupported")]
    expected = [parameter(value="a b c", status="truncated"), *source[1:]]
    field = "sqlParameterSets" if batch else "sqlParameters"
    bindings = [source] if batch else source
    event = jdbc(**{"sqlParametersStatus": "batch" if batch else "captured", "sqlParameters": None,
                    field: json.dumps(bindings) if serialized else bindings})
    result = boundary_summary(event)
    assert result[field] == ([expected] if batch else expected)
    assert result["sqlParametersTruncated"] is True
    assert sanitize_summary(result) == {**result, "destinationName": None}
    assert source[0]["status"] == "captured"


def test_display_sanitization_marks_sql_incomplete_without_changing_safe_formatting():
    result = boundary_summary(jdbc(sql="SELECT '\u202e\x1b'\n\r\tFROM t"))
    assert result["sql"] == "SELECT '  '\n\r\tFROM t"
    assert result["sqlTruncated"] is True
    assert sanitize_summary(result) == {**result, "destinationName": None}
    for exact in ("SELECT '\U0001f600'\n\r\tFROM t", ""):
        result = boundary_summary(jdbc(sql=exact))
        assert result["sqlTruncated"] is False
        assert result["sql"] == (exact or None)


def test_parameters_survive_federation_graph_and_execution_but_not_mcp_or_compact():
    row = segment([jdbc()])
    original = copy.deepcopy(row)
    journey = join_trace(TRACE_ID, [row], [])
    graph = build_visualization(journey)
    boundary = next(node for node in graph["nodes"] if node["kind"] == "dependency-call")
    assert boundary["sqlParameters"][0]["value"] == "private-binding"
    assert boundary["eventSeq"] == 1
    execution = build_segment_execution(row)
    assert execution["nodes"][0]["boundary"]["sqlParameters"] == boundary["sqlParameters"]
    assert row == original
    detached = without_sql({"raw": row, "journey": journey, "graph": graph, "execution": execution})
    assert "private-binding" not in json.dumps(detached) and "SELECT id" not in json.dumps(detached)
    assert row == original
    server = TraceLensMcpServer("http://unused", None)
    server.discovery.clients = {"default": ReadOnlyClient(row)}
    for result in (server.tool_get_distributed_trace(TRACE_ID), server.tool_get_trace_visualization(TRACE_ID)):
        encoded = json.dumps(result)
        assert "jdbc-execution" in encoded and "private-binding" not in encoded and "SELECT id" not in encoded
    compact = capture(capture_segment(events=[jdbc()], eventCount=1))
    assert "private-binding" not in json.dumps(compact) and "SELECT id" not in json.dumps(compact)
