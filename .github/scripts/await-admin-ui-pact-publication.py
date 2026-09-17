#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Wait on a hosted runner for exact-main Admin UI Pact publication, never on ARC."""

import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

PUBLISH_JOB = "Publish verified admin UI contracts"
DRIFT_JOB = "Regenerate consumer pacts and diff against committed"
POLL_SECONDS = 60
MAX_WAIT_SECONDS = 35 * 60


def poll_once(fetch, repository, sha):
    query = urllib.parse.urlencode({"head_sha": sha, "event": "push", "per_page": 20})
    listing = fetch(f"repos/{repository}/actions/workflows/pact-drift-check.yml/runs?{query}")
    runs = [run for run in listing.get("workflow_runs", [])
            if run.get("head_sha") == sha and run.get("event") == "push"]
    if not runs:
        return False
    run = max(runs, key=lambda item: item["id"])
    if run.get("status") != "completed":
        return False
    if run.get("conclusion") != "success":
        raise RuntimeError("Exact-SHA Pact drift/publication workflow did not succeed")
    jobs = fetch(f"repos/{repository}/actions/runs/{run['id']}/jobs?filter=latest&per_page=100").get("jobs", [])
    by_name = {job.get("name"): job.get("conclusion") for job in jobs}
    for name in (DRIFT_JOB, PUBLISH_JOB):
        if name in by_name and by_name[name] != "success":
            raise RuntimeError(f"Exact-SHA Pact job did not succeed: {name}")
    return by_name.get(DRIFT_JOB) == "success" and by_name.get(PUBLISH_JOB) == "success"


def wait_for_publication(fetch, repository, sha, *, clock=time.monotonic, sleep=time.sleep,
                         max_wait=MAX_WAIT_SECONDS):
    deadline = clock() + max_wait
    while True:
        if poll_once(fetch, repository, sha):
            return
        remaining = deadline - clock()
        if remaining <= 0:
            raise TimeoutError("Exact-SHA Pact publication was not proven within 35 minutes")
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
    sha = os.environ.get("GITHUB_SHA", "")
    token = os.environ.get("GH_TOKEN", "")
    if os.environ.get("GITHUB_EVENT_NAME") != "push" or os.environ.get("GITHUB_REF") != "refs/heads/main":
        raise ValueError("Publication wait requires a trusted main push")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository) or not re.fullmatch(r"[0-9a-f]{40}", sha) or not token:
        raise ValueError("Repository, exact SHA and read-only API token are required")
    wait_for_publication(github_fetch(token), repository, sha)
    print(f"Verified Admin UI Pact publication for {sha}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, TimeoutError, OSError) as error:
        print(f"::error::Admin UI Pact publication proof failed: {error}", file=sys.stderr)
        sys.exit(1)
