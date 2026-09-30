#!/usr/bin/env python3
"""Regression tests for PIT artifact lane isolation and safe publication."""

import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "stage_pitest_artifacts", Path(__file__).with_name("stage-pitest-artifacts.py")
)
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class PitestStagingTest(unittest.TestCase):
    def test_run_selection_uses_latest_attempt_not_response_order_or_conclusion(self):
        old = {"id": 11, "status": "completed", "conclusion": "success",
               "created_at": "2026-09-20T00:00:00Z", "run_started_at": "2026-09-20T00:00:00Z"}
        new = {"id": 12, "status": "completed", "conclusion": "failure",
               "created_at": "2026-09-29T00:00:00Z", "run_started_at": "2026-09-29T00:00:00Z"}
        self.assertEqual(module.select_run(
            {"total_count": 2, "workflow_runs": [old, new]},
            {"total_count": 2, "workflow_runs": [new, old]},
        ), 12)

    def test_missing_newer_run_in_one_api_view_fails_closed(self):
        old = {"id": 11, "status": "completed", "created_at": "2026-09-20T00:00:00Z"}
        new = {"id": 12, "status": "completed", "created_at": "2026-09-29T00:00:00Z"}
        with self.assertRaisesRegex(ValueError, "inventories disagree"):
            module.select_run(
                {"total_count": 1, "workflow_runs": [old]},
                {"total_count": 2, "workflow_runs": [old, new]},
            )

    def test_partial_run_page_fails_closed(self):
        old = {"id": 11, "status": "completed", "created_at": "2026-09-20T00:00:00Z"}
        with self.assertRaisesRegex(ValueError, "inventory is incomplete"):
            module.select_run(
                {"total_count": 2, "workflow_runs": [old]},
                {"total_count": 1, "workflow_runs": [old]},
            )

    def test_two_reports_from_one_gradle_module_remain_separate(self):
        inventory = {"artifacts": [
            {"id": 11, "name": "pitest-authz", "expired": False},
            {"id": 12, "name": "pitest-openbank-libs-runtime", "expired": False},
        ]}
        planned = module.plan(inventory)
        self.assertEqual([owner for _, _, owner in planned], [
            "openbank-libs-runtime-authz", "openbank-libs-runtime"
        ])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for artifact_id, _, owner in planned:
                archive = root / f"{artifact_id}.zip"
                with zipfile.ZipFile(archive, "w") as output:
                    output.writestr("mutations.xml", str(artifact_id))
                module.stage(archive, owner, root)
            self.assertEqual((root / "openbank-libs-runtime-authz/build/reports/pitest/mutations.xml").read_text(), "11")
            self.assertEqual((root / "openbank-libs-runtime/build/reports/pitest/mutations.xml").read_text(), "12")

    def test_duplicate_projected_lane_fails_before_staging(self):
        with self.assertRaisesRegex(ValueError, "duplicate PIT artifact"):
            module.plan({"artifacts": [
                {"id": 11, "name": "pitest-authz", "expired": False},
                {"id": 12, "name": "pitest-openbank-libs-runtime-authz", "expired": False},
            ]})

    def test_existing_report_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "report.zip"
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("mutations.xml", "new")
            module.stage(archive, "openbank-libs-runtime", root)
            with self.assertRaisesRegex(ValueError, "already exists"):
                module.stage(archive, "openbank-libs-runtime", root)
            self.assertEqual((root / "openbank-libs-runtime/build/reports/pitest/mutations.xml").read_text(), "new")

    def test_unsafe_archive_does_not_publish_partial_report(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "report.zip"
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("mutations.xml", "valid")
                output.writestr("../escape", "invalid")
            with self.assertRaisesRegex(ValueError, "unsafe PIT archive path"):
                module.stage(archive, "openbank-libs-runtime", root)
            self.assertFalse((root / "openbank-libs-runtime/build/reports/pitest").exists())

    def test_run_envelope_must_match_lane_and_selected_run(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "report.zip"
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("mutations.xml", "report")
                output.writestr("test-intelligence-run.json", json.dumps({
                    "component": "openbank-libs-runtime-authz", "run": {"id": "42"}
                }))
            with self.assertRaisesRegex(ValueError, "another lane"):
                module.stage(archive, "openbank-libs-runtime", root, "42")
            with self.assertRaisesRegex(ValueError, "another run"):
                module.stage(archive, "openbank-libs-runtime-authz", root, "43")
            self.assertFalse((root / "openbank-libs-runtime-authz/build/reports/pitest").exists())


if __name__ == "__main__":
    unittest.main()
