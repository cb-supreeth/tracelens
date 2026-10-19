"""Loopback-only manual Errors UI fixture. No business application is contacted.

Run from tracelens-cli: .venv/bin/python -u tests/errors_ui_fixture.py [--single]
Uses the real viewer and grouping endpoint with synthetic summary/source data.
"""
from __future__ import annotations

import argparse
import copy
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from tracelens_cli.server import make_server
from tracelens_cli.services import Service
from ui_fixture import FEATURE_ERROR_TRACE, _segment, fake_api, fixture_rows


def error_rows():
    rows = fixture_rows()
    now = int(time.time() * 1000)
    # A second independent request with the same downstream error signature.
    for service in ("cb-app", "cb-ems"):
        original = next(row for row in rows[service] if row["traceId"] == FEATURE_ERROR_TRACE)
        row = copy.deepcopy(original)
        row["traceId"] = "66666666666666666666666666666666"
        row["segmentId"] = ("aaaaaaaa" if service == "cb-app" else "bbbbbbbb") + "-6666-4666-8666-666666666666"
        for call in row["httpCalls"]:
            call["traceId"] = row["traceId"]
        rows[service].append(row)
    timeout = _segment("cb-app", "77777777777777777777777777777777",
                       "aaaaaaaa-7777-4777-8777-777777777777", "7777777777777771", now,
                       path="/api/v2/slow-request", status=504, duration=1000)
    timeout["asyncTimeout"] = "true"
    timeout["attributes"]["asyncTimeout"] = "true"
    rows["cb-app"].append(timeout)
    handled = _segment("cb-app", "88888888888888888888888888888888",
                       "aaaaaaaa-8888-4888-8888-888888888888", "8888888888888881", now,
                       path="/api/v2/handled-exception", status=200)
    handled["filterException"] = "java.lang.IllegalArgumentException"
    handled["attributes"]["filterException"] = handled["filterException"]
    rows["cb-app"].append(handled)  # Boundary evidence alone must not enter Errors.
    job = _segment("cb-ems", "99999999999999999999999999999999",
                   "bbbbbbbb-9999-4999-8999-999999999999", "9999999999999991", now,
                   channel="SCHEDULER", label="scheduled:failed-fixture-job")
    job["status"] = "FAILED"
    rows["cb-ems"].append(job)
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--single", action="store_true")
    args = parser.parse_args()
    rows = error_rows()
    with fake_api("cb-app", rows["cb-app"]) as (app_url, _), fake_api("cb-ems", rows["cb-ems"]) as (ems_url, _):
        services = None if args.single else [Service("cb-app", app_url), Service("cb-ems", ems_url)]
        viewer = make_server(args.port, app_url, None, services)
        print(f"http://127.0.0.1:{viewer.server_port}/", flush=True)
        try:
            viewer.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            viewer.server_close()


if __name__ == "__main__":
    main()
