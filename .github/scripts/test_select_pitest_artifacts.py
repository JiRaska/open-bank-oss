"""Contract tests for deterministic PIT evidence staging."""

import importlib.util
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location(
    "select_pitest_artifacts", Path(__file__).with_name("select-pitest-artifacts.py")
)
selector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(selector)


def artifact(artifact_id, name, created_at="2026-09-30T00:00:00Z"):
    return {"id": artifact_id, "name": name, "created_at": created_at, "expired": False}


class SelectPitestArtifactsTest(unittest.TestCase):
    def test_full_libs_report_wins_over_authz_subset_regardless_of_order(self):
        reports = [artifact(1, "pitest-openbank-libs-runtime"), artifact(2, "pitest-authz")]
        expected = [(1, "pitest-openbank-libs-runtime", "openbank-libs-runtime")]
        self.assertEqual(selector.select_artifacts({"artifacts": reports}), expected)
        self.assertEqual(selector.select_artifacts({"artifacts": list(reversed(reports))}), expected)

    def test_authz_report_is_fallback_when_full_report_is_absent(self):
        self.assertEqual(
            selector.select_artifacts({"artifacts": [artifact(2, "pitest-authz")]}),
            [(2, "pitest-authz", "openbank-libs-runtime")],
        )

    def test_latest_unexpired_attempt_wins_for_same_name(self):
        old = artifact(2, "pitest-openbank-ledger", "2026-09-01T00:00:00Z")
        new = artifact(3, "pitest-openbank-ledger", "2026-09-30T00:00:00Z")
        expired = artifact(4, "pitest-openbank-ledger", "2026-10-01T00:00:00Z")
        expired["expired"] = True
        self.assertEqual(
            selector.select_artifacts({"artifacts": [new, expired, old]}),
            [(3, "pitest-openbank-ledger", "openbank-ledger")],
        )

    def test_unsafe_artifact_name_fails_closed(self):
        with self.assertRaises(ValueError):
            selector.select_artifacts({"artifacts": [artifact(1, "pitest-../escape")]})


if __name__ == "__main__":
    unittest.main()
