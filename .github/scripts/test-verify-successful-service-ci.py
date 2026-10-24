# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""Fixture tests for exact-main Services CI provenance used by reconcile."""

from __future__ import annotations

import importlib.util
import io
import json
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.error import URLError
from urllib.parse import parse_qs, urlsplit

SCRIPT = Path(__file__).with_name("verify-successful-service-ci.py")
SPEC = importlib.util.spec_from_file_location("verify_services_ci_source", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

SHA = "a" * 40
SERVICE = "openbank-pension-service"
REPOSITORY = "JiRaska/open-bank-oss"
RUN_ID = 12345
ATTEMPT = 2


class FixtureResponse(io.BytesIO):
    def __init__(self, value: dict, headers: dict[str, str] | None = None):
        super().__init__(json.dumps(value).encode())
        self.headers = headers or {}

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        self.close()


def run_fixture(**overrides):
    run = {
        "id": RUN_ID,
        "run_attempt": ATTEMPT,
        "head_sha": SHA,
        "head_branch": "main",
        "event": "push",
        "status": "completed",
        "conclusion": "success",
    }
    run.update(overrides)
    return run


def job_fixture(service: str = SERVICE) -> dict:
    return {
        "jobs": [
            {"name": "all-green", "conclusion": "success"},
            {"name": f"build ({service}) / {service} (build)", "conclusion": "success"},
            {
                "name": f"build ({service}) / {service} (contract)",
                "conclusion": "success",
            },
            {"name": "build (other) / other (build)", "conclusion": "failure"},
        ]
    }


class VerifySuccessfulServiceCiTest(unittest.TestCase):
    def setUp(self):
        self.responses = {
            f"/repos/{REPOSITORY}/actions/workflows/services-ci.yml/runs": {
                "workflow_runs": [run_fixture()]
            },
            f"/repos/{REPOSITORY}/actions/runs/{RUN_ID}/attempts/{ATTEMPT}/jobs": job_fixture(),
        }

    def verify(self):
        requested: list[str] = []

        def fake_urlopen(request, timeout):
            del timeout
            url = request.full_url
            requested.append(url)
            path = url.split("?", 1)[0].replace("https://api.github.test", "")
            value = self.responses.get(path)
            if value is None:
                raise AssertionError(f"unexpected API request: {url}")
            return FixtureResponse(value)

        with patch.object(MODULE, "urlopen", fake_urlopen):
            result = MODULE.verify_successful_service_ci(
                SERVICE, SHA, REPOSITORY, "fixture-token", "https://api.github.test"
            )
        return result, requested

    def test_accepts_exact_successful_main_attempt_and_service_children(self):
        result, requested = self.verify()
        self.assertEqual(result, (str(RUN_ID), ATTEMPT))
        self.assertEqual(len(requested), 2)
        self.assertIn(f"head_sha={SHA}", requested[0])
        self.assertIn(f"attempts/{ATTEMPT}/jobs", requested[1])

    def test_rejects_wrong_sha_or_non_main_run(self):
        for overrides in (
            {"head_sha": "b" * 40},
            {"head_branch": "feature"},
            {"event": "schedule"},
        ):
            with self.subTest(overrides=overrides):
                self.responses[next(iter(self.responses))] = {
                    "workflow_runs": [run_fixture(**overrides)]
                }
                with self.assertRaises(MODULE.VerificationError):
                    self.verify()

    def test_rejects_missing_or_failed_exact_service_child(self):
        cases = [
            {
                "jobs": [
                    job
                    for job in job_fixture()["jobs"]
                    if "(contract)" not in job["name"]
                ]
            },
            {
                "jobs": [
                    {**job, "conclusion": "failure"}
                    if "(contract)" in job["name"]
                    else job
                    for job in job_fixture()["jobs"]
                ]
            },
            {
                "jobs": job_fixture()["jobs"]
                + [
                    {
                        "name": f"build ({SERVICE}) / {SERVICE} (contract)",
                        "conclusion": "success",
                    }
                ]
            },
        ]
        for value in cases:
            with self.subTest(value=value):
                self.responses[next(reversed(self.responses))] = value
                with self.assertRaises(MODULE.VerificationError):
                    self.verify()

    def test_api_failure_is_not_evidence(self):
        def fail_urlopen(_request, timeout):
            del timeout
            raise URLError("unavailable")

        with (
            patch.object(MODULE, "urlopen", fail_urlopen),
            self.assertRaises(MODULE.VerificationError),
        ):
            MODULE.verify_successful_service_ci(
                SERVICE, SHA, REPOSITORY, "fixture-token", "https://api.github.test"
            )

    def test_pagination_cannot_send_token_to_another_origin(self):
        calls: list[str] = []

        def fake_urlopen(request, timeout):
            del timeout
            calls.append(request.full_url)
            return FixtureResponse(
                {"workflow_runs": []},
                {"Link": '<https://attacker.invalid/next?page=2>; rel="next"'},
            )

        with (
            patch.object(MODULE, "urlopen", fake_urlopen),
            self.assertRaises(MODULE.VerificationError),
        ):
            MODULE._json_pages("https://api.github.test/runs?page=1", "fixture-token")
        self.assertEqual(len(calls), 1)

    def test_repeated_pagination_url_fails_closed(self):
        calls: list[str] = []
        first = "https://api.github.test/runs?page=1"
        second = "https://api.github.test/runs?page=2"

        def fake_urlopen(request, timeout):
            del timeout
            calls.append(request.full_url)
            next_url = second if request.full_url == first else first
            return FixtureResponse(
                {"workflow_runs": []}, {"Link": f'<{next_url}>; rel="next"'}
            )

        with (
            patch.object(MODULE, "urlopen", fake_urlopen),
            self.assertRaisesRegex(MODULE.VerificationError, "repeated a page URL"),
        ):
            MODULE._json_pages(first, "fixture-token")
        self.assertEqual(calls, [first, second])

    def test_expired_resolver_budget_fails_before_api_call(self):
        with (
            patch.object(MODULE, "urlopen") as request,
            self.assertRaisesRegex(MODULE.VerificationError, "time budget"),
        ):
            MODULE._json_pages(
                "https://api.github.test/runs?page=1",
                "fixture-token",
                deadline=0,
            )
        request.assert_not_called()

    def test_invalid_input_fails_before_api_call(self):
        with self.assertRaises(MODULE.VerificationError):
            MODULE.verify_successful_service_ci(
                SERVICE,
                "not-a-sha",
                REPOSITORY,
                "fixture-token",
                "https://api.github.test",
            )


class ResolveReconcileSourceTest(unittest.TestCase):
    TIP = "f" * 40
    OLDER = "a" * 40
    OLDEST = "b" * 40

    def run_record(self, run_id: int, sha: str, created: str, **overrides) -> dict:
        return {
            "id": run_id,
            "run_attempt": 1,
            "head_sha": sha,
            "head_branch": "main",
            "event": "push",
            "status": "completed",
            "conclusion": "success",
            "created_at": created,
            **overrides,
        }

    def test_push_prefilter_skips_inert_changes_and_keeps_unknown_inputs(self):
        parent = subprocess.CompletedProcess(
            args=[], returncode=0, stdout=f"{'a' * 40}\n", stderr=""
        )
        docs = subprocess.CompletedProcess(
            args=[], returncode=0, stdout="docs/operations.md\n", stderr=""
        )
        with (
            patch.object(MODULE.subprocess, "run", side_effect=[parent, docs]),
            patch.object(MODULE, "_tree_equivalent", return_value=True) as equivalent,
        ):
            self.assertFalse(MODULE._push_might_build_service(SERVICE, self.TIP))
        equivalent.assert_called_once_with(SERVICE, "a" * 40, self.TIP, deadline=None)

        workflow = subprocess.CompletedProcess(
            args=[],
            returncode=0,
            stdout=".github/workflows/services-ci.yml\n",
            stderr="",
        )
        with (
            patch.object(MODULE.subprocess, "run", side_effect=[parent, workflow]),
            patch.object(MODULE, "_tree_equivalent") as equivalent,
        ):
            self.assertTrue(MODULE._push_might_build_service(SERVICE, self.TIP))
        equivalent.assert_not_called()

        unknown = subprocess.CompletedProcess(
            args=[], returncode=0, stdout="unclassified/build-input.txt\n", stderr=""
        )
        with (
            patch.object(MODULE.subprocess, "run", side_effect=[parent, unknown]),
            patch.object(MODULE, "_tree_equivalent", return_value=False),
        ):
            self.assertTrue(MODULE._push_might_build_service(SERVICE, self.TIP))

    def test_batch_reads_jobs_once_for_multiple_reconcile_services(self):
        second_service = "openbank-ledger-service"
        run = self.run_record(
            37, self.OLDER, "2026-10-17T12:00:00Z", event="workflow_dispatch"
        )
        jobs = job_fixture()["jobs"][:3] + [
            {
                "name": f"build ({second_service}) / {second_service} (build)",
                "conclusion": "success",
            },
            {
                "name": f"build ({second_service}) / {second_service} (contract)",
                "conclusion": "success",
            },
        ]
        requested: list[str] = []

        def fake_urlopen(request, timeout):
            del timeout
            url = request.full_url
            requested.append(url)
            path = urlsplit(url).path
            if path.endswith("/services-ci.yml/runs"):
                return FixtureResponse({"workflow_runs": [run]})
            if path.endswith("/runs/37/attempts/1/jobs"):
                return FixtureResponse({"jobs": jobs})
            raise AssertionError(f"unexpected API request: {url}")

        with (
            patch.object(MODULE, "urlopen", fake_urlopen),
            patch.object(MODULE, "_expected_verification_modules", return_value=set()),
            patch.object(MODULE, "_is_ancestor", return_value=True),
            patch.object(MODULE, "_tree_equivalent", return_value=True),
            patch.object(MODULE, "_exact_pact_exists", return_value=True),
        ):
            resolved = MODULE.resolve_reconcile_service_sources(
                [SERVICE, second_service],
                self.TIP,
                REPOSITORY,
                "fixture-token",
                "https://api.github.test",
            )

        self.assertEqual(resolved[SERVICE]["sha"], self.OLDER)
        self.assertEqual(resolved[second_service]["sha"], self.OLDER)
        self.assertEqual(len(requested), 2, "one run-list and one shared jobs request")

    def test_failed_verification_metadata_does_not_prove_the_service(self):
        run = self.run_record(36, self.OLDER, "2026-10-17T12:00:00Z")
        jobs = job_fixture()["jobs"] + [
            {"name": "verification-metadata (openbank-libs)", "conclusion": "failure"}
        ]
        with (
            patch.object(MODULE, "_json_pages", return_value=jobs),
            patch.object(MODULE, "_expected_verification_modules", return_value=set()),
        ):
            proven = MODULE._jobs_prove_services(
                run, {SERVICE}, REPOSITORY, "fixture-token", "https://api.github.test"
            )
        self.assertEqual(proven, set())

    def test_missing_required_verification_metadata_job_is_not_vacuously_green(self):
        run = self.run_record(35, self.OLDER, "2026-10-17T12:00:00Z")
        with (
            patch.object(MODULE, "_json_pages", return_value=job_fixture()["jobs"]),
            patch.object(
                MODULE,
                "_expected_verification_modules",
                return_value={"openbank-libs-domain"},
            ),
        ):
            proven = MODULE._jobs_prove_services(
                run, {SERVICE}, REPOSITORY, "fixture-token", "https://api.github.test"
            )
        self.assertEqual(proven, set())

    def test_push_metadata_matrix_matches_the_workflow_deriver(self):
        run = self.run_record(34, self.OLDER, "2026-10-17T12:00:00Z")
        changed = subprocess.CompletedProcess(
            args=[],
            returncode=0,
            stdout=(
                "openbank-ledger-service/build.gradle.kts\n"
                "openbank-libs-domain/build.gradle.kts\n"
                "openbank-ledger-service/src/main/Ledger.kt\n"
                "docs/a.md\n"
            ),
            stderr="",
        )
        with patch.object(MODULE.subprocess, "run", return_value=changed) as git:
            modules = MODULE._expected_verification_modules(run)
        self.assertEqual(modules, {"openbank-ledger-service", "openbank-libs-domain"})
        self.assertEqual(
            git.call_args.args[0],
            ["git", "diff", "--name-only", f"{self.OLDER}^", self.OLDER],
        )

    def test_paginates_and_chooses_newest_service_success_even_if_unrelated_job_failed(
        self,
    ):
        runs_url = f"https://api.github.test/repos/{REPOSITORY}/actions/workflows/services-ci.yml/runs"
        first_page = {
            "workflow_runs": [
                self.run_record(
                    30, "c" * 40, "2026-10-17T12:00:00Z", conclusion="failure"
                ),
                self.run_record(29, "d" * 40, "2026-10-17T11:00:00Z"),
            ]
        }
        second_page = {
            "workflow_runs": [
                self.run_record(28, self.OLDER, "2026-10-17T10:00:00Z"),
                self.run_record(
                    27, self.OLDEST, "2026-10-17T09:00:00Z", conclusion="failure"
                ),
            ]
        }
        requested: list[str] = []

        def fake_urlopen(request, timeout):
            del timeout
            url = request.full_url
            requested.append(url)
            parsed = urlsplit(url)
            query = parse_qs(parsed.query)
            page = query.get("page", ["1"])[0]
            if parsed.path.endswith("/services-ci.yml/runs"):
                if page == "1":
                    return FixtureResponse(
                        first_page,
                        {
                            "Link": f'<{runs_url}?branch=main&per_page=100&page=2>; rel="next"'
                        },
                    )
                if page == "2":
                    return FixtureResponse(second_page)
            if parsed.path.endswith("/runs/30/attempts/1/jobs"):
                return FixtureResponse(job_fixture())
            if parsed.path.endswith("/runs/29/attempts/1/jobs"):
                return FixtureResponse(
                    {"jobs": [{"name": "unrelated", "conclusion": "success"}]}
                )
            if parsed.path.endswith("/runs/28/attempts/1/jobs"):
                return FixtureResponse(job_fixture())
            if parsed.path.endswith("/runs/27/attempts/1/jobs"):
                return FixtureResponse(job_fixture())
            raise AssertionError(f"unexpected API request: {url}")

        with (
            patch.object(MODULE, "urlopen", fake_urlopen),
            patch.object(MODULE, "_expected_verification_modules", return_value=set()),
            patch.object(
                MODULE,
                "_is_ancestor",
                side_effect=lambda source, _tip, **_kwargs: source != "d" * 40,
            ) as ancestor,
            patch.object(MODULE, "_tree_equivalent", return_value=True) as equivalent,
            patch.object(
                MODULE,
                "_exact_pact_exists",
                side_effect=lambda _service, sha, **_kwargs: sha == self.OLDEST,
            ) as pact,
        ):
            result = MODULE.resolve_reconcile_service_source(
                SERVICE,
                self.TIP,
                REPOSITORY,
                "fixture-token",
                "https://api.github.test",
            )

        self.assertEqual(result, (self.OLDEST, "27", 1))
        self.assertTrue(any("page=2" in url for url in requested))
        self.assertTrue(
            any(args.args == ("d" * 40, self.TIP) for args in ancestor.call_args_list)
        )
        self.assertEqual(
            equivalent.call_args_list[-1].args, (SERVICE, self.OLDEST, self.TIP)
        )
        self.assertEqual(
            [entry.args[:2] for entry in pact.call_args_list],
            [(SERVICE, "c" * 40), (SERVICE, self.OLDER), (SERVICE, self.OLDEST)],
        )

    def test_rejects_non_main_and_invalid_runs_without_considering_their_jobs(self):
        body = {
            "workflow_runs": [
                self.run_record(
                    31, "c" * 40, "2026-10-17T12:00:00Z", head_branch="feature"
                ),
                self.run_record(
                    32, self.OLDER, "2026-10-17T11:00:00Z", event="schedule"
                ),
            ]
        }

        def fake_urlopen(request, timeout):
            del timeout
            if urlsplit(request.full_url).path.endswith("/services-ci.yml/runs"):
                return FixtureResponse(body)
            raise AssertionError("ineligible run must not fetch jobs")

        with (
            patch.object(MODULE, "urlopen", fake_urlopen),
            patch.object(MODULE, "_expected_verification_modules", return_value=set()),
            patch.object(MODULE, "_is_ancestor", return_value=True),
            patch.object(MODULE, "_tree_equivalent", return_value=True),
            self.assertRaises(MODULE.VerificationError),
        ):
            MODULE.resolve_reconcile_service_source(
                SERVICE,
                self.TIP,
                REPOSITORY,
                "fixture-token",
                "https://api.github.test",
            )

    def test_changed_tree_and_missing_exact_pact_are_not_reused(self):
        run = self.run_record(33, self.OLDER, "2026-10-17T12:00:00Z")

        def fake_urlopen(request, timeout):
            del timeout
            if urlsplit(request.full_url).path.endswith("/services-ci.yml/runs"):
                return FixtureResponse({"workflow_runs": [run]})
            if urlsplit(request.full_url).path.endswith("/runs/33/attempts/1/jobs"):
                return FixtureResponse(job_fixture())
            raise AssertionError(f"unexpected API request: {request.full_url}")

        with (
            patch.object(MODULE, "urlopen", fake_urlopen),
            patch.object(MODULE, "_is_ancestor", return_value=True),
            patch.object(MODULE, "_tree_equivalent", return_value=False),
            patch.object(MODULE, "_exact_pact_exists", return_value=False) as pact,
            self.assertRaises(MODULE.VerificationError),
        ):
            MODULE.resolve_reconcile_service_source(
                SERVICE,
                self.TIP,
                REPOSITORY,
                "fixture-token",
                "https://api.github.test",
            )
        pact.assert_not_called()

    def test_equivalent_source_without_exact_pact_is_not_reused(self):
        run = self.run_record(35, self.OLDER, "2026-10-17T12:00:00Z")

        def fake_urlopen(request, timeout):
            del timeout
            path = urlsplit(request.full_url).path
            if path.endswith("/services-ci.yml/runs"):
                return FixtureResponse({"workflow_runs": [run]})
            if path.endswith("/runs/35/attempts/1/jobs"):
                return FixtureResponse(job_fixture())
            raise AssertionError(f"unexpected API request: {request.full_url}")

        with (
            patch.object(MODULE, "urlopen", fake_urlopen),
            patch.object(MODULE, "_is_ancestor", return_value=True),
            patch.object(MODULE, "_tree_equivalent", return_value=True),
            patch.object(MODULE, "_exact_pact_exists", return_value=False),
            self.assertRaises(MODULE.VerificationError),
        ):
            MODULE.resolve_reconcile_service_source(
                SERVICE,
                self.TIP,
                REPOSITORY,
                "fixture-token",
                "https://api.github.test",
            )

    def test_failed_service_child_and_api_failure_are_not_evidence(self):
        run = self.run_record(34, self.OLDER, "2026-10-17T12:00:00Z")
        failed = job_fixture()
        contract = next(job for job in failed["jobs"] if "(contract)" in job["name"])
        contract["conclusion"] = "failure"

        def fake_urlopen(request, timeout):
            del timeout
            path = urlsplit(request.full_url).path
            if path.endswith("/services-ci.yml/runs"):
                return FixtureResponse({"workflow_runs": [run]})
            if path.endswith("/runs/34/attempts/1/jobs"):
                return FixtureResponse(failed)
            raise AssertionError(f"unexpected API request: {request.full_url}")

        with (
            patch.object(MODULE, "urlopen", fake_urlopen),
            patch.object(MODULE, "_is_ancestor", return_value=True),
            patch.object(MODULE, "_tree_equivalent", return_value=True),
            patch.object(MODULE, "_exact_pact_exists") as pact,
            self.assertRaises(MODULE.VerificationError),
        ):
            MODULE.resolve_reconcile_service_source(
                SERVICE,
                self.TIP,
                REPOSITORY,
                "fixture-token",
                "https://api.github.test",
            )
        pact.assert_not_called()

    def test_skips_latest_run_without_service_jobs_and_uses_older_successful_run(self):
        latest = self.run_record(36, self.OLDER, "2026-10-17T12:00:00Z")
        previous = self.run_record(35, self.OLDER, "2026-10-17T11:00:00Z")

        def fake_urlopen(request, timeout):
            del timeout
            path = urlsplit(request.full_url).path
            if path.endswith("/services-ci.yml/runs"):
                return FixtureResponse({"workflow_runs": [latest, previous]})
            if path.endswith("/runs/36/attempts/1/jobs"):
                return FixtureResponse(
                    {"jobs": [{"name": "other service", "conclusion": "success"}]}
                )
            if path.endswith("/runs/35/attempts/1/jobs"):
                return FixtureResponse(job_fixture())
            raise AssertionError(f"unexpected API request: {request.full_url}")

        with (
            patch.object(MODULE, "urlopen", fake_urlopen),
            patch.object(MODULE, "_expected_verification_modules", return_value=set()),
            patch.object(MODULE, "_is_ancestor", return_value=True),
            patch.object(MODULE, "_tree_equivalent", return_value=True),
            patch.object(MODULE, "_exact_pact_exists", return_value=True),
        ):
            result = MODULE.resolve_reconcile_service_source(
                SERVICE,
                self.TIP,
                REPOSITORY,
                "fixture-token",
                "https://api.github.test",
            )
        self.assertEqual(result, (self.OLDER, "35", 1))

    def test_resolver_api_failure_is_a_refusal(self):
        def fail_urlopen(_request, timeout):
            del timeout
            raise URLError("unavailable")

        with (
            patch.object(MODULE, "urlopen", fail_urlopen),
            self.assertRaises(MODULE.VerificationError),
        ):
            MODULE.resolve_reconcile_service_source(
                SERVICE,
                self.TIP,
                REPOSITORY,
                "fixture-token",
                "https://api.github.test",
            )


if __name__ == "__main__":
    unittest.main(verbosity=2)
