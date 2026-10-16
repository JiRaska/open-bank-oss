#!/usr/bin/env python3
"""Resolve newest Services CI artifacts on main without blind pagination."""

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
        with urlopen(request, timeout=20) as response:  # noqa: S310 - fixed GitHub API origin
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


def github_run_path(repo: str, token: str, run_id: int) -> str:
    request = Request(
        f"https://api.github.com/repos/{repo}/actions/runs/{run_id}",
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    try:
        with urlopen(request, timeout=20) as response:  # noqa: S310 - fixed GitHub API origin
            payload = json.load(response)
    except (HTTPError, URLError, TimeoutError, json.JSONDecodeError) as exc:
        raise ArtifactApiError("GitHub run provenance is unavailable") from exc
    path = payload.get("path") if isinstance(payload, dict) else None
    if not isinstance(path, str):
        raise ArtifactApiError("GitHub run has no workflow path")
    return path


def latest_main_artifact(
    artifact_name: str,
    fetch_page: Callable[[str, int], list[dict]],
    fetch_run_path: Callable[[int], str],
) -> str | None:
    """Use only Services CI, not a newer targeted provider run with empty suites."""
    for page in range(1, MAX_PAGES + 1):
        artifacts = fetch_page(artifact_name, page)
        for artifact in artifacts:
            workflow_run = artifact.get("workflow_run") or {}
            if not artifact.get("expired", False) and workflow_run.get("head_branch") == "main":
                artifact_id = artifact.get("id")
                if artifact_id is None:
                    raise ArtifactApiError("eligible artifact has no id")
                run_id = workflow_run.get("id")
                if not isinstance(run_id, int):
                    raise ArtifactApiError("eligible artifact has no run id")
                if fetch_run_path(run_id) == ".github/workflows/services-ci.yml":
                    return str(artifact_id)
        # Exact-name results are newest-first. A short page proves there is no
        # next page; continuing would spend quota to rediscover the same absence.
        if len(artifacts) < PER_PAGE:
            return None
    raise ArtifactApiError(
        f"artifact inventory for {artifact_name} exceeded the bounded {MAX_PAGES}-page lookup"
    )


class LookupTests(unittest.TestCase):
    def test_returns_newest_nonexpired_main_artifact(self) -> None:
        calls: list[int] = []

        def fetch(_name: str, page: int) -> list[dict]:
            calls.append(page)
            return [
                {"id": 1, "expired": False, "workflow_run": {"head_branch": "feature"}},
                {"id": 2, "expired": True, "workflow_run": {"head_branch": "main"}},
                {"id": 3, "expired": False, "workflow_run": {"head_branch": "main", "id": 30}},
            ]

        self.assertEqual(latest_main_artifact("test", fetch, lambda _run_id: ".github/workflows/services-ci.yml"), "3")
        self.assertEqual(calls, [1])

    def test_short_page_stops_absent_lookup(self) -> None:
        calls: list[int] = []

        def fetch(_name: str, page: int) -> list[dict]:
            calls.append(page)
            return [{"id": 1, "expired": False, "workflow_run": {"head_branch": "feature"}}]

        self.assertIsNone(latest_main_artifact("test", fetch, lambda _run_id: ".github/workflows/services-ci.yml"))
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
            return [{"id": 101, "expired": False, "workflow_run": {"head_branch": "main", "id": 1010}}]

        self.assertEqual(latest_main_artifact("test", fetch, lambda _run_id: ".github/workflows/services-ci.yml"), "101")
        self.assertEqual(calls, [1, 2])

    def test_api_error_is_not_reported_as_absence(self) -> None:
        def fetch(_name: str, _page: int) -> list[dict]:
            raise ArtifactApiError("quota exhausted")

        with self.assertRaisesRegex(ArtifactApiError, "quota exhausted"):
            latest_main_artifact("test", fetch, lambda _run_id: ".github/workflows/services-ci.yml")

    def test_full_final_page_is_unknown_not_absence(self) -> None:
        calls: list[int] = []

        def fetch(_name: str, page: int) -> list[dict]:
            calls.append(page)
            return [
                {"id": value, "expired": False, "workflow_run": {"head_branch": "feature"}}
                for value in range(PER_PAGE)
            ]

        with self.assertRaisesRegex(ArtifactApiError, "exceeded the bounded"):
            latest_main_artifact("test", fetch, lambda _run_id: ".github/workflows/services-ci.yml")
        self.assertEqual(calls, list(range(1, MAX_PAGES + 1)))

    def test_newer_provider_artifact_cannot_replace_services_ci(self) -> None:
        artifacts = [
            {"id": 9, "expired": False, "workflow_run": {"head_branch": "main", "id": 90}},
            {"id": 8, "expired": False, "workflow_run": {"head_branch": "main", "id": 80}},
        ]
        paths = {90: ".github/workflows/verify-provider.yml", 80: ".github/workflows/services-ci.yml"}
        self.assertEqual(latest_main_artifact("test", lambda *_: artifacts, paths.__getitem__), "8")

    def test_missing_run_provenance_fails_closed(self) -> None:
        artifact = {"id": 9, "expired": False, "workflow_run": {"head_branch": "main"}}
        with self.assertRaisesRegex(ArtifactApiError, "no run id"):
            latest_main_artifact("test", lambda *_: [artifact], lambda _: ".github/workflows/services-ci.yml")


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

    try:
        run_paths: dict[int, str] = {}

        def cached_run_path(run_id: int) -> str:
            if run_id not in run_paths:
                run_paths[run_id] = github_run_path(args.repo, token, run_id)
            return run_paths[run_id]

        for name in args.artifact_names:
            artifact_id = latest_main_artifact(
                name,
                lambda artifact_name, page: github_page(args.repo, token, artifact_name, page),
                cached_run_path,
            )
            print(artifact_id or "")
    except ArtifactApiError as exc:
        print(f"artifact lookup failed closed: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
