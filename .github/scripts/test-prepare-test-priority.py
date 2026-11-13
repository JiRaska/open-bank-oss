#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Trusted evidence ordering, bounded retrieval and safe fallback regressions."""
from __future__ import annotations

import contextlib
import copy
from datetime import datetime, timedelta, timezone
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location("prepare_priority", Path(__file__).with_name("prepare-test-priority.py"))
assert SPEC and SPEC.loader
PRIORITY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PRIORITY)
COMPONENT = PRIORITY.COMPONENT
NOW = datetime.now(timezone.utc)
SHA = "a" * 40
REPO = "org/repo"


def when(minutes):
    return (NOW + timedelta(minutes=minutes)).isoformat()


def case(name, duration, state="passed", index=1):
    return {"fingerprint": f"{index:024x}", "kind": "unit", "classname": name,
            "name": f"test {index}", "state": state, "durationMs": duration}


def archive(envelope, extra=None):
    result = io.BytesIO()
    with zipfile.ZipFile(result, "w") as zipped:
        zipped.writestr("run.json", json.dumps(envelope))
        for name, content in (extra or {}).items():
            zipped.writestr(name, content)
    return result.getvalue()


class PriorityTests(unittest.TestCase):
    def setUp(self):
        self.run = {"id": 42, "run_attempt": 2, "head_sha": SHA, "head_branch": "main",
                    "event": "push", "status": "completed", "path": ".github/workflows/services-ci.yml",
                    "head_repository": {"full_name": REPO}, "run_started_at": when(-120), "updated_at": when(-58)}
        self.envelope = {"schemaVersion": 1, "component": COMPONENT, "suites": [], "coverage": None,
                         "testInfrastructure": {"declared": [], "observed": []},
                         "run": {"id": "42", "attempt": 2, "commit": SHA, "branch": "main",
                                 "workflow": "Services CI", "url": "https://github.com/org/repo/actions/runs/42",
                                 "observedAt": when(-60)},
                         "testCases": [case("com.openbank.context.PassedTest", 10)]}
        self.artifact = {"id": 99, "name": f"test-intelligence-{COMPONENT}", "expired": False,
                         "created_at": when(-59), "workflow_run": {"id": 42, "head_branch": "main", "head_sha": SHA}}
        self.calls = []

    def prepare(self, **kwargs):
        def page(name, number):
            self.calls.append(("page", number))
            return [self.artifact]
        def run(number):
            self.calls.append(("run", number))
            return self.run
        def get_archive(number):
            self.calls.append(("archive", number))
            return archive(self.envelope)
        return PRIORITY.prepare(REPO, COMPONENT, "test-token", now=NOW, **{
            "fetch_page": page, "fetch_run": run, "fetch_archive": get_archive, **kwargs})

    def test_failed_classes_first_then_total_duration_then_name(self):
        self.envelope["testCases"] = [case("com.openbank.SlowFail", 100, "failed", 1),
                                      case("com.openbank.FastFail", 10, "failed", 2),
                                      case("com.openbank.FastFail", 10, "passed", 3),
                                      case("com.openbank.FastPass", 1, "passed", 4),
                                      case("com.openbank.SlowPass", 200, "passed", 5),
                                      case("com.openbank.Skipped", 0, "skipped", 6)]
        result = self.prepare()
        self.assertEqual(result["classes"], ["com.openbank.FastFail", "com.openbank.SlowFail", "com.openbank.FastPass", "com.openbank.SlowPass"])
        self.assertEqual(result["generatedAt"], self.envelope["run"]["observedAt"])
        self.assertEqual(set(result), {"schemaVersion", "component", "generatedAt", "classes"})
        self.assertEqual(self.calls, [("page", 1), ("run", 42), ("archive", 99)])

    def test_validated_retry_failure_is_also_prioritized_and_ties_are_deterministic(self):
        retry = case("com.openbank.Retry", 20)
        retry.update(retryFlaky=True, failedAttemptCount=1, failedAttemptDurationMs=10)
        self.envelope["testCases"] = [case("com.openbank.Zed", 10, index=2), retry,
                                      case("com.openbank.Alpha", 10, index=3)]
        expected = ["com.openbank.Retry", "com.openbank.Alpha", "com.openbank.Zed"]
        self.assertEqual(self.prepare()["classes"], expected)
        self.envelope["testCases"].reverse()
        self.assertEqual(self.prepare()["classes"], expected)

    def test_empty_and_all_skipped_have_no_priority(self):
        for cases in ([], [case("com.openbank.Skipped", 0, "skipped")]):
            self.envelope["testCases"] = cases
            with self.assertRaises(PRIORITY.NoEvidence):
                self.prepare()

    def test_stale_observation_not_refreshed_by_new_artifact_timestamp(self):
        self.envelope["run"]["observedAt"] = (NOW - timedelta(days=8)).isoformat()
        self.run["run_started_at"] = (NOW - timedelta(days=9)).isoformat()
        with self.assertRaisesRegex(PRIORITY.NoEvidence, "stale"):
            self.prepare()

    def test_exact_freshness_boundary_and_future_skew(self):
        for delta, accepted in ((timedelta(days=-7), True), (timedelta(days=-7, seconds=-1), False),
                                (timedelta(minutes=5), True), (timedelta(minutes=5, seconds=1), False)):
            envelope = copy.deepcopy(self.envelope)
            envelope["run"]["observedAt"] = (NOW + delta).isoformat()
            with self.subTest(delta=delta):
                if accepted:
                    self.assertEqual(PRIORITY.manifest(envelope, COMPONENT, NOW)["generatedAt"], envelope["run"]["observedAt"])
                else:
                    with self.assertRaises(PRIORITY.NoEvidence):
                        PRIORITY.manifest(envelope, COMPONENT, NOW)

    def test_invalid_class_names_and_durations_fall_back(self):
        for field, value in (("classname", "not fully qualified"), ("classname", "../run"),
                             ("durationMs", True), ("durationMs", float("nan")), ("durationMs", -1)):
            envelope = copy.deepcopy(self.envelope)
            envelope["testCases"][0][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(PRIORITY.NoEvidence):
                PRIORITY.manifest(envelope, COMPONENT, NOW)

    def test_malformed_envelope_rejected_by_shared_validator(self):
        self.envelope["testCases"][0]["privateField"] = "not allowed"
        with self.assertRaises(PRIORITY.EVIDENCE.CollectionError):
            self.prepare()

    def test_component_commit_run_and_future_attempt_mismatches_rejected(self):
        changes = (("component", "openbank-other-service"), ("commit", "b" * 40), ("id", "43"), ("attempt", 3))
        original = copy.deepcopy(self.envelope)
        for field, value in changes:
            self.envelope = copy.deepcopy(original)
            if field == "component":
                self.envelope[field] = value
            else:
                self.envelope["run"][field] = value
            with self.subTest(field=field), self.assertRaises(PRIORITY.EVIDENCE.CollectionError):
                self.prepare()

    def test_fork_or_wrong_producer_not_trusted(self):
        for field, value in (("head_repository", {"full_name": "fork/repo"}), ("event", "pull_request"),
                             ("path", ".github/workflows/other.yml"), ("status", "in_progress")):
            original = self.run[field]
            self.run[field] = value
            with self.subTest(field=field), self.assertRaisesRegex(PRIORITY.NoEvidence, "no retained"):
                self.prepare()
            self.run[field] = original
        self.assertFalse(any(call[0] == "archive" for call in self.calls))

    def test_same_attempt_observation_must_follow_attempt_start(self):
        self.run["run_started_at"] = when(-59)
        with self.assertRaisesRegex(PRIORITY.NoEvidence, "predates"):
            self.prepare()

    def test_prior_attempt_resolved_exactly_once(self):
        self.envelope["run"]["attempt"] = 1
        earlier = self.run | {"run_attempt": 1}
        self.run.update(run_started_at=when(-30), updated_at=when(-10))
        attempts = []
        def get_attempt(run_id, number):
            attempts.append((run_id, number))
            return earlier
        self.assertEqual(self.prepare(fetch_attempt=get_attempt)["classes"], ["com.openbank.context.PassedTest"])
        self.assertEqual(attempts, [(42, 1)])
        with self.assertRaises(PRIORITY.EVIDENCE.CollectionError):
            self.prepare(fetch_attempt=lambda _id, _attempt: earlier | {"head_sha": "b" * 40})

    def test_archive_traversal_and_duplicate_envelope_rejected_without_staging(self):
        for extra in ({"../unsafe": "x"}, {"nested/run.json": json.dumps(self.envelope)}):
            with self.subTest(extra=extra), self.assertRaises(PRIORITY.EVIDENCE.CollectionError):
                self.prepare(fetch_archive=lambda _id, extra=extra: archive(self.envelope, extra))

    def test_lookup_bound_and_no_blind_archive_downloads(self):
        def many(_name, _page):
            return [self.artifact | {"id": i, "workflow_run": {"id": i, "head_branch": "main"}} for i in range(1, 101)]
        calls = []
        def untrusted(rid):
            calls.append(rid)
            return self.run | {"id": rid, "event": "pull_request"}
        with self.assertRaisesRegex(PRIORITY.NoEvidence, "budget"):
            self.prepare(fetch_page=many, fetch_run=untrusted)
        self.assertEqual(len(calls), PRIORITY.MAX_RUN_LOOKUPS)
        self.assertFalse(any(call[0] == "archive" for call in self.calls))

    def test_inventory_stops_after_five_pages(self):
        pages = []
        def many(_name, number):
            pages.append(number)
            return [self.artifact | {"id": number * 100 + i, "expired": True} for i in range(100)]
        with self.assertRaises(PRIORITY.FINDER.ArtifactApiError):
            self.prepare(fetch_page=many)
        self.assertEqual(pages, [1, 2, 3, 4, 5])

    def test_stale_artifact_and_wrong_artifact_identity_do_not_download(self):
        for update in ({"created_at": (NOW - timedelta(days=8)).isoformat()},
                       {"name": "other-artifact"}, {"workflow_run": {"id": 42, "head_branch": "main", "head_sha": "b" * 40}}):
            old = self.artifact
            self.artifact = old | update
            with self.subTest(update=update), self.assertRaises(PRIORITY.NoEvidence):
                self.prepare()
            self.artifact = old
        self.assertFalse(any(call[0] == "archive" for call in self.calls))

    def test_class_and_case_limits_are_not_silent_truncation(self):
        with patch.object(PRIORITY, "MAX_CLASSES", 1):
            self.envelope["testCases"].append(case("com.openbank.Another", 1, index=2))
            with self.assertRaises(PRIORITY.NoEvidence):
                self.prepare()
        with patch.object(PRIORITY, "MAX_CASES", 1), self.assertRaises(PRIORITY.NoEvidence):
            self.prepare()

    def test_manifest_byte_limit_counts_compact_json_and_final_newline(self):
        self.assertEqual(PRIORITY.MAX_MANIFEST_BYTES, 256 * 1024)
        value = self.prepare()
        encoded = (json.dumps(value, separators=(",", ":"), sort_keys=True) + "\n").encode("utf-8")
        with patch.object(PRIORITY, "MAX_MANIFEST_BYTES", len(encoded)):
            self.assertEqual(PRIORITY.encode_manifest(self.prepare()), encoded)
        with patch.object(PRIORITY, "MAX_MANIFEST_BYTES", len(encoded) - 1):
            with self.assertRaisesRegex(PRIORITY.NoEvidence, "byte limit"):
                self.prepare()

    def test_valid_class_count_can_exceed_byte_limit_and_cli_keeps_default_order(self):
        classes = [f"com.openbank.{'X' * 490}{index}" for index in range(600)]
        self.envelope["testCases"] = [case(name, 1, index=index) for index, name in enumerate(classes)]
        with self.assertRaisesRegex(PRIORITY.NoEvidence, "byte limit"):
            self.prepare()
        value = {"schemaVersion": 1, "component": COMPONENT, "generatedAt": when(-60), "classes": classes}
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "priority.json"
            out.write_text("old hints")
            stderr = io.StringIO()
            with patch.object(PRIORITY, "prepare", return_value=value), contextlib.redirect_stderr(stderr):
                self.assertEqual(PRIORITY.main(["--repo", REPO, "--component", COMPONENT, "--out", str(out)]), 0)
            self.assertFalse(out.exists())
            self.assertEqual(list(out.parent.iterdir()), [])
            self.assertIn("byte limit", stderr.getvalue())
            self.assertIn("default full-suite order", stderr.getvalue())

    def test_cli_unavailable_evidence_removes_previous_manifest_and_returns_zero(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "priority.json"
            for error in (PRIORITY.NoEvidence("absent"), PRIORITY.EVIDENCE.CollectionError("PRIVATE_METADATA"),
                          PRIORITY.FINDER.ArtifactApiError("PRIVATE_METADATA"), ValueError("PRIVATE_METADATA")):
                out.write_text("stale manifest")
                stderr = io.StringIO()
                with patch.object(PRIORITY, "prepare", side_effect=error), contextlib.redirect_stderr(stderr):
                    self.assertEqual(PRIORITY.main(["--repo", REPO, "--component", COMPONENT, "--out", str(out)]), 0)
                self.assertFalse(out.exists())
                self.assertIn("no priority evidence", stderr.getvalue())
                self.assertNotIn("PRIVATE_METADATA", stderr.getvalue())

    def test_cli_writes_only_manifest_and_preserves_source_timestamp(self):
        value = self.prepare()
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "nested" / "priority.json"
            with patch.object(PRIORITY, "prepare", return_value=value), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(PRIORITY.main(["--repo", REPO, "--component", COMPONENT, "--out", str(out)]), 0)
            self.assertEqual(json.loads(out.read_text()), value)
            self.assertEqual(out.read_bytes(), PRIORITY.encode_manifest(value))
            self.assertEqual(list(out.parent.iterdir()), [out])

    def test_no_token_or_nonpilot_component_performs_no_request(self):
        with patch.object(PRIORITY.FINDER, "github_page", side_effect=AssertionError("no API expected")):
            for component, token in ((COMPONENT, ""), ("openbank-other-service", "test-token")):
                with self.assertRaises(PRIORITY.NoEvidence):
                    PRIORITY.prepare(REPO, component, token, now=NOW)


if __name__ == "__main__":
    unittest.main()
