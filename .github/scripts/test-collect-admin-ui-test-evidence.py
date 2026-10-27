#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import io
import json
import tempfile
import unittest
import zipfile
from unittest.mock import patch
from pathlib import Path

SCRIPT = Path(__file__).with_name("collect-admin-ui-test-evidence.py")
SPEC = importlib.util.spec_from_file_location("admin_ui_test_evidence", SCRIPT)
assert SPEC and SPEC.loader
COLLECTOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COLLECTOR)

COMPONENT = "openbank-example-service"
SHA = "a" * 40
RUN = {
    "id": 123, "run_attempt": 2, "head_sha": SHA, "head_branch": "main", "status": "completed",
    "path": ".github/workflows/services-ci.yml", "event": "push",
    "head_repository": {"full_name": "org/repo"},
}
ENVELOPE = {
    "schemaVersion": 1,
    "component": COMPONENT,
    "suites": [],
    "coverage": None,
    "testInfrastructure": {"declared": [], "observed": []},
    "run": {
        "id": "123", "attempt": 2, "commit": SHA, "branch": "main", "workflow": "Services CI",
        "url": "https://github.com/org/repo/actions/runs/123", "observedAt": "2026-10-19T12:00:00Z",
    },
}


def archive(files: dict[str, bytes]) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as zipped:
        for name, data in files.items():
            zipped.writestr(name, data)
    return output.getvalue()


