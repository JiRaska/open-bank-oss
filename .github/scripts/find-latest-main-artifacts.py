#!/usr/bin/env python3
"""Resolve newest non-expired main-branch Actions artifacts without blind pagination."""

from __future__ import annotations

import argparse
import json
import os
import sys
import unittest
from collections.abc import Callable
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

PER_PAGE = 100
MAX_PAGES = 5


class ArtifactApiError(RuntimeError):
    """The artifact inventory is unavailable, not empty."""


def github_page(repo: str, token: str, artifact_name: str, page: int) -> list[dict]:
    query = urlencode({"name": artifact_name, "per_page": PER_PAGE, "page": page})
    request = Request(
        f"https://api.github.com/repos/{repo}/actions/artifacts?{query}",
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    try:
        with urlopen(request, timeout=20) as response:
            payload = json.load(response)
    except HTTPError as exc:
        remaining = exc.headers.get("x-ratelimit-remaining", "unknown")
        raise ArtifactApiError(
            f"GitHub artifact API returned HTTP {exc.code} (rate remaining: {remaining})"
        ) from exc
    except (URLError, TimeoutError, json.JSONDecodeError) as exc:
        raise ArtifactApiError(f"GitHub artifact API response is unavailable: {exc}") from exc

    artifacts = payload.get("artifacts") if isinstance(payload, dict) else None
    if not isinstance(artifacts, list):
        raise ArtifactApiError("GitHub artifact API response has no artifacts array")
    return artifacts


def latest_main_artifact(
    artifact_name: str,
    fetch_page: Callable[[str, int], list[dict]],
) -> str | None:
    """Return the newest eligible id; stop once GitHub proves the result set exhausted."""
    for page in range(1, MAX_PAGES + 1):
        artifacts = fetch_page(artifact_name, page)
        for artifact in artifacts:
            workflow_run = artifact.get("workflow_run") or {}
            if not artifact.get("expired", False) and workflow_run.get("head_branch") == "main":
                artifact_id = artifact.get("id")
                if artifact_id is None:
                    raise ArtifactApiError("eligible artifact has no id")
                return str(artifact_id)
        # Exact-name results are newest-first. A short page proves there is no
        # next page; continuing would spend quota to rediscover the same absence.
        if len(artifacts) < PER_PAGE:
            return None
    return None


class LookupTests(unittest.TestCase):
    def test_returns_newest_nonexpired_main_artifact(self) -> None:
        calls: list[int] = []

        def fetch(_name: str, page: int) -> list[dict]:
            calls.append(page)
            return [
                {"id": 1, "expired": False, "workflow_run": {"head_branch": "feature"}},
                {"id": 2, "expired": True, "workflow_run": {"head_branch": "main"}},
                {"id": 3, "expired": False, "workflow_run": {"head_branch": "main"}},
            ]

        self.assertEqual(latest_main_artifact("test", fetch), "3")
        self.assertEqual(calls, [1])

    def test_short_page_stops_absent_lookup(self) -> None:
        calls: list[int] = []

        def fetch(_name: str, page: int) -> list[dict]:
            calls.append(page)
            return [{"id": 1, "expired": False, "workflow_run": {"head_branch": "feature"}}]

        self.assertIsNone(latest_main_artifact("test", fetch))
        self.assertEqual(calls, [1])

    def test_full_page_can_reach_later_main_result(self) -> None:
        calls: list[int] = []

        def fetch(_name: str, page: int) -> list[dict]:
            calls.append(page)
            if page == 1:
                return [
                    {"id": value, "expired": False, "workflow_run": {"head_branch": "feature"}}
                    for value in range(PER_PAGE)
                ]
            return [{"id": 101, "expired": False, "workflow_run": {"head_branch": "main"}}]

        self.assertEqual(latest_main_artifact("test", fetch), "101")
        self.assertEqual(calls, [1, 2])

    def test_api_error_is_not_reported_as_absence(self) -> None:
        def fetch(_name: str, _page: int) -> list[dict]:
            raise ArtifactApiError("quota exhausted")

        with self.assertRaisesRegex(ArtifactApiError, "quota exhausted"):
            latest_main_artifact("test", fetch)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("artifact_names", nargs="*")
    parser.add_argument("--repo", default=os.environ.get("REPO", ""))
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(LookupTests)
        return 0 if unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful() else 1
    token = os.environ.get("GH_TOKEN", "")
    if not args.repo or not token or not args.artifact_names:
        parser.error("--repo, GH_TOKEN and at least one artifact name are required")

    request_count = 0
    found_count = 0

    def fetch(artifact_name: str, page: int) -> list[dict]:
        nonlocal request_count
        request_count += 1
        return github_page(args.repo, token, artifact_name, page)

    try:
        for name in args.artifact_names:
            artifact_id = latest_main_artifact(name, fetch)
            found_count += artifact_id is not None
            print(artifact_id or "")
    except ArtifactApiError as exc:
        print(
            f"artifact lookup failed closed after {request_count} request(s): {exc}",
            file=sys.stderr,
        )
        return 2
    print(
        f"artifact lookup: names={len(args.artifact_names)} requests={request_count} "
        f"found={found_count} absent={len(args.artifact_names) - found_count}",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
