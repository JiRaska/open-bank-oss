#!/usr/bin/env python3
"""Resolve newest Services CI artifacts on main without blind pagination."""

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


def github_run(repo: str, token: str, run_id: int) -> dict:
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
    if not isinstance(payload, dict):
        raise ArtifactApiError("GitHub run response is not an object")
    path = payload.get("path")
    if not isinstance(path, str):
        raise ArtifactApiError("GitHub run has no workflow path")
    head_repository = payload.get("head_repository")
    head_repo_name = head_repository.get("full_name") if isinstance(head_repository, dict) else None
    return {
        "path": path,
        "event": payload.get("event"),
        "head_repository": head_repo_name,
        "status": payload.get("status"),
    }


SERVICES_CI = ".github/workflows/services-ci.yml"
# The one artifact of this family that Services CI does not produce: admin-ui is built and
# tested by ci.yml, which uploads the same v1 envelope under this name.
PRODUCERS = {"test-intelligence-openbank-admin-ui": ".github/workflows/ci.yml"}


def producer_of(artifact_name: str) -> str:
    return PRODUCERS.get(artifact_name, SERVICES_CI)


def latest_main_artifact(
    artifact_name: str,
    fetch_page: Callable[[str, int], list[dict]],
    fetch_run: Callable[[int], dict],
    repo: str,
) -> str | None:
    """Use only the artifact's own producer (Services CI for services), never a newer
    targeted provider run with empty suites. `head_branch == "main"` is not provenance on
    its own: a fork pull_request can be named main, so the run must also be a push from
    this repository."""
    producer = producer_of(artifact_name)
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
                run = fetch_run(run_id)
                if (
                    run.get("path") == producer
                    and run.get("event") == "push"
                    and run.get("head_repository") == repo
                    and run.get("status") == "completed"
                ):
                    return str(artifact_id)
        # Exact-name results are newest-first. A short page proves there is no
        # next page; continuing would spend quota to rediscover the same absence.
        if len(artifacts) < PER_PAGE:
            return None
    raise ArtifactApiError(
        f"artifact inventory for {artifact_name} exceeded the bounded {MAX_PAGES}-page lookup"
    )


TEST_REPO = "org/repo"


def run(path: str, event: str = "push", head_repository: str = TEST_REPO,
        status: str = "completed", conclusion: str = "success") -> dict:
    return {
        "path": path,
        "event": event,
        "head_repository": head_repository,
        "status": status,
        "conclusion": conclusion,
    }