class ArchiveTests(unittest.TestCase):
    def payload(self, content, run=RUN, **kwargs):
        return COLLECTOR.artifact_payload(
            content, COMPONENT, run, repo_slug="org/repo",
            expected_path=".github/workflows/services-ci.yml", **kwargs,
        )

    def test_merged_case_details_keep_existing_window_and_prioritize_failures(self):
        rows = [{"fingerprint": str(index), "state": "stable", "name": str(index),
                 "wastedDurationMs": 0, "lastObservedAt": "2026-10-18T00:00:00Z"} for index in range(2000)]
        failure = {"fingerprint": "failed", "state": "failing", "name": "failure", "wastedDurationMs": 10}
        core = {"testCases": [failure], "totals": {"passed": 12345}}
        seed = {"schemaVersion": 1, "collectedAt": "2026-10-18T00:00:00Z", "testCases": rows}
        result = COLLECTOR.merge_ancillary(core, seed)
        self.assertEqual(len(result["testCases"]), 2000)
        self.assertEqual(result["testCases"][0], failure)
        self.assertEqual(result["totals"], {"passed": 12345})
        self.assertTrue(any("1 historical detail rows omitted" in warning for warning in result["warnings"]))

    def test_stages_only_run_junit_and_kover_with_verified_identity(self):
        payloads = self.payload(archive({
            "run.json": json.dumps(ENVELOPE).encode(),
            "test-results/test/TEST-example.xml": b"<testsuite tests='1'/>\n",
            "reports/kover/report.xml": b"<report/>\n",
            "private/unrelated.txt": b"ignored",
        }))
        self.assertEqual(set(payloads), {
            f"{COMPONENT}/build/test-intelligence/run.json",
            f"{COMPONENT}/build/test-results/test/TEST-example.xml",
            f"{COMPONENT}/build/reports/kover/report.xml",
        })

    def test_rejects_corrupt_zip_traversal_duplicate_envelope_and_wrong_identity(self):
        cases = [
            (b"not a zip", RUN),
            (archive({"../run.json": json.dumps(ENVELOPE).encode()}), RUN),
            (archive({"run.json": json.dumps(ENVELOPE).encode(), "nested/run.json": json.dumps(ENVELOPE).encode()}), RUN),
            (archive({"run.json": json.dumps(ENVELOPE | {"component": "openbank-other"}).encode()}), RUN),
            (archive({"run.json": json.dumps(ENVELOPE).encode()}), RUN | {"head_sha": "b" * 40}),
        ]
        for content, run in cases:
            with self.subTest(content=content[:20]):
                with self.assertRaises(COLLECTOR.CollectionError):
                    self.payload(content, run)

    def test_rejects_abbreviated_commit_even_when_it_prefix_matches_trusted_run(self):
        abbreviated = ENVELOPE | {"run": ENVELOPE["run"] | {"commit": SHA[:7]}}
        with self.assertRaisesRegex(COLLECTOR.CollectionError, "run identity"):
            self.payload(archive({"run.json": json.dumps(abbreviated).encode()}))

    def test_accepts_previous_attempt_only_when_exact_attempt_metadata_matches(self):
        previous = RUN | {"run_attempt": 1,
                          "run_started_at": "2026-10-19T11:00:00Z",
                          "updated_at": "2026-10-19T12:30:00Z"}
        old_envelope = ENVELOPE | {"run": ENVELOPE["run"] | {"attempt": 1}}
        content = archive({"run.json": json.dumps(old_envelope).encode()})
        payloads = self.payload(content, resolve_attempt=lambda run_id, attempt: previous,
                                artifact_created_at="2026-10-19T12:10:00Z")
        self.assertIn(f"{COMPONENT}/build/test-intelligence/run.json", payloads)

        forged = [
            previous | {"id": 124},
            previous | {"run_attempt": 2},
            previous | {"head_sha": "b" * 40},
            previous | {"head_repository": "attacker/repo"},
            previous | {"path": ".github/workflows/other.yml"},
            previous | {"event": "pull_request"},
        ]
        for metadata in forged:
            with self.subTest(metadata=metadata):
                with self.assertRaisesRegex(COLLECTOR.CollectionError, "run identity"):
                    self.payload(content, resolve_attempt=lambda _run_id, _attempt, metadata=metadata: metadata,
                                 artifact_created_at="2026-10-19T12:10:00Z")

    def test_rejects_future_attempt_and_unverified_previous_attempt(self):
        future = ENVELOPE | {"run": ENVELOPE["run"] | {"attempt": 3}}
        with self.assertRaisesRegex(COLLECTOR.CollectionError, "future"):
            self.payload(archive({"run.json": json.dumps(future).encode()}))
        previous = ENVELOPE | {"run": ENVELOPE["run"] | {"attempt": 1}}
        with self.assertRaisesRegex(COLLECTOR.CollectionError, "metadata.*unavailable"):
            self.payload(archive({"run.json": json.dumps(previous).encode()}))

    def test_artifact_timestamp_must_fit_claimed_attempt(self):
        previous = RUN | {"run_attempt": 1,
                          "run_started_at": "2026-10-19T11:00:00Z",
                          "updated_at": "2026-10-19T12:30:00Z"}
        old_envelope = ENVELOPE | {"run": ENVELOPE["run"] | {"attempt": 1}}
        content = archive({"run.json": json.dumps(old_envelope).encode()})
        with self.assertRaisesRegex(COLLECTOR.CollectionError, "provenance"):
            self.payload(content, resolve_attempt=lambda _run_id, _attempt: previous,
                         artifact_created_at="2026-10-19T13:00:00Z")

    def test_prior_attempt_requires_all_valid_aware_timestamps(self):
        previous = RUN | {"run_attempt": 1,
                          "run_started_at": "2026-10-19T11:00:00Z",
                          "updated_at": "2026-10-19T12:30:00Z"}
        old_envelope = ENVELOPE | {"run": ENVELOPE["run"] | {"attempt": 1}}
        valid_created_at = "2026-10-19T12:10:00Z"
        fields = ("run_started_at", "updated_at", "observedAt", "artifact_created_at")
        for field in fields:
            for invalid_value in (None, "not-a-timestamp", "2026-10-19T12:10:00"):
                with self.subTest(field=field, value=invalid_value):
                    attempt_metadata = dict(previous)
                    evidence = old_envelope
                    created_at = valid_created_at
                    if field in {"run_started_at", "updated_at"}:
                        if invalid_value is None:
                            attempt_metadata.pop(field)
                        else:
                            attempt_metadata[field] = invalid_value
                    elif field == "observedAt":
                        run_data = dict(old_envelope["run"])
                        if invalid_value is None:
                            run_data.pop(field)
                        else:
                            run_data[field] = invalid_value
                        evidence = old_envelope | {"run": run_data}
                    else:
                        created_at = invalid_value
                    body = archive({"run.json": json.dumps(evidence).encode()})
                    with self.assertRaises(COLLECTOR.CollectionError):
                        self.payload(body, resolve_attempt=lambda _run_id, _attempt, attempt_metadata=attempt_metadata: attempt_metadata,
                                     artifact_created_at=created_at)

    def test_current_attempt_timestamps_remain_optional(self):
        content = archive({"run.json": json.dumps(ENVELOPE).encode()})
        self.assertIn(f"{COMPONENT}/build/test-intelligence/run.json",
                      self.payload(content))

    def test_github_api_requests_reject_non_github_hosts_before_sending_credentials(self):
        for request in (COLLECTOR.api_json, COLLECTOR.api_bytes):
            for url in (
                "https://api.github.com.attacker.example/repos/org/repo",
                "https://user@api.github.com/repos/org/repo",
                "http://api.github.com/repos/org/repo",
            ):
                with self.subTest(url=url, method=request.__name__):
                    with self.assertRaisesRegex(COLLECTOR.CollectionError, "outside api.github.com"):
                        request(url, "sensitive-test-token")

    def test_rejects_symlink_members(self):
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as zipped:
            info = zipfile.ZipInfo("run.json")
            info.create_system = 3
            info.external_attr = (0o120777 << 16)
            zipped.writestr(info, json.dumps(ENVELOPE))
        with self.assertRaisesRegex(COLLECTOR.CollectionError, "symlink"):
            self.payload(output.getvalue())


