#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Verdict for `all-green` from per-leg success markers, with no REST API call.

Each service build leg (`_service-ci.yml` `build`) uploads `service-build-ok-<service>`
as its LAST step, `if: success()`. A failed, cancelled or never-scheduled leg therefore
leaves no marker. This script requires exactly one valid marker per selected service.

Why not `needs.build.result`: for a reusable-workflow matrix it can read success while a
child was cancelled before getting a runner. Why not the jobs API: it spends the shared
installation quota, and exhausting that quota reddened a required check on green PRs.
Artifact download uses the artifact service, not the REST quota.

Fail closed: unreadable, malformed, foreign-sha, missing or extra => non-zero.
"""
import json
import os
import sys
from pathlib import Path

PREFIX = "marker-"
SUFFIX = ".json"


def verify(services, directory, sha):
    if not isinstance(services, list) or not services or len(services) != len(set(services)):
        return ["invalid selected-service matrix"]
    root = Path(directory)
    found = {}
    problems = []
    if not root.is_dir():
        return [f"marker directory {directory} does not exist"]
    # Flat layout (download-artifact merge-multiple): one marker-<service>.json per leg.
    # The layout does not depend on how many artifacts matched.
    for entry in sorted(root.iterdir()):
        name = entry.name
        if not entry.is_file() or not (name.startswith(PREFIX) and name.endswith(SUFFIX)):
            problems.append(f"unexpected entry {name}")
            continue
        service = name[len(PREFIX):-len(SUFFIX)]
        try:
            data = json.loads(entry.read_text())
        except (OSError, ValueError) as exc:
            problems.append(f"{service}: unreadable marker ({exc.__class__.__name__})")
            continue
        if not isinstance(data, dict):
            problems.append(f"{service}: marker is not an object")
            continue
        if data.get("service") != service or data.get("sha") != sha or data.get("result") != "success":
            problems.append(f"{service}: marker does not attest success of this sha")
            continue
        found[service] = data
    missing = sorted(set(services) - set(found))
    extra = sorted(set(found) - set(services))
    if missing:
        problems.append(f"no success marker for: {missing}")
    if extra:
        problems.append(f"markers for unselected services: {extra}")
    return problems


def main():
    try:
        services = json.loads(os.environ["SERVICES_JSON"])
        directory, sha = os.environ["MARKER_DIR"], os.environ["GITHUB_SHA"]
    except (KeyError, ValueError) as exc:
        print(f"cannot determine verdict: {exc!r}")
        return 1
    problems = verify(services, directory, sha)
    for problem in problems:
        print(problem)
    if problems:
        return 1
    print(f"Verified {len(services)} selected service builds via success markers")
    return 0


if __name__ == "__main__":
    sys.exit(main())
