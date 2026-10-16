# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""Fixture tests for exact-main Services CI provenance used by reconcile."""

from __future__ import annotations

import importlib.util
import io
import json
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.error import URLError

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
            {"name": f"build ({service}) / {service} (contract)", "conclusion": "success"},
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
        for overrides in ({"head_sha": "b" * 40}, {"head_branch": "feature"}, {"event": "schedule"}):
            with self.subTest(overrides=overrides):
                self.responses[next(iter(self.responses))] = {
                    "workflow_runs": [run_fixture(**overrides)]
                }
                with self.assertRaises(MODULE.VerificationError):
                    self.verify()

    def test_rejects_missing_or_failed_exact_service_child(self):
        cases = [
            {"jobs": [job for job in job_fixture()["jobs"] if "(contract)" not in job["name"]]},
            {
                "jobs": [
                    {**job, "conclusion": "failure"} if "(contract)" in job["name"] else job
                    for job in job_fixture()["jobs"]
                ]
            },
            {
                "jobs": job_fixture()["jobs"]
                + [{"name": f"build ({SERVICE}) / {SERVICE} (contract)", "conclusion": "success"}]
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

    def test_invalid_input_fails_before_api_call(self):
        with self.assertRaises(MODULE.VerificationError):
            MODULE.verify_successful_service_ci(
                SERVICE, "not-a-sha", REPOSITORY, "fixture-token", "https://api.github.test"
            )


if __name__ == "__main__":
    unittest.main(verbosity=2)
