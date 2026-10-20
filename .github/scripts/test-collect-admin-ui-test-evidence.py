#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).with_name("collect-admin-ui-test-evidence.py")
SPEC = importlib.util.spec_from_file_location("admin_ui_test_evidence", SCRIPT)
assert SPEC and SPEC.loader
COLLECTOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COLLECTOR)

COMPONENT = "openbank-example-service"
SHA = "a" * 40
RUN = {
    "id": 123, "run_attempt": 2, "head_sha": SHA, "head_branch": "main",
    "path": ".github/workflows/services-ci.yml", "event": "push", "head_repository": "org/repo",
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
        payloads = COLLECTOR.artifact_payload(archive({
            "run.json": json.dumps(ENVELOPE).encode(),
            "test-results/test/TEST-example.xml": b"<testsuite tests='1'/>\n",
            "reports/kover/report.xml": b"<report/>\n",
            "private/unrelated.txt": b"ignored",
        }), COMPONENT, RUN)
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
                    COLLECTOR.artifact_payload(content, COMPONENT, run)

    def test_rejects_abbreviated_commit_even_when_it_prefix_matches_trusted_run(self):
        abbreviated = ENVELOPE | {"run": ENVELOPE["run"] | {"commit": SHA[:7]}}
        with self.assertRaisesRegex(COLLECTOR.CollectionError, "run identity"):
            COLLECTOR.artifact_payload(archive({"run.json": json.dumps(abbreviated).encode()}), COMPONENT, RUN)

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
            COLLECTOR.artifact_payload(output.getvalue(), COMPONENT, RUN)


class CollectionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.repo = Path(self.temp.name)
        (self.repo / COMPONENT).mkdir()
        (self.repo / COMPONENT / "version.txt").write_text("1.0.0\n")
        (self.repo / "openbank-admin-ui/scripts").mkdir(parents=True)
        (self.repo / "openbank-admin-ui/scripts/collect-test-intelligence.mjs").write_text("// stub\n")

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
            result = COLLECTOR.collect(
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
            result = COLLECTOR.collect(self.repo, output, "org/repo", "token",
                                       fetch_page=lambda _name, _page: [], seed=None, run_collector=run)
            self.assertEqual(result["missing"], [COMPONENT])
            report = json.loads(output.read_text())
            self.assertTrue(any("unknown, not passed" in warning for warning in report["warnings"]))
        with self.assertRaises(COLLECTOR.CollectionError):
            COLLECTOR.collect(self.repo, Path(self.temp.name) / "bad.json", "org/repo", "token",
                              fetch_page=lambda *_: (_ for _ in ()).throw(COLLECTOR.CollectionError("API down")),
                              seed=None, run_collector=run)

    def test_rejects_mixing_with_existing_build_evidence(self):
        target = self.repo / COMPONENT / "build/test-intelligence/run.json"
        target.parent.mkdir(parents=True)
        target.write_text("old")
        artifact = {"id": 7, "name": f"test-intelligence-{COMPONENT}", "expired": False,
                    "workflow_run": {"id": 123, "head_branch": "main"}}
        with self.assertRaisesRegex(COLLECTOR.CollectionError, "pre-existing path"):
            COLLECTOR.collect(self.repo, self.repo / "out.json", "org/repo", "token",
                              fetch_page=lambda *_: [artifact], fetch_run=lambda _: RUN,
                              fetch_archive=lambda _: archive({"run.json": json.dumps(ENVELOPE).encode()}),
                              seed=None, run_collector=self.fake_collector(None))


if __name__ == "__main__":
    unittest.main()
