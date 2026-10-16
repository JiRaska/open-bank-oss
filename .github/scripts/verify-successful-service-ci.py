# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""Verify that an exact main Services CI run built and contracted one service.

Used by auto-deploy reconcile before it borrows a Pact verdict for an equivalent
tree. A broker tag is not provenance: this checks a completed successful run of
the Services CI workflow, its exact SHA/attempt, all-green, and the service's own
build and contract child jobs. Every API or schema failure is a refusal.
"""

from __future__ import annotations

import json
import os
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urljoin, urlsplit
from urllib.request import Request, urlopen


class VerificationError(Exception):
    pass


def _json_pages(url: str, token: str) -> list[dict]:
    """Read every API page; errors and malformed pages must never become evidence."""
    rows: list[dict] = []
    next_url: str | None = url
    origin = urlsplit(url)[:2]
    while next_url:
        if urlsplit(next_url)[:2] != origin:
            raise VerificationError("GitHub Actions API pagination changed origin")
        request = Request(
            next_url,
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {token}",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )
        try:
            with urlopen(request, timeout=20) as response:
                body = json.loads(response.read())
                link_header = response.headers.get("Link", "")
        except (HTTPError, URLError, TimeoutError, OSError, json.JSONDecodeError) as exc:
            raise VerificationError(f"GitHub Actions API request failed: {type(exc).__name__}") from exc
        if not isinstance(body, dict):
            raise VerificationError("GitHub Actions API returned a non-object response")
        # Run endpoints wrap results in workflow_runs; job endpoints use jobs.
        key = "workflow_runs" if "workflow_runs" in body else "jobs"
        page_rows = body.get(key)
        if not isinstance(page_rows, list) or any(not isinstance(row, dict) for row in page_rows):
            raise VerificationError(f"GitHub Actions API response has no valid {key} array")
        rows.extend(page_rows)
        next_url = None
        for part in link_header.split(","):
            match = re.match(r'\s*<([^>]+)>\s*;\s*rel="next"', part)
            if match:
                next_url = match.group(1)
                if urlsplit(next_url)[:2] != origin:
                    raise VerificationError("GitHub Actions API pagination changed origin")
                break
    return rows


def verify_successful_service_ci(
    service: str, sha: str, repository: str, token: str, api_url: str
) -> tuple[str, int]:
    if not re.fullmatch(r"openbank-[a-z0-9-]+", service):
        raise VerificationError("invalid service name")
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise VerificationError("invalid source SHA")
    if not re.fullmatch(r"[^/]+/[^/]+", repository):
        raise VerificationError("invalid repository")
    if not token:
        raise VerificationError("GitHub token is unavailable")

    base = api_url.rstrip("/") + "/"
    encoded_repo = "/".join(repository.split("/"))
    query = urlencode({"branch": "main", "head_sha": sha, "per_page": 100})
    runs_url = urljoin(
        base,
        f"repos/{encoded_repo}/actions/workflows/services-ci.yml/runs?{query}",
    )
    runs = _json_pages(runs_url, token)
    candidates = [
        run
        for run in runs
        if run.get("head_sha") == sha
        and run.get("head_branch") == "main"
        and run.get("event") in {"push", "workflow_dispatch"}
        and run.get("status") == "completed"
        and run.get("conclusion") == "success"
        and isinstance(run.get("id"), int)
        and isinstance(run.get("run_attempt"), int)
        and run.get("run_attempt", 0) > 0
    ]
    expected = {
        "all-green",
        f"build ({service}) / {service} (build)",
        f"build ({service}) / {service} (contract)",
    }
    for run in candidates:
        run_id = run["id"]
        attempt = run["run_attempt"]
        jobs_query = urlencode({"per_page": 100})
        jobs_url = urljoin(
            base,
            f"repos/{encoded_repo}/actions/runs/{run_id}/attempts/{attempt}/jobs?{jobs_query}",
        )
        jobs = _json_pages(jobs_url, token)
        observed: dict[str, list[str | None]] = {}
        for job in jobs:
            name = job.get("name")
            if name in expected:
                observed.setdefault(name, []).append(job.get("conclusion"))
        if set(observed) == expected and all(value == ["success"] for value in observed.values()):
            return str(run_id), attempt
    raise VerificationError(
        f"no successful exact-main Services CI attempt proves {service} build and contract for {sha}"
    )


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: verify-successful-service-ci.py <service> <sha>", file=sys.stderr)
        return 2
    try:
        run_id, attempt = verify_successful_service_ci(
            sys.argv[1],
            sys.argv[2],
            os.environ.get("GITHUB_REPOSITORY", ""),
            os.environ.get("GH_TOKEN", ""),
            os.environ.get("GITHUB_API_URL", "https://api.github.com"),
        )
    except VerificationError as exc:
        print(f"::error::reconcile source refused: {exc}", file=sys.stderr)
        return 1
    print(f"verified Services CI run {run_id} attempt {attempt}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
