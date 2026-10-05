#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Validate a read-only Dependabot classification before an App arms auto-merge."""

import json
import os
from pathlib import Path
import re
import sys
import urllib.request

ECOSYSTEM_PREFIXES = {
    "gradle": "dependabot/gradle/",
    "npm_and_yarn": "dependabot/npm_and_yarn/",
}
SHA = re.compile(r"[0-9a-f]{40}\Z")
REPOSITORY = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\Z")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_small_json(path):
    source = Path(path)
    require(source.stat().st_size <= 16_384, "eligibility artifact is unexpectedly large")
    with source.open(encoding="utf-8") as stream:
        return json.load(stream)


def validate(request, event, pr, repository):
    require(REPOSITORY.fullmatch(repository), "invalid repository identity")
    require(isinstance(request, dict) and set(request) ==
            {"number", "sha", "ref", "update_type", "ecosystem"},
            "eligibility artifact has an unexpected shape")
    number = request["number"]
    require(type(number) is int and 0 < number < 2**31, "invalid PR number")
    require(isinstance(request["sha"], str) and SHA.fullmatch(request["sha"]),
            "invalid PR head SHA")
    ecosystem = request["ecosystem"]
    require(isinstance(ecosystem, str) and ecosystem in ECOSYSTEM_PREFIXES,
            "ecosystem is outside patch auto-merge policy")
    ref = request["ref"]
    require(isinstance(ref, str) and len(ref) <= 255 and
            ref.startswith(ECOSYSTEM_PREFIXES[ecosystem]) and
            re.fullmatch(r"[A-Za-z0-9._/-]+", ref), "invalid Dependabot branch")
    require(request["update_type"] == "version-update:semver-patch",
            "update is outside patch auto-merge policy")

    run = event.get("workflow_run") or {}
    require(run.get("event") == "pull_request" and run.get("conclusion") == "success" and
            (run.get("actor") or {}).get("login") == "dependabot[bot]" and
            (run.get("head_repository") or {}).get("full_name") == repository and
            run.get("head_branch") == ref and run.get("head_sha") == request["sha"] and
            run.get("path") == ".github/workflows/dependabot-auto-merge.yml",
            "eligibility artifact does not belong to a successful Dependabot PR run")
    associated = run.get("pull_requests") or []
    require(not associated or any(item.get("number") == number for item in associated),
            "eligibility PR does not match the workflow run")

    require(pr.get("number") == number and pr.get("state") == "open" and
            (pr.get("user") or {}).get("login") == "dependabot[bot]" and
            (pr.get("base") or {}).get("ref") == "main" and
            (pr.get("head") or {}).get("sha") == request["sha"] and
            (pr.get("head") or {}).get("ref") == ref and
            ((pr.get("head") or {}).get("repo") or {}).get("full_name") == repository,
            "Dependabot PR identity or head changed after classification")
    url = pr.get("html_url")
    require(url == f"https://github.com/{repository}/pull/{number}", "invalid PR URL")
    return url


def fetch_pr(repository, number):
    token = os.environ.get("GH_TOKEN", "")
    require(token, "read-only GitHub token is required")
    request = urllib.request.Request(
        f"https://api.github.com/repos/{repository}/pulls/{number}",
        headers={"Authorization": f"Bearer {token}",
                 "Accept": "application/vnd.github+json",
                 "X-GitHub-Api-Version": "2022-11-28"},
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.load(response)


def main():
    require(len(sys.argv) == 3, "usage: validate-dependabot-auto-merge.py ARTIFACT REPOSITORY")
    request = read_small_json(sys.argv[1])
    with open(os.environ["GITHUB_EVENT_PATH"], encoding="utf-8") as stream:
        event = json.load(stream)
    number = request.get("number") if isinstance(request, dict) else None
    require(type(number) is int and 0 < number < 2**31, "invalid PR number")
    url = validate(request, event, fetch_pr(sys.argv[2], number), sys.argv[2])
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        print(f"pr_url={url}", file=output)
    print(f"Validated Dependabot patch PR #{number} for App-token auto-merge")


if __name__ == "__main__":
    try:
        main()
    except (KeyError, OSError, ValueError, json.JSONDecodeError) as error:
        print(f"::error::Dependabot auto-merge eligibility rejected: {error}", file=sys.stderr)
        sys.exit(1)