def lookup(name: str, fetch, fetch_run) -> str | None:
    return latest_main_artifact(name, fetch, fetch_run, TEST_REPO)


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

        self.assertEqual(lookup("test", fetch, lambda _run_id: run(".github/workflows/services-ci.yml")), "3")
        self.assertEqual(calls, [1])

    def test_short_page_stops_absent_lookup(self) -> None:
        calls: list[int] = []

        def fetch(_name: str, page: int) -> list[dict]:
            calls.append(page)
            return [{"id": 1, "expired": False, "workflow_run": {"head_branch": "feature"}}]

        self.assertIsNone(lookup("test", fetch, lambda _run_id: run(".github/workflows/services-ci.yml")))
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

        self.assertEqual(lookup("test", fetch, lambda _run_id: run(".github/workflows/services-ci.yml")), "101")
        self.assertEqual(calls, [1, 2])

    def test_api_error_is_not_reported_as_absence(self) -> None:
        def fetch(_name: str, _page: int) -> list[dict]:
            raise ArtifactApiError("quota exhausted")

        with self.assertRaisesRegex(ArtifactApiError, "quota exhausted"):
            lookup("test", fetch, lambda _run_id: run(".github/workflows/services-ci.yml"))

    def test_full_final_page_is_unknown_not_absence(self) -> None:
        calls: list[int] = []

        def fetch(_name: str, page: int) -> list[dict]:
            calls.append(page)
            return [
                {"id": value, "expired": False, "workflow_run": {"head_branch": "feature"}}
                for value in range(PER_PAGE)
            ]

        with self.assertRaisesRegex(ArtifactApiError, "exceeded the bounded"):
            lookup("test", fetch, lambda _run_id: run(".github/workflows/services-ci.yml"))
        self.assertEqual(calls, list(range(1, MAX_PAGES + 1)))

    def test_newer_provider_artifact_cannot_replace_services_ci(self) -> None:
        artifacts = [
            {"id": 9, "expired": False, "workflow_run": {"head_branch": "main", "id": 90}},
            {"id": 8, "expired": False, "workflow_run": {"head_branch": "main", "id": 80}},
        ]
        paths = {90: ".github/workflows/verify-provider.yml", 80: ".github/workflows/services-ci.yml"}
        self.assertEqual(lookup("test", lambda *_: artifacts, lambda i: run(paths[i])), "8")

    def test_admin_ui_artifact_is_accepted_from_its_own_producer(self) -> None:
        # `test-intelligence-openbank-admin-ui` is uploaded by ci.yml, never by Services CI.
        # Requiring services-ci.yml for it scanned every page and failed every admin-ui
        # deploy build from #12480 on (exceeded the bounded lookup).
        artifacts = [
            {"id": 9, "expired": False, "workflow_run": {"head_branch": "main", "id": 90}},
            {"id": 8, "expired": False, "workflow_run": {"head_branch": "main", "id": 80}},
        ]
        paths = {90: ".github/workflows/ci.yml", 80: ".github/workflows/services-ci.yml"}
        self.assertEqual(lookup("test-intelligence-openbank-admin-ui",
                                  lambda *_: artifacts, lambda i: run(paths[i])), "9")
        # The producer stays exact for every other artifact: ci.yml cannot stand in for a service.
        self.assertEqual(lookup("test-intelligence-openbank-ledger-service",
                                  lambda *_: artifacts, lambda i: run(paths[i])), "8")

    def test_fork_pull_request_named_main_is_rejected(self) -> None:
        # A fork PR whose branch is "main" runs ci.yml with head_branch == "main".
        forged = {"id": 9, "expired": False, "workflow_run": {"head_branch": "main", "id": 90}}
        genuine = {"id": 8, "expired": False, "workflow_run": {"head_branch": "main", "id": 80}}
        runs = {
            90: run(".github/workflows/ci.yml", "pull_request", "attacker/repo"),
            80: run(".github/workflows/ci.yml", "push"),
        }
        name = "test-intelligence-openbank-admin-ui"
        self.assertEqual(lookup(name, lambda *_: [forged, genuine], runs.__getitem__), "8")
        self.assertIsNone(lookup(name, lambda *_: [forged], runs.__getitem__))

    def test_each_provenance_axis_rejects_alone(self) -> None:
        artifact = {"id": 9, "expired": False, "workflow_run": {"head_branch": "main", "id": 90}}
        ci = ".github/workflows/ci.yml"
        name = "test-intelligence-openbank-admin-ui"
        for forged in (run(ci, "pull_request"), run(ci, "push", "attacker/repo"), run(ci, "workflow_dispatch")):
            self.assertIsNone(lookup(name, lambda *_: [artifact], lambda _i, f=forged: f))
        self.assertEqual(lookup(name, lambda *_: [artifact], lambda _i: run(ci)), "9")

    def test_missing_run_provenance_fails_closed(self) -> None:
        artifact = {"id": 9, "expired": False, "workflow_run": {"head_branch": "main"}}
        with self.assertRaisesRegex(ArtifactApiError, "no run id"):
            lookup("test", lambda *_: [artifact], lambda _: run(".github/workflows/services-ci.yml"))

    def test_newer_incomplete_run_is_skipped_for_older_completed_run(self) -> None:
        for status in ("queued", "in_progress"):
            with self.subTest(status=status):
                artifacts = [
                    {
                        "id": 9,
                        "expired": False,
                        "workflow_run": {"head_branch": "main", "id": 90},
                    },
                    {
                        "id": 8,
                        "expired": False,
                        "workflow_run": {"head_branch": "main", "id": 80},
                    },
                ]
                runs = {
                    90: run(".github/workflows/services-ci.yml", status=status),
                    80: run(".github/workflows/services-ci.yml"),
                }

                self.assertEqual(
                    lookup("test", lambda *_, artifacts=artifacts: artifacts, runs.__getitem__),
                    "8",
                )

    def test_completed_failure_run_remains_valid_evidence_source(self) -> None:
        artifact = {
            "id": 9,
            "expired": False,
            "workflow_run": {"head_branch": "main", "id": 90},
        }

        self.assertEqual(
            lookup(
                "test", lambda *_: [artifact],
                lambda _: run(
                    ".github/workflows/services-ci.yml",
                    conclusion="failure",
                ),
            ),
            "9",
        )

    def test_missing_run_status_is_not_selected(self) -> None:
        artifact = {
            "id": 9,
            "expired": False,
            "workflow_run": {"head_branch": "main", "id": 90},
        }
        incomplete_metadata = run(".github/workflows/services-ci.yml")
        incomplete_metadata.pop("status")

        self.assertIsNone(
            lookup("test", lambda *_: [artifact], lambda _: incomplete_metadata)
        )


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
        run_cache: dict[int, dict] = {}

        def cached_run(run_id: int) -> dict:
            if run_id not in run_cache:
                run_cache[run_id] = github_run(args.repo, token, run_id)
            return run_cache[run_id]

        for name in args.artifact_names:
            artifact_id = latest_main_artifact(
                name,
                lambda artifact_name, page: github_page(args.repo, token, artifact_name, page),
                cached_run,
                args.repo,
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