class PerformanceMergeTests(unittest.TestCase):
    def row(self, scenario, component, observed_at, state="passed", attempt=1):
        return {"id": scenario, "component": component, "observedAt": observed_at, "state": state,
                "run": ENVELOPE["run"] | {"attempt": attempt, "observedAt": observed_at},
                "detail": f"{scenario}: {state}", "metrics": {"requests": attempt * 100}}

    def merge(self, current, retained):
        core = {"performance": current, "warnings": []}
        seed = {"schemaVersion": 1, "collectedAt": "2026-10-19T12:00:00Z", "performance": retained}
        return COLLECTOR.merge_ancillary(core, seed)["performance"]

    def test_platform_scenarios_with_null_component_keep_separate_identities(self):
        retained = [self.row("platform-a", None, "2026-10-18T12:00:00Z"),
                    self.row("platform-b", None, "2026-10-18T12:00:00Z")]
        current = [self.row("platform-a", None, "2026-10-19T12:00:00Z", "failed", 2),
                   self.row("platform-c", None, "2026-10-19T12:00:00Z")]
        self.assertCountEqual(self.merge(current, retained), [current[0], retained[1], current[1]])

    def test_multiple_scenarios_of_one_service_keep_separate_identities(self):
        retained = [self.row("service-read", COMPONENT, "2026-10-18T12:00:00Z"),
                    self.row("service-write", COMPONENT, "2026-10-18T12:00:00Z")]
        current = [self.row("service-read", COMPONENT, "2026-10-19T12:00:00Z", "failed", 2),
                   self.row("service-batch", COMPONENT, "2026-10-19T12:00:00Z")]
        self.assertCountEqual(self.merge(current, retained), [current[0], retained[1], current[1]])

    def test_newest_failed_attempt_and_its_provenance_win_out_of_order(self):
        retained = [self.row("service-read", COMPONENT, "2026-10-18T12:00:00Z")]
        failed = self.row("service-read", COMPONENT, "2026-10-19T12:00:00Z", "failed", 3)
        older_pass = self.row("service-read", COMPONENT, "2026-10-19T11:00:00Z", attempt=2)
        for current in ([failed, older_pass], [older_pass, failed]):
            with self.subTest(current=current):
                self.assertEqual(self.merge(current, retained), [failed])
        self.assertEqual(self.merge([older_pass], [failed]), [failed])

    def test_newer_not_run_is_authoritative_but_undated_declaration_is_not(self):
        retained = self.row("service-read", COMPONENT, "2026-10-18T12:00:00Z")
        not_run = self.row("service-read", COMPONENT, "2026-10-19T12:00:00Z", "not-run", 2)
        self.assertEqual(self.merge([not_run], [retained]), [not_run])
        declaration = {"id": "service-read", "component": COMPONENT, "state": "not-run", "observedAt": None}
        self.assertEqual(self.merge([declaration], [retained]), [retained])


