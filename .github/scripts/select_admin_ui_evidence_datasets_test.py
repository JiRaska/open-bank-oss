from __future__ import annotations

import unittest
from pathlib import Path

from select_admin_ui_evidence_datasets import ALL_DATASETS, select_datasets

REPO_ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = REPO_ROOT / ".github/workflows/admin-ui-evidence.yml"


class DatasetSelectionTests(unittest.TestCase):
    def test_completed_workflows_select_only_their_datasets(self):
        self.assertEqual(select_datasets("workflow_run", "CI"), [
            "gate-health", "readiness", "test-intelligence", "content",
        ])
        self.assertEqual(select_datasets("workflow_run", "Services CI"), ["test-intelligence"])
        self.assertEqual(select_datasets("workflow_run", "Security KPI snapshot"), ["security-kpis"])
        with self.assertRaisesRegex(ValueError, "unsupported evidence producer"):
            select_datasets("workflow_run", "Pact drift check")

    def test_schedule_and_manual_selection_keep_existing_scope(self):
        self.assertEqual(select_datasets("schedule"), ALL_DATASETS)
        self.assertEqual(select_datasets("workflow_dispatch"), ALL_DATASETS)
        for dataset in ALL_DATASETS:
            with self.subTest(dataset=dataset):
                self.assertEqual(select_datasets("workflow_dispatch", dataset=dataset), [dataset])
        with self.assertRaisesRegex(ValueError, "unsupported evidence dataset"):
            select_datasets("workflow_dispatch", dataset="unknown")

    def test_workflow_matrix_consumes_the_tested_selector(self):
        source = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn('workflows: ["CI", "Services CI", "Security KPI snapshot"]', source)
        self.assertNotIn('"Pact drift check"', source)
        self.assertIn("python3 .github/scripts/select_admin_ui_evidence_datasets.py", source)
        self.assertIn("dataset: ${{ fromJSON(needs.authorize-source.outputs.datasets) }}", source)
        self.assertIn('if [ -n "$ROLLBACK_SNAPSHOT" ] && [ "$SELECTED_DATASET" = all ]; then', source)


if __name__ == "__main__":
    unittest.main()
