#!/usr/bin/env python3
"""Aggregate atomic, sanitized Admin UI collector stage receipts."""

# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors.

from __future__ import annotations

import json
import os
import platform
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from admin_ui_trace_ipc import AUTH, AUTH_ALLOWED, DESTINATIONS, STAGES

MEASUREMENT_SCOPE = {
    "id": "admin-ui-admitted-build-repository-owned-collector-fetches-v1",
    "meaning": "Counts repo-owned collector HTTP header-send attempts in listed stages, including retries and redirects; not a billing, quota, or full-workflow request count.",
    "coveredClientStages": [
        {"stage": stage, "client": client, "authAliases": sorted(AUTH_ALLOWED[stage])}
        for stage, clients in STAGES.items() for client in sorted(clients)
    ],
    "excludedTraffic": [
        "deploy-source admission and source-recovery GitHub API calls",
        "git fetch and other Git transport",
        "GitHub Actions checkout, setup, cache, and artifact traffic",
        "npm registry and cache traffic",
        "Docker, BuildKit, and ECR registry traffic",
        "AWS OIDC, KMS, cosign, and attestation signing traffic",
        "AWS CLI Cost Explorer traffic",
        "GitOps pull-request action and API traffic",
    ],
}

REASONS = {
    "malformed_lifecycle", "invalid_lifecycle", "unmatched_lifecycle",
    "trace_limit_exceeded", "ipc_failure", "missing_terminal_frame",
    "bounded_eof_timeout", "missing_client", "no_send_observed",
    "trace_parse_failure", "pre_send_failure", "client_start_failure",
    "hook_unavailable", "collector_not_configured", "collector_failed",
}


def client_version(command: str, pattern: str) -> str:
    try:
        result = subprocess.run(
            [command, "--version"], capture_output=True, text=True, timeout=2, check=False
        )
        first = result.stdout.splitlines()[0]
        match = re.search(pattern, first)
        return match.group(1) if match else "unknown"
    except Exception:
        return "unknown"


def read_stage(trace_dir: Path, stage: str) -> tuple[dict | None, bool]:
    path = trace_dir / f"admin-ui-stage-{stage}.json"
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
        if (
            not isinstance(payload, dict)
            or payload.get("schemaVersion") != 1
            or payload.get("stage") != stage
            or payload.get("authAlias") not in AUTH_ALLOWED[stage]
            or type(payload.get("complete")) is not bool
            or payload.get("reasonEnum") not in (None, *REASONS)
            or not isinstance(payload.get("records"), list)
        ):
            return None, True
        records = payload["records"]
        seen: set[tuple[str, str]] = set()
        for record in records:
            if (
                not isinstance(record, dict)
                or record.get("client") not in STAGES[stage]
                or record.get("destinationClass") not in DESTINATIONS
                or type(record.get("numericSendCount")) is not int
                or record["numericSendCount"] < 0
            ):
                return None, True
            key = (record["client"], record["destinationClass"])
            if key in seen:
                return None, True
            seen.add(key)
        if payload["complete"] and (
            payload["reasonEnum"] is not None
            or not records
            or {row["client"] for row in records} != STAGES[stage]
            or any(sum(
                row["numericSendCount"] for row in records if row["client"] == client
            ) == 0 for client in STAGES[stage])
        ):
            return None, True
        return payload, False
    except FileNotFoundError:
        return None, False
    except Exception:
        return None, True


