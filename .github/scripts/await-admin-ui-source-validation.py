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
        return None
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


def latest_ui_change(fetch, repository, source_sha):
    # app-status.json is derived display data and cannot change a consumer Pact. Its
    # standalone refresh does not trigger pact-drift-check.yml, so select the most
    # recent *Pact-relevant* UI ancestor. Inspect the whole commit: a mixed change
    # must retain its exact-source Pact requirement even if it touches the dossier.
    for page in range(1, 6):
        query = urllib.parse.urlencode({"sha": source_sha, "path": "openbank-admin-ui/",
                                        "per_page": 100, "page": page})
        commits = fetch(f"repos/{repository}/commits?{query}")
        if not isinstance(commits, list):
            raise RuntimeError("Admin UI commit inventory is malformed")
        for commit in commits:
            ui_sha = commit.get("sha") if isinstance(commit, dict) else None
            if not isinstance(ui_sha, str) or not re.fullmatch(r"[0-9a-f]{40}", ui_sha):
                raise RuntimeError("Admin UI ancestor has no canonical commit SHA")
            detail = fetch(f"repos/{repository}/commits/{ui_sha}?per_page=300")
            files = detail.get("files") if isinstance(detail, dict) else None
            if not isinstance(detail, dict) or detail.get("sha") != ui_sha or \
                    not isinstance(files, list) or not files:
                raise RuntimeError(f"Cannot verify changed files for {ui_sha}")
            dossier_only = len(files) == 1 and files[0].get("filename") ==                 "openbank-admin-ui/app-status.json" and files[0].get("status") == "modified"
            if not dossier_only:
                return ui_sha
        if len(commits) < 100:
            break
    raise RuntimeError(f"Cannot identify a Pact-relevant Admin UI ancestor of {source_sha}")


def wait_for_validation(fetch, repository, sha, *, clock=time.monotonic,
                        sleep=time.sleep, max_wait=MAX_WAIT_SECONDS):
    deadline = clock() + max_wait
    # A governance-only push can rebuild UI code introduced by an earlier main commit.
    # Prefer a successful Pact run for the exact image source when one exists. Only fall
    # back to the latest Pact-relevant UI ancestor when that source did not trigger Pact drift.
    # A pending or failed exact-source run must never be bypassed by older evidence.
    ui_sha = latest_ui_change(fetch, repository, sha)
    while True:
        if check_workflow(fetch, repository, sha, "ci.yml"):
            source_pact = check_workflow(fetch, repository, sha, "pact-drift-check.yml")
            if source_pact is True or (source_pact is None and
                                       check_workflow(fetch, repository, ui_sha, "pact-drift-check.yml")):
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
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Repository identity is missing or invalid")
    if not re.fullmatch(r"[0-9a-f]{40}", sha) or not token:
        raise ValueError("Exact source SHA and read-only API token are required")
    wait_for_validation(github_fetch(token), repository, sha)
    print(f"Verified exact-source Admin UI validation for {sha}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, TimeoutError, OSError) as error:
        print(f"::error::Admin UI source validation failed: {error}", file=sys.stderr)
        sys.exit(1)
