from __future__ import annotations

import importlib.util
import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).with_name("collect-admin-ui-security-kpis.py")
SPEC = importlib.util.spec_from_file_location("admin_ui_security_kpis", SCRIPT)
assert SPEC and SPEC.loader
COLLECTOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COLLECTOR)

SHA = "a" * 40
RUN = {
    "id": 123, "run_attempt": 2, "head_sha": SHA, "head_branch": "main", "event": "schedule",
    "path": COLLECTOR.WORKFLOW_PATH, "head_repository": "org/repo", "conclusion": "success",
}
REPORT = {"generatedAt": "2026-10-19T09:23:00Z", "credentials": {"available": False, "reason": "test"}}


def archive(files: dict[str, bytes]) -> bytes:
    result = io.BytesIO()
    with zipfile.ZipFile(result, "w") as zipped:
        for name, data in files.items():
            zipped.writestr(name, data)
    return result.getvalue()


class SecurityKpiArtifactTests(unittest.TestCase):
    def test_accepts_only_successful_same_repo_main_security_workflow(self):
        self.assertTrue(COLLECTOR.trusted_run(RUN, "org/repo"))
        for bad in (
            RUN | {"head_branch": "feature"}, RUN | {"head_repository": "fork/repo"},
            RUN | {"path": ".github/workflows/other.yml"}, RUN | {"conclusion": "failure"},
            RUN | {"head_sha": "abc1234"},
        ):
            with self.subTest(run=bad):
                self.assertFalse(COLLECTOR.trusted_run(bad, "org/repo"))

    def test_extracts_single_root_file_and_preserves_original_generated_at_bytes(self):
        raw = json.dumps(REPORT, separators=(",", ":")).encode()
        self.assertEqual(COLLECTOR.extract_snapshot(archive({"security-kpis.json": raw})), raw)
        for members in (
            {"nested/security-kpis.json": raw},
            {"security-kpis.json": raw, "copy/security-kpis.json": raw},
            {"../security-kpis.json": raw},
            {"security-kpis.json": b"not json"},
            {"security-kpis.json": b"{}"},
        ):
            with self.subTest(members=members.keys()):
                with self.assertRaises(COLLECTOR.CollectionError):
                    COLLECTOR.extract_snapshot(archive(members))

    def test_selects_exact_run_attempt_artifact_and_preserves_generated_timestamp(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "security-kpis.json"
            metadata_out = Path(directory) / "provenance.json"
            artifact = {"id": 9, "name": "security-kpis-123-2", "expired": False,
                        "workflow_run": {"id": 123}}
            responses = {
                "/workflows/security-kpis.yml/runs?branch=main&status=success&per_page=100": {"workflow_runs": [RUN]},
                "/runs/123": RUN,
                "/runs/123/artifacts?per_page=100": {"artifacts": [artifact]},
            }
            def fetch_json(url):
                return responses[next(path for path in responses if url.endswith(path))]

            metadata = COLLECTOR.collect("org/repo", "token", out, metadata_out,
                                         fetch_json=fetch_json,
                                         fetch_bytes=lambda _url: archive({"security-kpis.json": json.dumps(REPORT).encode()}))
            self.assertEqual(json.loads(out.read_text())["generatedAt"], REPORT["generatedAt"])
            self.assertEqual(json.loads(metadata_out.read_text())["sourceSha"], SHA)
            self.assertEqual(metadata["runId"], 123)
            self.assertEqual(metadata["runAttempt"], 2)

    def test_missing_or_mismatched_artifact_fails_closed_without_output(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "security-kpis.json"
            metadata_out = Path(directory) / "provenance.json"
            responses = {
                "/workflows/security-kpis.yml/runs?branch=main&status=success&per_page=100": {"workflow_runs": [RUN]},
                "/runs/123": RUN,
                "/runs/123/artifacts?per_page=100": {"artifacts": []},
            }
            def fetch_json(url):
                return responses[next(path for path in responses if url.endswith(path))]
            with self.assertRaisesRegex(COLLECTOR.CollectionError, "exactly one retained"):
                COLLECTOR.collect("org/repo", "token", out, metadata_out, fetch_json=fetch_json, fetch_bytes=lambda _: b"")
            self.assertFalse(out.exists())
            self.assertFalse(metadata_out.exists())


if __name__ == "__main__":
    unittest.main()
