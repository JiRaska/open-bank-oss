#!/usr/bin/env python3
"""Resolve newest non-expired main-branch Actions artifacts without blind pagination."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import unittest
from collections import Counter
from collections.abc import Callable
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen

sys.path.insert(0, str(Path(__file__).resolve().parent))
from admin_ui_trace_ipc import Invocation

PER_PAGE = 100
MAX_PAGES = 5
API_ORIGIN = "https://api.github.com"


class SendTrace:
    """Count http.client header sends without retaining request data."""

    def __init__(self, stage: str, auth: str) -> None:
        self.counts: Counter[str] = Counter()
        self.incomplete = False
        self.request_failed = False
        self.installed = False
        self.stage = stage
        self.auth = auth
        self.invocation: Invocation | None = None

    def audit(self, event: str, args: tuple[object, ...]) -> None:
        if event != "http.client.send":
            return
        try:
            connection, data = args
            if not isinstance(data, bytes):
                self.incomplete = True
                return
            first_line = data.split(b"\r\n", 1)[0]
            match = re.match(rb"^([A-Z]+)\s+(\S+)\s+HTTP/\S+$", first_line)
            if not match:
                # This GET-only collector sends no request bodies. An unexpected
                # send shape cannot be treated as a complete zero-count trace.
                self.incomplete = True
                return
            if match.group(1) == b"CONNECT":
                return
            request_target = match.group(2).decode("ascii", errors="ignore")
            host = None
            if request_target.startswith(("http://", "https://")):
                host = urlsplit(request_target).hostname
            if not host:
                host = getattr(connection, "_tunnel_host", None) or getattr(connection, "host", None)
            if not isinstance(host, str) or not host:
                self.counts["unknown"] += 1
                self.incomplete = True
            else:
                self.counts["api" if host.lower() == "api.github.com" else "non_api"] += 1
        except Exception:
            # Audit callbacks must never change the caller's request behavior.
            self.incomplete = True

    def install(self) -> None:
        try:
            self.invocation = Invocation(self.stage, "python_urllib", self.auth)
            sys.addaudithook(self.audit)
            self.installed = True
        except Exception:
            self.incomplete = True

    def write(self, stage: str, auth: str) -> None:
        if self.invocation is None:
            return
        total = sum(self.counts.values())
        complete = (
            self.installed
            and self.invocation.started
            and not self.incomplete
            and total > 0
        )
        reason = None
        if not self.installed:
            reason = "hook_unavailable"
        elif self.incomplete:
            reason = "trace_parse_failure"
        elif self.request_failed and total == 0:
            reason = "pre_send_failure"
        elif total == 0:
            reason = "no_send_observed"
        self.invocation.end(self.counts, complete=complete, reason=reason)


class ArtifactApiError(RuntimeError):
    """The artifact inventory is unavailable, not empty."""


def github_page(
    repo: str,
    token: str,
    artifact_name: str,
    page: int,
    *,
    api_origin: str = API_ORIGIN,
) -> list[dict]:
    query = urlencode({"name": artifact_name, "per_page": PER_PAGE, "page": page})
    request = Request(
        f"{api_origin.rstrip('/')}/repos/{repo}/actions/artifacts?{query}",
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

    def test_full_final_page_is_unknown_not_absence(self) -> None:
        calls: list[int] = []

        def fetch(_name: str, page: int) -> list[dict]:
            calls.append(page)
            return [
                {"id": value, "expired": False, "workflow_run": {"head_branch": "feature"}}
                for value in range(PER_PAGE)
            ]

        with self.assertRaisesRegex(ArtifactApiError, "exceeded the bounded"):
            latest_main_artifact("test", fetch)
        self.assertEqual(calls, list(range(1, MAX_PAGES + 1)))


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

    trace = SendTrace("service_ti", "github")
    trace.install()
    request_failed = False
    try:
        for name in args.artifact_names:
            artifact_id = latest_main_artifact(
                name,
                lambda artifact_name, page: github_page(args.repo, token, artifact_name, page),
            )
            print(artifact_id or "")
    except ArtifactApiError as exc:
        request_failed = True
        print(f"artifact lookup failed closed: {exc}", file=sys.stderr)
        return 2
    except Exception:
        request_failed = True
        raise
    finally:
        trace.request_failed = request_failed
        trace.write("service_ti", "github")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