class AggregateSeedTests(unittest.TestCase):
    def setUp(self):
        self.report = {"schemaVersion": 1, "collectedAt": "2026-10-19T12:00:00Z", "components": [], "totals": {},
                       **{field: [{"retained": field}] for field in COLLECTOR.SEEDED_LIST_FIELDS},
                       "clientExperiences": [{"id": "mobile"}], "syntheticJourneys": [{"id": "journey"}],
                       "contracts": [{"provider": "example"}]}
        self.run = RUN | {"path": ".github/workflows/admin-ui-deploy.yml", "event": "workflow_run",
                          "conclusion": "success", "run_started_at": "2026-10-19T11:00:00Z",
                          "updated_at": "2026-10-19T13:00:00Z"}
        self.artifact = {"id": 456, "name": COLLECTOR.SEED_ALIAS, "expired": False,
                         "created_at": "2026-10-19T12:10:00Z",
                         "workflow_run": {"id": 123, "head_sha": SHA, "head_branch": "main"}}
        self.provenance = {"schemaVersion": 1, "runId": 123, "attempt": 2, "sourceSha": "b" * 40,
                           "workflowSha": SHA}
        self.calls = []

    def body(self, **extra):
        return archive({"test-intelligence.json": json.dumps(self.report).encode(),
                        "seed-provenance.json": json.dumps(self.provenance).encode(), **extra})

    def response(self, url, token):
        self.calls.append(url)
        if "/actions/artifacts?" in url:
            return {"total_count": 1, "artifacts": [self.artifact]}
        if url.endswith("/actions/runs/123"):
            return self.run
        raise AssertionError(f"unexpected legacy or additional lookup: {url}")

    def read(self, body=None, response=None):
        with patch.object(COLLECTOR, "api_json", side_effect=response or self.response), \
             patch.object(COLLECTOR, "api_bytes", return_value=self.body() if body is None else body) as download:
            result = COLLECTOR.latest_aggregate("org/repo", "fixture-token")
        return result, download.call_count

    def test_constant_request_path_preserves_every_ancillary_field(self):
        result, downloads = self.read()
        self.assertEqual(result, self.report)
        self.assertEqual(len(self.calls), 2)  # exact-name inventory plus one trusted run
        self.assertEqual(downloads, 1)
        self.assertIn("name=admin-ui-test-intelligence-seed", self.calls[0])

    def test_wrong_workflow_fork_unsuccessful_and_attempt_mismatch_fail_closed(self):
        original = dict(self.run)
        for changed in [{"path": ".github/workflows/other.yml"}, {"head_repository": {"full_name": "fork/repo"}},
                        {"conclusion": "failure"}, {"status": "in_progress"}, {"run_attempt": 3},
                        {"head_sha": "c" * 40}, {"event": "pull_request"}]:
            self.run = original | changed
            with self.subTest(changed=changed), self.assertRaises(COLLECTOR.CollectionError):
                self.read()
        self.run = original

    def test_ambiguous_incomplete_and_malformed_inventory_never_falls_back(self):
        for listing in [{"total_count": 2, "artifacts": [self.artifact]},
                        {"total_count": 2, "artifacts": [self.artifact, self.artifact | {"id": 457}]},
                        {"total_count": 0, "artifacts": None},
                        {"total_count": 1, "artifacts": [self.artifact | {"name": "wrong"}]}]:
            with self.subTest(listing=listing), self.assertRaises(COLLECTOR.CollectionError):
                self.read(response=lambda _url, _token, listing=listing: listing)

    def test_timestamp_and_sidecar_errors(self):
        original = dict(self.provenance)
        for changed in [{"sourceSha": "short"}, {"workflowSha": "c" * 40}, {"runId": 999}, {"attempt": 1}]:
            self.provenance = original | changed
            with self.subTest(changed=changed), self.assertRaises(COLLECTOR.CollectionError):
                self.read()
        self.provenance = original
        for collected in [None, "2026-10-19T10:00:00Z", "2026-10-19T12:11:00Z", "2026-10-19T12:00:00"]:
            self.report["collectedAt"] = collected
            with self.subTest(collected=collected), self.assertRaises(COLLECTOR.CollectionError):
                self.read()

    def test_archive_errors_and_missing_sidecar(self):
        for body in [b"corrupt", archive({"test-intelligence.json": json.dumps(self.report).encode()}),
                     self.body(**{"../escape": b"bad"}), self.body(**{"unexpected": b"bad"})]:
            with self.subTest(body=body[:20]), self.assertRaises(COLLECTOR.CollectionError):
                self.read(body=body)

    def test_alias_absence_preserves_legacy_lookup(self):
        def response(url, token):
            self.calls.append(url)
            if "/actions/artifacts?" in url:
                return {"total_count": 0, "artifacts": []}
            if "/workflows/admin-ui-deploy.yml/runs?" in url:
                return {"workflow_runs": [self.run]}
            if url.endswith("/actions/runs/123"):
                return self.run
            if "/runs/123/artifacts?" in url:
                return {"artifacts": [self.artifact | {"name": "test-intelligence-snapshot-123-2"}]}
            raise AssertionError(url)
        result, count = self.read(body=archive({"test-intelligence.json": json.dumps(self.report).encode()}), response=response)
        self.assertEqual(result, self.report)
        self.assertEqual(count, 1)
        self.assertEqual(len(self.calls), 4)

    def test_all_expired_aliases_fall_back_to_validated_retained_snapshot(self):
        self.artifact["expired"] = True
        older = self.artifact | {"id": 455, "workflow_run": {"id": 122, "head_sha": SHA, "head_branch": "main"}}

        def response(url, token):
            self.calls.append(url)
            if "/actions/artifacts?" in url:
                return {"total_count": 2, "artifacts": [self.artifact, older]}
            if "/workflows/admin-ui-deploy.yml/runs?" in url:
                return {"workflow_runs": [self.run]}
            if url.endswith("/actions/runs/123"):
                return self.run
            if "/runs/123/artifacts?" in url:
                return {"artifacts": [self.artifact | {"id": 500, "name": "test-intelligence-snapshot-123-2", "expired": False}]}
            raise AssertionError(url)

        body = archive({"test-intelligence.json": json.dumps(self.report).encode()})
        result, downloads = self.read(body=body, response=response)
        self.assertEqual(result, self.report)
        self.assertEqual(result["collectedAt"], "2026-10-19T12:00:00Z")
        self.assertEqual(downloads, 1)
        self.assertEqual(len(self.calls), 4)
        self.assertFalse(any(url.endswith("/actions/runs/122") for url in self.calls))
        # Fallback still verifies the legacy producer; expiration is not trust.
        self.run["head_repository"] = {"full_name": "fork/repo"}
        result, downloads = self.read(body=body, response=response)
        self.assertIsNone(result)
        self.assertEqual(downloads, 0)

    def test_missing_or_nonboolean_expiration_never_authorizes_fallback(self):
        for value in (None, 0, 1, "true", "false", [], {}):
            artifact = self.artifact | {"expired": value}
            with self.subTest(value=value), self.assertRaises(COLLECTOR.CollectionError):
                self.read(response=lambda _url, _token, artifact=artifact: {"total_count": 1, "artifacts": [artifact]})
        missing = {key: value for key, value in self.artifact.items() if key != "expired"}
        with self.assertRaises(COLLECTOR.CollectionError):
            self.read(response=lambda _url, _token: {"total_count": 1, "artifacts": [missing]})

    def test_expiration_does_not_hide_malformed_or_ambiguous_inventory(self):
        expired = self.artifact | {"expired": True}
        for listing in ({"total_count": 2, "artifacts": [expired]},
                        {"total_count": 2, "artifacts": [expired, expired | {"id": 457}]},
                        {"total_count": 1, "artifacts": [expired | {"workflow_run": {}}]},
                        {"total_count": 1, "artifacts": [expired | {"workflow_run": {"id": 123, "head_branch": "main", "head_sha": "short"}}]},
                        {"total_count": 1, "artifacts": [expired | {"workflow_run": {"id": 123, "head_branch": "feature", "head_sha": SHA}}]},
                        {"total_count": 1, "artifacts": [expired | {"name": "wrong"}]}):
            with self.subTest(listing=listing), self.assertRaises(COLLECTOR.CollectionError):
                self.read(response=lambda _url, _token, listing=listing: listing)

    def test_unexpired_invalid_identity_with_expired_sibling_never_falls_back(self):
        expired = self.artifact | {"id": 455, "expired": True,
                                   "workflow_run": {"id": 122, "head_sha": SHA, "head_branch": "main"}}
        self.artifact["workflow_run"]["head_sha"] = "b" * 40
        original = self.response

        def response(url, token):
            if "/actions/artifacts?" in url:
                return {"total_count": 2, "artifacts": [self.artifact, expired]}
            return original(url, token)

        with self.assertRaisesRegex(COLLECTOR.CollectionError, "identity mismatch"):
            self.read(response=response)

    def test_api_errors_never_mean_absence(self):
        with self.assertRaises(COLLECTOR.CollectionError):
            self.read(response=lambda _url, _token: (_ for _ in ()).throw(COLLECTOR.CollectionError("unavailable")))


class CollectionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.repo = Path(self.temp.name)
        (self.repo / COMPONENT).mkdir()
        (self.repo / COMPONENT / "version.txt").write_text("1.0.0\n")
        (self.repo / "openbank-admin-ui/scripts").mkdir(parents=True)
        (self.repo / "openbank-admin-ui/scripts/collect-test-intelligence.mjs").write_text("// stub\n")

    def collect(self, *args, **kwargs):
        # Existing service-only fixtures must never perform implicit network reads.
        kwargs.setdefault("performance_stage", lambda *_args, **_kwargs:
                          {"payloads": {}, "sources": [], "warnings": []})
        return COLLECTOR.collect(*args, **kwargs)

    def tearDown(self):
        self.temp.cleanup()

    def fake_collector(self, seed):
        def run(command, cwd, check):
            self.assertEqual(cwd, self.repo)
            output = Path(command[command.index("--out") + 1])
            output.write_text(json.dumps({
                "schemaVersion": 1, "collectedAt": "2026-10-19T13:00:00Z", "components": [],
                "contracts": [{"consumer": "openbank-a", "provider": "openbank-b", "pactFile": "a-b.json",
                               "consumerVersion": "c" * 40, "state": "pending", "observedAt": None}],
                "mutations": [], "performance": [], "performanceHistory": [],
                "syntheticJourneys": [], "clientExperiences": [], "history": [], "runHistory": [],
                "testCases": [], "totals": {}, "warnings": [],
            }))
        return run

    def test_collects_latest_service_artifact_and_carries_only_ancillary_seed_fields(self):
        artifact = {"id": 7, "name": f"test-intelligence-{COMPONENT}", "expired": False,
                    "workflow_run": {"id": 123, "head_branch": "main"}}
        seed = {"schemaVersion": 1, "collectedAt": "2026-10-18T12:00:00Z", "components": [{"component": "old"}],
                "contracts": [{"consumer": "openbank-a", "provider": "openbank-b", "pactFile": "a-b.json",
                               "consumerVersion": "c" * 40, "state": "failed", "observedAt": "2026-10-18T11:00:00Z"}],
                "mutations": [{"component": COMPONENT, "state": "passed", "observedAt": "2026-10-17T11:00:00Z"}]}
        with tempfile.TemporaryDirectory() as output_dir:
            output = Path(output_dir) / "test-intelligence.json"
            result = self.collect(
                self.repo, output, "org/repo", "test-token", fetch_page=lambda name, page: [artifact],
                fetch_run=lambda run_id: RUN, fetch_archive=lambda artifact_id: archive({
                    "run.json": json.dumps(ENVELOPE).encode(),
                    "test-results/test/TEST-example.xml": b"<testsuite tests='1'/>\n",
                }), seed=seed, run_collector=self.fake_collector(seed),
            )
            report = json.loads(output.read_text())
            self.assertEqual(output.read_text(), json.dumps(report, separators=(",", ":"), ensure_ascii=False) + "\n")
        self.assertEqual(result["staged"], [COMPONENT])
        self.assertEqual(report["components"], [])
        self.assertEqual(report["contracts"][0]["state"], "failed")
        self.assertEqual(report["mutations"], seed["mutations"])
        self.assertIn("collected at 2026-10-18T12:00:00Z", report["warnings"][-1])
        self.assertFalse((self.repo / COMPONENT / "build").exists())

    def test_absent_component_is_reported_unknown_and_api_errors_fail(self):
        def run(command, cwd, check):
            output = Path(command[command.index("--out") + 1])
            output.write_text(json.dumps({"schemaVersion": 1, "collectedAt": "2026-10-19T13:00:00Z",
                                          "components": [], "warnings": [], "totals": {}}))
        with tempfile.TemporaryDirectory() as output_dir:
            output = Path(output_dir) / "result.json"
            result = self.collect(self.repo, output, "org/repo", "token",
                                       fetch_page=lambda _name, _page: [], seed=None, run_collector=run)
            self.assertEqual(result["missing"], [COMPONENT])
            report = json.loads(output.read_text())
            self.assertTrue(any("unknown, not passed" in warning for warning in report["warnings"]))
        with self.assertRaises(COLLECTOR.CollectionError):
            self.collect(self.repo, Path(self.temp.name) / "bad.json", "org/repo", "token",
                              fetch_page=lambda *_: (_ for _ in ()).throw(COLLECTOR.CollectionError("API down")),
                              seed=None, run_collector=run)

    def test_missing_or_partial_run_status_cannot_stage_evidence(self):
        artifact = {"id": 7, "name": f"test-intelligence-{COMPONENT}", "expired": False,
                    "workflow_run": {"id": 123, "head_branch": "main"}}
        for status in (None, "queued", "in_progress"):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as output_dir:
                metadata = RUN | {"status": status}
                if status is None:
                    metadata.pop("status")
                result = self.collect(
                    self.repo, Path(output_dir) / "result.json", "org/repo", "token",
                    fetch_page=lambda *_: [artifact], fetch_run=lambda _, metadata=metadata: metadata,
                    fetch_archive=lambda _: self.fail("unfinished evidence must not be downloaded"),
                    seed=None, run_collector=self.fake_collector(None),
                )
                self.assertEqual(result["staged"], [])
                self.assertEqual(result["missing"], [COMPONENT])

    def test_rejects_mixing_with_existing_build_evidence(self):
        target = self.repo / COMPONENT / "build/test-intelligence/run.json"
        target.parent.mkdir(parents=True)
        target.write_text("old")
        artifact = {"id": 7, "name": f"test-intelligence-{COMPONENT}", "expired": False,
                    "workflow_run": {"id": 123, "head_branch": "main"}}
        with self.assertRaisesRegex(COLLECTOR.CollectionError, "pre-existing path"):
            self.collect(self.repo, self.repo / "out.json", "org/repo", "token",
                              fetch_page=lambda *_: [artifact], fetch_run=lambda _: RUN,
                              fetch_archive=lambda _: archive({"run.json": json.dumps(ENVELOPE).encode()}),
                              seed=None, run_collector=self.fake_collector(None))

    def test_caches_current_and_attempt_metadata_per_run_attempt(self):
        second = "openbank-second-service"
        (self.repo / second).mkdir()
        (self.repo / second / "version.txt").write_text("1.0.0\n")
        artifacts = {
            7: {"id": 7, "name": f"test-intelligence-{COMPONENT}", "expired": False,
                "created_at": "2026-10-19T12:10:00Z",
                "workflow_run": {"id": 123, "head_branch": "main"}},
            8: {"id": 8, "name": f"test-intelligence-{second}", "expired": False,
                "created_at": "2026-10-19T12:10:00Z",
                "workflow_run": {"id": 123, "head_branch": "main"}},
        }
        archives = {}
        for artifact_id, component in ((7, COMPONENT), (8, second)):
            envelope = ENVELOPE | {"component": component,
                                   "run": ENVELOPE["run"] | {"attempt": 1}}
            archives[artifact_id] = archive({"run.json": json.dumps(envelope).encode()})
        run_calls = []
        attempt_calls = []
        prior_attempt = RUN | {"run_attempt": 1,
                              "run_started_at": "2026-10-19T11:00:00Z",
                              "updated_at": "2026-10-19T12:30:00Z"}
        with tempfile.TemporaryDirectory() as output_dir:
            result = self.collect(
                self.repo, Path(output_dir) / "result.json", "org/repo", "token",
                fetch_page=lambda name, _page: [next(row for row in artifacts.values() if row["name"] == name)],
                fetch_run=lambda run_id: run_calls.append(run_id) or RUN,
                fetch_attempt=lambda run_id, attempt: attempt_calls.append((run_id, attempt)) or prior_attempt,
                fetch_archive=archives.__getitem__, seed=None,
                run_collector=self.fake_collector(None),
            )
        self.assertEqual(set(result["staged"]), {COMPONENT, second})
        self.assertEqual(run_calls, [123])
        self.assertEqual(attempt_calls, [(123, 1)])

    def performance_row(self, local="read", state="not-run"):
        return {"id": COMPONENT + "-" + local, "component": COMPONENT,
                "source": f"{COMPONENT}/src/test/k6/{local}.js", "state": state,
                "observedAt": ENVELOPE["run"]["observedAt"], "run": ENVELOPE["run"],
                "detail": "No safe authenticated target was supplied.", "thresholds": 1}

    def performance_source(self, rows, *, scope="full", selected=True):
        expected = [{"scenario_id": row["id"], "component": row["component"]} for row in rows]
        return {"workflow": "perf-baseline.yml", "scope": scope,
                "run_id": None if scope == "absent" else 123,
                "attempt": None if scope == "absent" else 2,
                **({} if scope == "absent" else {"commit": SHA, "observed_at": "2026-10-19T11:00:00Z"}),
                "expected": expected, "selected": expected if selected else [],
                "missing": [] if selected else [entry | {"reason": "no attributable latest-job artifact"} for entry in expected]}

    def collect_performance(self, rows, sources, *, seed=None, payloads=None, run=None):
        def collector(command, cwd, check):
            self.fake_collector(None)(command, cwd, check)
            output = Path(command[command.index("--out") + 1])
            report = json.loads(output.read_text())
            report["performance"] = rows
            output.write_text(json.dumps(report))
        output = self.repo / "performance-result.json"
        self.collect(self.repo, output, "org/repo", "token", fetch_page=lambda *_: [], seed=seed,
                     performance_stage=lambda *_args, **_kwargs:
                     {"payloads": payloads or {}, "sources": sources, "warnings": ["fixture selection"]},
                     run_collector=run or collector)
        return json.loads(output.read_text())

    def test_selected_not_run_retains_detail_and_beats_newer_seed(self):
        row = self.performance_row()
        old = row | {"state": "passed", "observedAt": "2026-10-20T00:00:00Z", "metrics": {"requests": 900}}
        historical = {"id": row["id"], "collectedAt": "2026-10-18T00:00:00Z", "metrics": {"requests": 100}}
        seed = {"schemaVersion": 1, "collectedAt": "2026-10-20T00:00:00Z",
                "performance": [old], "performanceHistory": [historical]}
        report = self.collect_performance([row], [self.performance_source([row])], seed=seed)
        self.assertEqual(report["performance"], [row])
        self.assertEqual(report["performanceHistory"], [historical])
        self.assertEqual(report["performance"][0]["detail"], "No safe authenticated target was supplied.")
        self.assertNotIn("metrics", report["performance"][0])

    def test_latest_missing_barrier_and_global_absence_block_old_pass_without_fake_measurements(self):
        for scope in ("full", "barrier", "absent"):
            with self.subTest(scope=scope):
                row = self.performance_row(state="passed") | {"metrics": {"requests": 999},
                                                             "thresholdResults": {"evaluated": 1, "breached": 0}}
                seed = {"schemaVersion": 1, "collectedAt": "2026-10-20T00:00:00Z", "performance": [row]}
                source = self.performance_source([row], scope=scope, selected=False)
                result = self.collect_performance([row], [source], seed=seed)["performance"][0]
                self.assertEqual(result["state"], "unknown")
                self.assertEqual(result["observedAt"], source.get("observed_at"))
                self.assertEqual(result["collectionStatus"]["runId"], source["run_id"])
                self.assertIn("no attributable", result["detail"])
                for key in ("metrics", "run", "thresholdResults"):
                    self.assertNotIn(key, result)

    def test_authority_preserves_multiple_scenarios_of_one_component_and_unrelated_scenarios(self):
        rows = [self.performance_row("read"), self.performance_row("write", "failed")]
        untouched = {"id": "platform-unrelated", "component": None, "state": "not-run", "observedAt": None}
        seed = {"schemaVersion": 1, "collectedAt": "2026-10-20T00:00:00Z",
                "performance": [row | {"state": "passed", "observedAt": "2026-10-20T00:00:00Z"} for row in rows]}
        report = self.collect_performance(rows + [untouched], [self.performance_source(rows)], seed=seed)
        self.assertCountEqual(report["performance"], rows + [untouched])

    def test_selected_global_money_path_matches_null_component_javascript_identity(self):
        row = self.performance_row() | {"id": "money-path-smoke", "component": None,
                                       "source": "perf/k6/money-path-smoke.js"}
        source = self.performance_source([row])
        source["expected"][0]["component"] = "openbank-money-path"
        self.assertEqual(self.collect_performance([row], [source])["performance"], [row])

    def test_selected_row_identity_and_provenance_fail_closed(self):
        for change in ({"source": f"{COMPONENT}/src/test/k6/wrong.js"},
                       {"run": ENVELOPE["run"] | {"id": "999"}}, {"run": None}):
            with self.subTest(change=change), self.assertRaises(COLLECTOR.CollectionError):
                row = self.performance_row()
                self.collect_performance([row | change], [self.performance_source([row])])

    def test_removed_missing_declaration_does_not_resurrect_seed_pass(self):
        row = self.performance_row(state="passed")
        seed = {"schemaVersion": 1, "collectedAt": "2026-10-20T00:00:00Z", "performance": [row]}
        report = self.collect_performance([], [self.performance_source([row], selected=False)], seed=seed)
        self.assertEqual(report["performance"], [])

    def test_performance_payloads_are_visible_only_during_collection_and_cleaned_after_failure(self):
        relative = "openbank-admin-ui/perf-artifacts/read-summary.json.run.json"
        target = self.repo / relative
        for fail in (False, True):
            def collector(command, cwd, check):
                self.assertEqual(target.read_bytes(), b"fixture")
                if fail:
                    raise RuntimeError("collector failed")
                self.fake_collector(None)(command, cwd, check)
            if fail:
                with self.assertRaisesRegex(RuntimeError, "collector failed"):
                    self.collect_performance([], [], payloads={relative: b"fixture"}, run=collector)
            else:
                self.collect_performance([], [], payloads={relative: b"fixture"}, run=collector)
            self.assertFalse(target.exists())
            self.assertFalse(target.parent.exists())
            self.assertTrue((self.repo / "openbank-admin-ui/scripts").is_dir())

    def test_performance_staging_rejects_traversal_symlinks_and_preexisting_files(self):
        for relative in ("../outside.json", "/absolute.json", "openbank-admin-ui/../outside.json", "x\\evil"):
            with self.subTest(relative=relative), self.assertRaises(COLLECTOR.CollectionError):
                self.collect_performance([], [], payloads={relative: b"invalid"})
        folder = self.repo / "openbank-admin-ui/perf-artifacts"
        folder.mkdir()
        existing = folder / "old.json"
        existing.write_bytes(b"keep")
        with self.assertRaises(COLLECTOR.CollectionError):
            self.collect_performance([], [], payloads={str(existing.relative_to(self.repo)): b"replace"})
        self.assertEqual(existing.read_bytes(), b"keep")
        existing.unlink()
        folder.rmdir()
        folder.symlink_to(self.repo / "openbank-admin-ui/scripts", target_is_directory=True)
        with self.assertRaisesRegex(COLLECTOR.CollectionError, "symlink"):
            self.collect_performance([], [], payloads={"openbank-admin-ui/perf-artifacts/new.json": b"invalid"})
        self.assertFalse((self.repo / "openbank-admin-ui/scripts/new.json").exists())

    def test_performance_callbacks_are_authenticated_memoized_and_stage_errors_translate(self):
        url = "https://api.github.com/repos/org/repo/actions/runs/123"
        archive_url = "https://api.github.com/repos/org/repo/actions/artifacts/8/zip"
        def stage(_repo, _slug, fetch_json, fetch_bytes, **callbacks):
            self.assertEqual(fetch_json(url), fetch_json(url))
            self.assertEqual(fetch_bytes(archive_url), fetch_bytes(archive_url))
            self.assertEqual(callbacks["fetch_log"](9), callbacks["fetch_log"](9))
            return {"payloads": {}, "sources": [], "warnings": []}
        with patch.object(COLLECTOR, "api_json", return_value={}) as json_api, \
                patch.object(COLLECTOR, "api_bytes", return_value=b"fixture") as bytes_api:
            self.collect(self.repo, self.repo / "out.json", "org/repo", "token", fetch_page=lambda *_: [],
                         seed=None, performance_stage=stage, run_collector=self.fake_collector(None))
        json_api.assert_called_once_with(url, "token")
        self.assertEqual(bytes_api.call_count, 2)
        self.assertEqual(bytes_api.call_args_list[0].args, (archive_url, "token"))
        self.assertEqual(bytes_api.call_args_list[1].args,
                         ("https://api.github.com/repos/org/repo/actions/jobs/9/logs", "token"))
        def invalid(*_args, **_kwargs):
            raise COLLECTOR.PERFORMANCE.StageError("bad producer")
        with self.assertRaisesRegex(COLLECTOR.CollectionError, "performance artifact collection failed"):
            self.collect(self.repo, self.repo / "bad.json", "org/repo", "token", fetch_page=lambda *_: [],
                         seed=None, performance_stage=invalid)


if __name__ == "__main__":
    unittest.main()
