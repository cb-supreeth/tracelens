"""Synthetic local-only dependency UI QA; no application traffic or secrets."""
from ui_fixture import fixture_rows, fake_api, FEATURE_OK_TRACE
from tracelens_cli.services import Service
from tracelens_cli.server import make_server


def main():
    app = fixture_rows()["cb-app"][0]
    # Deliberately no EMS receiver: the caller's HTTP observation must remain.
    app["httpCalls"].append({"spanId": "eeeeeeeeeeeeeeee", "httpMethod": "POST", "path": "/items/search",
        "peer": "plans-fixture:8081", "httpStatus": 200, "status": "COMPLETED", "propagationStatus": "NOT_ALLOWLISTED"})
    for index, (system, direction, target, operation, status) in enumerate([
        ("kafka", "publish", "billing-events", "send", "SUBMITTED"),
        ("sqs", "publish", "https://sqs.example.invalid/000000000000/usage", "sendMessage", "SUBMITTED"),
        ("queueman", "consume", "usage", "processMessage", "OBSERVED")
    ], 4):
        app["events"].append({"type": "EXTERNAL_CALL", "seq": index, "thread": "fixture-http-1", "attributes": {
            "boundaryKind": "messaging", "system": system, "direction": direction, "destination": target,
            "operation": operation, "status": status, "evidenceKind": "client-boundary"}})
    app["events"].append({"type": "EXTERNAL_CALL", "seq": 7, "thread": "fixture-http-1",
        "attributes": {"system": "DynamoRepository", "detail": "find"}})
    app["eventCount"] = len(app["events"])
    with fake_api("cb-app", [app]) as (url, _):
        service = Service("cb-app", url, dependency_aliases={"cb-ems-fixture:8082": "cb-ems", "plans-fixture:8081": "cb-plans"})
        viewer = make_server(0, url, None, [service])
        print(f"http://127.0.0.1:{viewer.server_port}/#trace={FEATURE_OK_TRACE}", flush=True)
        try:
            viewer.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            viewer.server_close()


if __name__ == "__main__":
    main()
