#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Escalate durable can-i-deploy blocks by service, not whole-workflow result.

Issue #9898. This reader never authorizes issue closure: a green contract gate
does not prove that the image reached a healthy live rollout at current source.
The caller may open/refresh a subject issue, and must retain existing ones until
a separate runtime/source attestation is available.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path


WORKFLOW = "auto-deploy.yml"
ARTIFACT_PREFIX = "can-i-deploy-verdicts-"
SERVICE = re.compile(r"^openbank-[a-z0-9-]+$")
SHA = re.compile(r"^[0-9a-f]{40}$")
DURABLE = {"REGRESSION", "UNVERIFIABLE", "PROVIDER_UNVERIFIED"}
CLASSES = DURABLE | {"DEPLOYABLE", "PENDING_BUILD", "UNVERIFIED", "NOT_ASKED",
                     "NO_CONTRACTS", "UNKNOWN"}
MAX_RUNS = 24


def validate_artifact(doc: dict, run: dict) -> list[dict]:
    if not isinstance(doc, dict) or set(doc) != {
            "schema_version", "head_sha", "run_id", "services"} or type(doc.get("schema_version")) is not int \
            or doc["schema_version"] != 1:
        raise ValueError("unknown artifact schema")
    if type(doc.get("run_id")) is not int or doc["run_id"] != run["id"] \
            or doc.get("head_sha") != run["head_sha"]:
        raise ValueError("artifact does not match its scheduled run")
    rows = doc.get("services")
    if not isinstance(rows, list) or len(rows) > 100:
        raise ValueError("missing or unbounded service verdicts")
    names = set()
    for row in rows:
        if not isinstance(row, dict) or set(row) != {
                "service", "class", "blocked_on", "money_path", "head_sha", "run_id"} \
                or row.get("service") in names:
            raise ValueError("duplicate or malformed service verdict")
        service = row.get("service")
        if not isinstance(service, str) or not SERVICE.fullmatch(service):
            raise ValueError("invalid service name")
        if row.get("class") not in CLASSES or type(row.get("money_path")) is not bool:
            raise ValueError("invalid class or money-path marker")
        if row.get("run_id") != run["id"] or row.get("head_sha") != run["head_sha"]:
            raise ValueError("per-service verdict does not match its run")
        blocked_on = row.get("blocked_on")
        if row["class"] == "DEPLOYABLE" and blocked_on is not None:
            raise ValueError("deployable service carries blocker identity")
        if blocked_on is not None:
            if not isinstance(blocked_on, list) or not 0 < len(blocked_on) <= 100:
                raise ValueError("invalid blocker list")
            for pair in blocked_on:
                if not isinstance(pair, dict) or set(pair) != {
                        "consumer", "provider", "verification_id"}:
                    raise ValueError("invalid blocker fields")
                if not all(isinstance(pair.get(key), str) and SERVICE.fullmatch(pair[key])
                           for key in ("consumer", "provider")):
                    raise ValueError("invalid blocker participant")
                if service not in (pair["consumer"], pair["provider"]):
                    raise ValueError("blocker does not involve selected service")
                ident = pair["verification_id"]
                if ident is not None and (type(ident) is not int or not 0 < ident < 10**18):
                    raise ValueError("invalid verification ID")
        names.add(service)
    return rows


def evaluate(runs: list[dict]) -> dict:
    """Newest-first scheduled runs, each with a validated ``verdicts`` artifact."""
    if not runs:
        return {"ready": False, "active": [], "observed_services": 0}
    history: dict[str, list[dict]] = {}
    for run in runs:
        if run.get("event") != "schedule" or run.get("status") != "completed":
            raise ValueError("non-scheduled or incomplete run in subject population")
        for row in validate_artifact(run["verdicts"], run):
            history.setdefault(row["service"], []).append({
                "class": row["class"], "money_path": row["money_path"],
                "blocked_on": row.get("blocked_on"), "run_id": run["id"],
                "head_sha": run["head_sha"],
            })
    active = []
    for service, observed in sorted(history.items()):
        newest = observed[0]
        if newest["class"] not in DURABLE:
            continue
        threshold = 1 if newest["money_path"] else 3
        streak = []
        for verdict in observed:
            if verdict["money_path"] != newest["money_path"]:
                raise ValueError("money-path classification changed within watch window")
            if verdict["class"] not in DURABLE:
                break
            streak.append(verdict)
        if len(streak) >= threshold:
            active.append({"service": service, "class": newest["class"],
                           "money_path": newest["money_path"], "threshold": threshold,
                           "streak": len(streak), "run_id": newest["run_id"],
                           "head_sha": newest["head_sha"],
                           "blocked_on": newest["blocked_on"]})
    return {"ready": True, "active": active, "observed_services": len(history)}


def gh_json(*args: str) -> object:
    cp = subprocess.run(["gh", "api", *args], capture_output=True, text=True, check=True)
    return json.loads(cp.stdout)


def skipped_gate(repo: str, run_id: int) -> bool:
    """An artifact-free tick is harmless only when no contract question was asked."""
    response = gh_json(f"repos/{repo}/actions/runs/{run_id}/jobs?filter=latest&per_page=100")
    jobs = response.get("jobs")
    if not isinstance(jobs, list):
        raise ValueError("missing run jobs")
    gate = [job for job in jobs if job.get("name") == "can-i-deploy"]
    if len(gate) != 1:
        raise ValueError("cannot establish contract-gate status")
    return gate[0].get("conclusion") == "skipped"


