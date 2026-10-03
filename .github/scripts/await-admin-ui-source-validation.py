#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Admit an Admin UI image source only after exact-main validation completes."""

import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

POLL_SECONDS = 60
MAX_WAIT_SECONDS = 40 * 60
WORKFLOWS = {
    "ci.yml": ("Admin UI build",),
    "pact-drift-check.yml": (
        "Regenerate consumer pacts and diff against committed",
        "Publish verified admin UI contracts",
    ),
}


def check_workflow(fetch, repository, sha, workflow):
    query = urllib.parse.urlencode({"head_sha": sha, "event": "push", "per_page": 100})
    listing = fetch(f"repos/{repository}/actions/workflows/{workflow}/runs?{query}")
    runs = [run for run in listing.get("workflow_runs", [])
            if run.get("head_sha") == sha
            and run.get("head_branch") == "main"
            and run.get("event") == "push"
            and run.get("path") == f".github/workflows/{workflow}"
            and (run.get("repository") or {}).get("full_name") == repository]
    if not runs:
        return False
    run = max(runs, key=lambda item: item["id"])
    if run.get("status") != "completed":
        return False
    if run.get("conclusion") != "success":
        raise RuntimeError(f"{workflow} for {sha} ended {run.get('conclusion')}")
    jobs = fetch(f"repos/{repository}/actions/runs/{run['id']}/jobs?filter=latest&per_page=100").get("jobs", [])
    for name in WORKFLOWS[workflow]:
        matches = [job for job in jobs if job.get("name") == name]
        if len(matches) != 1 or matches[0].get("status") != "completed" or matches[0].get("conclusion") != "success":
            raise RuntimeError(f"{workflow} lacks successful exact-source job {name}")
    return True


def wait_for_validation(fetch, repository, sha, require_pact, *, clock=time.monotonic,
                        sleep=time.sleep, max_wait=MAX_WAIT_SECONDS):
    deadline = clock() + max_wait
    required = ("ci.yml", "pact-drift-check.yml") if require_pact else ("ci.yml",)
    while True:
        if all(check_workflow(fetch, repository, sha, workflow) for workflow in required):
            return
        remaining = deadline - clock()
        if remaining <= 0:
            raise TimeoutError(f"Exact-source validation for {sha} did not complete within {max_wait}s")
        sleep(min(POLL_SECONDS, remaining))


def github_fetch(token):
    def fetch(path):
        request = urllib.request.Request(
            "https://api.github.com/" + path,
            headers={"Authorization": "Bearer " + token,
                     "Accept": "application/vnd.github+json",
                     "X-GitHub-Api-Version": "2022-11-28"},
        )
        with urllib.request.urlopen(request, timeout=20) as response:
            return json.load(response)
    return fetch


def main():
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    sha = os.environ.get("SOURCE_SHA", "")
    token = os.environ.get("GH_TOKEN", "")
    require_pact = os.environ.get("REQUIRE_PACT", "")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Repository identity is missing or invalid")
    if not re.fullmatch(r"[0-9a-f]{40}", sha) or not token or require_pact not in ("true", "false"):
        raise ValueError("Exact source SHA, read-only API token and Pact scope are required")
    wait_for_validation(github_fetch(token), repository, sha, require_pact == "true")
    print(f"Verified exact-source Admin UI validation for {sha}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, TimeoutError, OSError) as error:
        print(f"::error::Admin UI source validation failed: {error}", file=sys.stderr)
        sys.exit(1)
