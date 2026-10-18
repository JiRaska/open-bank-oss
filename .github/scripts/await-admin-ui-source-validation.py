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


def main_push_runs(fetch, repository, workflow, sha=None):
    params = {"branch": "main", "event": "push", "per_page": 100}
    if sha:
        params["head_sha"] = sha
    listing = fetch(f"repos/{repository}/actions/workflows/{workflow}/runs?{urllib.parse.urlencode(params)}")
    return [run for run in listing.get("workflow_runs", [])
            if (sha is None or run.get("head_sha") == sha)
            and isinstance(run.get("head_sha"), str)
            and run.get("head_branch") == "main"
            and run.get("event") == "push"
            and run.get("path") == f".github/workflows/{workflow}"
            and (run.get("repository") or {}).get("full_name") == repository]


def check_workflow(fetch, repository, sha, workflow):
    runs = main_push_runs(fetch, repository, workflow, sha)
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
    query = urllib.parse.urlencode({"sha": source_sha, "path": "openbank-admin-ui/", "per_page": 1})
    commits = fetch(f"repos/{repository}/commits?{query}")
    if not isinstance(commits, list) or len(commits) != 1:
        raise RuntimeError(f"Cannot identify the latest Admin UI ancestor of {source_sha}")
    ui_sha = commits[0].get("sha")
    if not isinstance(ui_sha, str) or not re.fullmatch(r"[0-9a-f]{40}", ui_sha):
        raise RuntimeError("Latest Admin UI ancestor has no canonical commit SHA")
    return ui_sha


def same_ui_tree_pact_sha(fetch, repository, ui_sha, ui_changes):
    """Return the head of the newest main Pact run whose Admin UI tree is ui_sha's.

    A commit whose latest openbank-admin-ui/ change is ui_sha carries ui_sha's Admin UI
    tree byte for byte, so its Pact verdict is about the same source. This includes
    ui_sha's own runs; the NEWEST such run decides, so a failed ancestor is superseded
    only by a later run over the identical tree, and a later failure or pending run is
    never bypassed by an earlier success. A commit that touched openbank-admin-ui/
    resolves to itself and never qualifies. ui_changes caches sha -> latest UI change.
    """
    runs = sorted(main_push_runs(fetch, repository, "pact-drift-check.yml"),
                  key=lambda item: item["id"], reverse=True)
    for run in runs:
        head = run["head_sha"]
        if head not in ui_changes:
            ui_changes[head] = ui_sha if head == ui_sha else latest_ui_change(fetch, repository, head)
        if ui_changes[head] == ui_sha:
            return head
    return ui_sha


def wait_for_validation(fetch, repository, sha, *, clock=time.monotonic,
                        sleep=time.sleep, max_wait=MAX_WAIT_SECONDS):
    deadline = clock() + max_wait
    # A governance-only push can rebuild UI code introduced by an earlier main commit.
    # Prefer a successful Pact run for the exact image source when one exists. Only fall
    # back to the latest UI-changing ancestor when that source did not trigger Pact drift.
    # A pending or failed exact-source run must never be bypassed by older evidence.
    # The fallback is the newest main Pact run over the SAME Admin UI tree, not only the
    # ancestor's: a re-run replays the ancestor's old workflow file, so when its run failed
    # on a CI defect later fixed on main, only a descendant run can ever clear it.
    ui_sha = latest_ui_change(fetch, repository, sha)
    ui_changes = {sha: ui_sha}
    while True:
        if check_workflow(fetch, repository, sha, "ci.yml"):
            source_pact = check_workflow(fetch, repository, sha, "pact-drift-check.yml")
            if source_pact is True:
                return
            if source_pact is None:
                evidence = same_ui_tree_pact_sha(fetch, repository, ui_sha, ui_changes)
                if check_workflow(fetch, repository, evidence, "pact-drift-check.yml"):
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