def build_report() -> dict:
    trace_dir_value = os.environ.get("ADMIN_UI_TRACE_DIR", "")
    trace_dir = Path(trace_dir_value) if trace_dir_value else Path("/__admin_ui_trace_missing__")
    statuses = {
        name: os.environ.get(f"TRACE_STATUS_{name.upper()}", "not_run")
        for name in STAGES
    }
    app_ro_configured = os.environ.get("TRACE_APP_RO_CONFIGURED") == "true"
    pact_broker_configured = os.environ.get("TRACE_PACT_BROKER_CONFIGURED") == "true"
    fixture_status = os.environ.get("ADMIN_UI_TRACE_TESTS", "not_run")
    records: list[dict] = []
    malformed = False
    applicable = []
    for stage, _clients in STAGES.items():
        status = statuses[stage]
        optional_absent = (
            (stage == "customer_ti" and not app_ro_configured and status == "skipped")
            or (stage == "quality_report" and not pact_broker_configured and status == "skipped")
        )
        if optional_absent:
            records.append({
                "stage": stage,
                "client": "node_fetch" if stage == "quality_report" else "curl",
                "fixedAuthAlias": AUTH[stage] if stage != "quality_report" or pact_broker_configured else "none",
                "destinationClass": "none",
                "numericSendCount": 0,
                "complete": False,
                "reasonEnum": "optional_not_configured",
            })
            continue
        applicable.append(stage)
        if status != "success":
            envelope, bad = read_stage(trace_dir, stage)
            malformed |= bad
            if envelope is not None:
                for row in envelope["records"]:
                    records.append({
                        "stage": stage,
                        "client": row["client"],
                        "fixedAuthAlias": envelope["authAlias"],
                        "destinationClass": row["destinationClass"],
                        "numericSendCount": row["numericSendCount"],
                        "complete": False,
                        "reasonEnum": "stage_not_successful",
                    })
            records.append({
                "stage": stage,
                "client": "stage",
                "fixedAuthAlias": AUTH[stage] if stage != "quality_report" or pact_broker_configured else "none",
                "destinationClass": "none",
                "numericSendCount": 0,
                "complete": False,
                "reasonEnum": "stage_not_successful",
            })
            continue
        envelope, bad = read_stage(trace_dir, stage)
        malformed |= bad
        if envelope is None:
            records.append({
                "stage": stage,
                "client": "stage",
                "fixedAuthAlias": AUTH[stage] if stage != "quality_report" or pact_broker_configured else "none",
                "destinationClass": "none",
                "numericSendCount": 0,
                "complete": False,
                "reasonEnum": "malformed_receipt" if bad else "missing_trace",
            })
            continue
        for row in envelope["records"]:
            records.append({
                "stage": stage,
                "client": row["client"],
                "fixedAuthAlias": envelope["authAlias"],
                "destinationClass": row["destinationClass"],
                "numericSendCount": row["numericSendCount"],
                "complete": envelope["complete"],
                "reasonEnum": envelope["reasonEnum"],
            })
        if not envelope["complete"]:
            records.append({
                "stage": stage,
                "client": "stage",
                "fixedAuthAlias": AUTH[stage],
                "destinationClass": "none",
                "numericSendCount": 0,
                "complete": False,
                "reasonEnum": envelope["reasonEnum"] or "trace_parse_failure",
            })

    if malformed:
        records.append({
            "stage": "trace",
            "client": "unknown",
            "fixedAuthAlias": "unknown",
            "destinationClass": "unknown",
            "numericSendCount": 0,
            "complete": False,
            "reasonEnum": "malformed_receipt",
        })
    if fixture_status != "passed":
        records.append({
            "stage": "trace_tests",
            "client": "local_fixture",
            "fixedAuthAlias": "none",
            "destinationClass": "none",
            "numericSendCount": 0,
            "complete": False,
            "reasonEnum": "trace_fixture_failed",
        })
    records.sort(key=lambda row: (
        row["stage"], row["client"], row["fixedAuthAlias"], row["destinationClass"]
    ))
    complete = (
        bool(applicable)
        and not malformed
        and fixture_status == "passed"
        and all(row["complete"] for row in records if row["reasonEnum"] != "optional_not_configured")
    )
    return {
        "schemaVersion": 1,
        "measurementScope": MEASUREMENT_SCOPE,
        "runId": os.environ.get("GITHUB_RUN_ID", ""),
        "attempt": os.environ.get("GITHUB_RUN_ATTEMPT", ""),
        "headSha": os.environ.get("GITHUB_SHA", ""),
        "tracerRevision": os.environ.get("GITHUB_SHA", ""),
        "clientVersions": {
            "curl": client_version("curl", r"curl\s+(\S+)"),
            "python": platform.python_version(),
            "node": client_version("node", r"v(\S+)"),
        },
        "complete": complete,
        "localFixtureStatus": fixture_status,
        "records": records,
    }


def main() -> int:
    report = build_report()
    text = json.dumps(report, indent=2, sort_keys=True) + "\n"
    trace_dir = os.environ.get("ADMIN_UI_TRACE_DIR", "")
    try:
        if trace_dir:
            path = Path(trace_dir) / "admin-ui-api-request-trace.json"
            temp_path = path.with_suffix(".tmp")
            temp_path.write_text(text, encoding="utf-8")
            temp_path.chmod(0o600)
            os.replace(temp_path, path)
    except Exception:
        try:
            (Path(trace_dir) / "admin-ui-api-request-trace.json").unlink(missing_ok=True)
        except Exception:
            pass
        report["complete"] = False
        for record in report["records"]:
            record["complete"] = False
            record["reasonEnum"] = "trace_write_failure"
        text = json.dumps(report, indent=2, sort_keys=True) + "\n"
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        try:
            with open(summary, "a", encoding="utf-8") as stream:
                stream.write("### Admin UI collector request-send trace\n\n")
                stream.write("```json\n")
                stream.write(text)
                stream.write("```\n")
        except Exception:
            pass
    print(f"Admin UI collector request-send trace complete={str(report['complete']).lower()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
