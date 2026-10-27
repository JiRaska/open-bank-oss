#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Require completed exact-head library quality workflows from the required all-green gate."""
from __future__ import annotations

import argparse
import json
import os
import signal
import subprocess
import time
from pathlib import Path

WORKFLOWS = (".github/workflows/libs-quality.yml", ".github/workflows/cflite-pr.yml", ".github/workflows/libs-bench.yml")
JOBS = dict(zip(WORKFLOWS, ("Library ABI and recovery", "Fuzz changed code (Jazzer, 300s)",
                            "JMH allocation baseline (enforced)"), strict=True))
PROOF_PATHS = ('.github/scripts/pitest-libs-advisory.init.gradle', '.github/workflows/pitest.yml', ".github/scripts/test_bench_ratchet.py", ".github/workflows/services-ci.yml", ".github/scripts/library_quality_scope.py", ".github/scripts/test_library_quality_scope.py",
               ".github/scripts/check_library_quality_evidence.py", ".github/scripts/test_library_quality_evidence.py",
               ".github/scripts/check-bench-alloc-baseline.py")


def applicable(paths):
    return any(((p.startswith("openbank-libs") and "/" in p) or p.startswith(("fuzz/", ".clusterfuzzlite/"))) or p in WORKFLOWS + PROOF_PATHS for p in paths)


def verdict(runs, head):
    pending = []
    for workflow in WORKFLOWS:
        candidates = [r for r in runs if r.get("path") == workflow and r.get("head_sha") == head
                      and r.get("event") == "pull_request"]
        if not candidates:
            pending.append(f"{workflow}: no exact-head run")
            continue
        latest = max(candidates, key=lambda r: (r["id"], r.get("run_attempt", 1)))
        if latest["status"] != "completed":
            pending.append(f"{workflow}: {latest['status']}")
        elif latest.get("conclusion") != "success":
            return "failure", f"{workflow}: {latest.get('conclusion')} ({latest.get('html_url', '')})"
        else:
            proofs = [j for j in latest.get("jobs", []) if j.get("name") == JOBS[workflow]]
            if not proofs or any(j.get("status") != "completed" or j.get("conclusion") != "success" for j in proofs):
                return "failure", f"{workflow}: required proof job missing, skipped or unsuccessful"
    return ("pending", "; ".join(pending)) if pending else ("success", "all library proofs passed on this head")


def api(endpoint, deadline):
    # Reuse the fleet retry classifier/reset wait. Each attempt owns its complete
    # paginated output: a failed page must never leak into a later successful read.
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise RuntimeError("Library evidence API deadline expired; refusing all-green")
    wrapper = r'''source "$1"; shift
candidate=$(mktemp)
trap 'rm -f "$candidate"' EXIT
fetch_pages() { gh api --paginate --slurp "$1" > "$candidate"; }
if gh_retry 3 -- fetch_pages "$1"; then cat "$candidate"; else exit $?; fi
'''
    env = dict(os.environ, GH_RETRY_MAX_WAIT_SECONDS="60", GH_RETRY_DEAD_LETTER="0")
    process = subprocess.Popen(
        ["bash", "-c", wrapper, "library-evidence", str(Path(__file__).with_name("gh-retry.sh")), endpoint],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, start_new_session=True,
    )
    try:
        output, diagnostic = process.communicate(timeout=min(300, remaining))
    except subprocess.TimeoutExpired as error:
        # Stop the wrapper AND its gh/sleep children at the shared deadline.
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.communicate()
        raise RuntimeError("Library evidence API request timed out; refusing all-green") from error
    if process.returncode:
        raise RuntimeError(f"Library evidence API failed: {' '.join(diagnostic.split())[-400:]}")
    if diagnostic:
        print("Library evidence API recovered after a bounded transient retry", flush=True)
    return json.loads(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--pr", required=True, type=int)
    parser.add_argument("--head", required=True)
    parser.add_argument("--timeout", type=int, default=900)
    args = parser.parse_args()
    deadline = time.monotonic() + args.timeout
    paths = [f["filename"] for page in api(f"repos/{args.repo}/pulls/{args.pr}/files?per_page=100", deadline) for f in page]
    if not applicable(paths):
        print("Library proofs: not applicable to these paths")
        return 0
    completed_jobs = {}
    while True:
        runs = [r for page in api(f"repos/{args.repo}/actions/runs?head_sha={args.head}&event=pull_request&per_page=100", deadline)
                for r in page["workflow_runs"]]
        for workflow in WORKFLOWS:
            candidates = [r for r in runs if r.get("path") == workflow and r.get("head_sha") == args.head
                          and r.get("event") == "pull_request"]
            if candidates:
                latest = max(candidates, key=lambda r: (r["id"], r.get("run_attempt", 1)))
                if latest["status"] == "completed" and latest.get("conclusion") == "success":
                    # Keep reading the run inventory: reruns and newer producers must
                    # supersede old success. Only a proven successful terminal attempt's
                    # job roster is immutable and reusable within this invocation.
                    key = (args.repo, workflow, latest["head_sha"], latest["event"],
                           latest["id"], latest.get("run_attempt", 1))
                    if key in completed_jobs:
                        latest["jobs"] = completed_jobs[key]
                    else:
                        endpoint = f"repos/{args.repo}/actions/runs/{latest['id']}/attempts/{latest.get('run_attempt', 1)}/jobs?per_page=100"
                        latest["jobs"] = [j for page in api(endpoint, deadline) for j in page["jobs"]]
                        proofs = [j for j in latest["jobs"] if j.get("name") == JOBS[workflow]]
                        if proofs and all(j.get("status") == "completed" and j.get("conclusion") == "success" for j in proofs):
                            completed_jobs[key] = latest["jobs"]
        state, detail = verdict(runs, args.head)
        print(f"Library proofs: {state}: {detail}", flush=True)
        if state == "success":
            return 0
        if state == "failure" or time.monotonic() >= deadline:
            print("::error::Library quality evidence failed or is missing; refusing all-green")
            return 1
        time.sleep(min(30, max(0, deadline - time.monotonic())))


if __name__ == "__main__":
    raise SystemExit(main())