def fetch(repo: str) -> list[dict]:
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise ValueError("invalid repository")
    response = gh_json(f"repos/{repo}/actions/workflows/{WORKFLOW}/runs"
                       f"?event=schedule&status=completed&per_page={MAX_RUNS}")
    raw_runs = response.get("workflow_runs")
    if not isinstance(raw_runs, list):
        raise ValueError("missing scheduled run list")
    runs = []
    missing_newer = False
    for item in raw_runs:
        if item.get("event") != "schedule" or item.get("status") != "completed":
            raise ValueError("GitHub returned an unscoped run")
        if item.get("conclusion") == "cancelled":
            continue
        run_id, sha, attempt = item.get("id"), item.get("head_sha"), item.get("run_attempt")
        if type(run_id) is not int or not SHA.fullmatch(str(sha)) or type(attempt) is not int:
            raise ValueError("invalid scheduled run identity")
        expected = f"{ARTIFACT_PREFIX}{run_id}-{attempt}"
        artifacts = gh_json(f"repos/{repo}/actions/runs/{run_id}/artifacts?per_page=100")
        matches = [a for a in artifacts.get("artifacts", [])
                   if a.get("name") == expected and not a.get("expired")]
        if not matches:
            if skipped_gate(repo, run_id):
                continue  # no service selected; this tick says nothing about any subject
            if runs:
                break  # older run before artifact rollout; keep the contiguous recent window
            missing_newer = True
            continue
        if len(matches) != 1 or missing_newer:
            raise ValueError("newer scheduled run lacks its verdict artifact")
        with tempfile.TemporaryDirectory(prefix="auto-deploy-subject-") as directory:
            subprocess.run(["gh", "run", "download", str(run_id), "--repo", repo,
                            "--name", expected, "--dir", directory],
                           capture_output=True, text=True, check=True)
            path = Path(directory) / "can-i-deploy-verdicts.json"
            if not path.is_file() or path.stat().st_size > 128_000:
                raise ValueError("missing or unbounded verdict artifact")
            doc = json.loads(path.read_text())
        run = {"id": run_id, "head_sha": sha, "event": "schedule",
               "status": "completed", "verdicts": doc}
        validate_artifact(doc, run)
        runs.append(run)
    return runs


def self_test() -> None:
    def tick(ident: int, cls: str, money: bool, service: str = "openbank-account-service") -> dict:
        sha = f"{ident:040x}"
        row = {"service": service, "class": cls, "blocked_on": None,
               "money_path": money, "head_sha": sha, "run_id": ident}
        return {"id": ident, "head_sha": sha, "event": "schedule", "status": "completed",
                "verdicts": {"schema_version": 1, "head_sha": sha, "run_id": ident,
                             "services": [row]}}

    assert evaluate([])["ready"] is False
    assert len(evaluate([tick(3, "REGRESSION", True)])["active"]) == 1
    ordinary = [tick(3, "REGRESSION", False), tick(2, "UNVERIFIABLE", False),
                tick(1, "PROVIDER_UNVERIFIED", False)]
    assert len(evaluate(ordinary)["active"]) == 1
    assert evaluate(ordinary[:2])["active"] == []
    assert evaluate([ordinary[0], tick(2, "DEPLOYABLE", False), ordinary[2]])["active"] == []
    # A green push and a tick that selected another service cannot clear the
    # subject's existing issue; neither is included in this service's streak.
    other = tick(4, "DEPLOYABLE", False, "openbank-card-service")
    assert len(evaluate([other, *ordinary])["active"]) == 1
    try:
        bad = tick(5, "REGRESSION", True)
        bad["verdicts"]["head_sha"] = "f" * 40
        evaluate([bad])
    except ValueError:
        pass
    else:
        raise AssertionError("mismatched artifact accepted")
    original_gh_json = globals()["gh_json"]
    try:
        globals()["gh_json"] = lambda path: (
            {"workflow_runs": [{"id": 7, "head_sha": "a" * 40,
                                "run_attempt": 1, "event": "schedule",
                                "status": "completed", "conclusion": "success"}]}
            if "/runs?" in path else
            {"artifacts": []} if "/artifacts?" in path else
            {"jobs": [{"name": "can-i-deploy", "conclusion": "skipped"}]})
        assert fetch("owner/repo") == []  # no selected service is not a failed verdict
    finally:
        globals()["gh_json"] = original_gh_json
    print("detect-auto-deploy-subjects: self-test PASS")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--evaluate", action="store_true")
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    parser.add_argument("--json", type=Path)
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if not args.evaluate or not args.json:
        parser.error("--evaluate and --json are required")
    try:
        result = evaluate(fetch(args.repo))
        args.json.write_text(json.dumps(result, sort_keys=True) + "\n")
        return 0
    except (ValueError, OSError, subprocess.CalledProcessError, json.JSONDecodeError) as exc:
        print(f"::error::subject watch cannot validate scheduled verdict artifacts ({type(exc).__name__})",
              file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
