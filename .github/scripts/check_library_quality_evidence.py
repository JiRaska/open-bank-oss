#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Require completed exact-head library quality workflows from the required all-green gate."""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import time

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


class EvidenceUnavailable(RuntimeError):
    """A read could not establish evidence; never a successful or negative test verdict."""


class ReadBudget:
    def __init__(self, timeout):
        self.deadline = time.monotonic() + timeout
        self.recoveries = 0

    def remaining(self):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise EvidenceUnavailable("shared evidence deadline exhausted")
        return remaining


def responses(output):
    """Decode gh --include --paginate framing, not HTTP-looking text inside JSON."""
    result = []
    decoder = json.JSONDecoder()
    rest = output.replace("\r\n", "\n").lstrip()
    while rest:
        header, separator, body = rest.partition("\n\n")
        lines = header.splitlines()
        status = re.fullmatch(r"HTTP/\d(?:\.\d)? ([1-5]\d{2})(?: .*)?", lines[0]) if lines else None
        if not separator or not status:
            raise ValueError("missing HTTP response framing")
        headers = {}
        for line in lines[1:]:
            name, colon, value = line.partition(":")
            if not colon or not re.fullmatch(r"[A-Za-z0-9-]+", name):
                raise ValueError("malformed response header")
            name, value = name.lower(), value.strip()
            if name in headers and headers[name] != value:
                raise ValueError("conflicting response headers")
            headers[name] = value
        data, end = decoder.raw_decode(body.lstrip())
        result.append((int(status[1]), headers, data))
        rest = body.lstrip()[end:].lstrip()
    if not result:
        raise ValueError("empty API response")
    return result


def quota_delay(status, headers, body):
    """Only a recognized GitHub quota response may delay a GET; unknown 403s fail."""
    message = body.get("message", "") if isinstance(body, dict) else ""
    primary = isinstance(message, str) and re.match(r"^API rate limit exceeded(?:[ .]|$)", message)
    secondary = isinstance(message, str) and re.match(r"^You have exceeded a secondary rate limit(?:[ .]|$)", message)
    if status not in (403, 429) or not (primary or secondary):
        raise EvidenceUnavailable(f"HTTP {status}: not a recognized recoverable quota response")
    for name in ("x-ratelimit-remaining", "x-ratelimit-reset", "retry-after"):
        if name in headers and not re.fullmatch(r"[0-9]{1,12}", headers[name]):
            raise EvidenceUnavailable(f"invalid {name}")
    delays = []
    if primary or headers.get("x-ratelimit-remaining") == "0":
        if headers.get("x-ratelimit-remaining") != "0" or "x-ratelimit-reset" not in headers:
            raise EvidenceUnavailable("primary quota has no authoritative reset")
        delay = int(headers["x-ratelimit-reset"]) - time.time()
        if delay <= 0:
            raise EvidenceUnavailable("quota reset is not in the future")
        delays.append(delay + 1)  # Do not retry on the reset boundary itself.
    if secondary or "retry-after" in headers:
        if not int(headers.get("retry-after", "0")):
            raise EvidenceUnavailable("secondary quota has no positive Retry-After")
        delays.append(int(headers["retry-after"]))
    return max(delays)


def api(endpoint, *, budget):
    for attempt in range(3):
        try:
            response = subprocess.run(
                ["gh", "api", "--include", "--paginate", endpoint], text=True,
                capture_output=True, check=False, timeout=min(30, budget.remaining()),
            )
            budget.remaining()
            pages = responses(response.stdout)
        except (OSError, subprocess.TimeoutExpired, ValueError) as exc:
            raise EvidenceUnavailable(f"{endpoint}: {type(exc).__name__}") from exc
        if response.returncode == 0 and all(status == 200 for status, _headers, _body in pages):
            budget.recoveries += int(attempt > 0)
            return [body for _status, _headers, body in pages]
        # A failed later page never supplies partial proof, even if earlier pages were 200.
        if response.returncode == 0 or any(status != 200 for status, _headers, _body in pages[:-1]):
            raise EvidenceUnavailable(f"{endpoint}: invalid API completion")
        delay = quota_delay(*pages[-1])
        if attempt == 2 or delay >= budget.remaining():
            raise EvidenceUnavailable(f"{endpoint}: quota recovery cannot fit remaining budget/attempts")
        print(f"Library evidence read rate-limited; retry {attempt + 1}/2 in {delay:.1f}s", flush=True)
        time.sleep(delay)
    raise AssertionError("unreachable retry exhaustion")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--pr", required=True, type=int)
    parser.add_argument("--head", required=True)
    parser.add_argument("--timeout", type=int, default=900)
    args = parser.parse_args()
    budget = ReadBudget(args.timeout)
    try:
        return require_proofs(args, budget)
    except EvidenceUnavailable as exc:
        print(f"::error::Library quality evidence NOT_EVALUATED: {exc}; refusing all-green")
        return 1


def require_proofs(args, budget):
    paths = [f["filename"] for page in api(f"repos/{args.repo}/pulls/{args.pr}/files?per_page=100", budget=budget) for f in page]
    if not applicable(paths):
        budget.remaining()
        print("Library proofs: not applicable to these paths")
        return 0
    completed_jobs = {}
    while True:
        generation = budget.recoveries
        runs = [r for page in api(f"repos/{args.repo}/actions/runs?head_sha={args.head}&event=pull_request&per_page=100", budget=budget)
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
                        latest["jobs"] = [j for page in api(endpoint, budget=budget) for j in page["jobs"]]
                        proofs = [j for j in latest["jobs"] if j.get("name") == JOBS[workflow]]
                        if proofs and all(j.get("status") == "completed" and j.get("conclusion") == "success" for j in proofs):
                            completed_jobs[key] = latest["jobs"]
        # A recovered read may have waited across a rerun. Re-select latest attempts
        # from uncached inventory before trusting even the final recovered roster.
        budget.remaining()
        if budget.recoveries != generation:
            continue
        state, detail = verdict(runs, args.head)
        print(f"Library proofs: {state}: {detail}", flush=True)
        if state == "success":
            budget.remaining()
            return 0
        if state == "failure" or time.monotonic() >= budget.deadline:
            print("::error::Library quality evidence failed or is missing; refusing all-green")
            return 1
        time.sleep(min(30, max(0, budget.deadline - time.monotonic())))


if __name__ == "__main__":
    raise SystemExit(main())
