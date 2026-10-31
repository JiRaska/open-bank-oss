from __future__ import annotations

import unittest
from pathlib import Path

from select_admin_ui_evidence_datasets import ALL_DATASETS, WORKFLOW_PATHS, select_datasets, select_source

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

    def test_failed_performance_attempt_refreshes_current_catalogue_only(self):
        for name in ("Performance gate", "Perf baseline"):
            for conclusion in ("success", "failure", "cancelled", "timed_out", "skipped"):
                with self.subTest(name=name, conclusion=conclusion):
                    self.assertEqual(select_datasets("workflow_run", name), ["test-intelligence"])
                    self.assertEqual(select_source("workflow_run", event_sha="a" * 40,
                        repository="owner/repo", workflow_name=name, workflow_path=WORKFLOW_PATHS[name],
                        workflow_sha="b" * 40, workflow_repository="owner/repo", workflow_branch="main",
                        workflow_status="completed", workflow_conclusion=conclusion), "a" * 40)

    def test_source_authorization_rejects_forks_paths_branches_and_unfinished_runs(self):
        base = dict(event_sha="a" * 40, repository="owner/repo", workflow_name="Performance gate",
                    workflow_path=WORKFLOW_PATHS["Performance gate"], workflow_sha="b" * 40,
                    workflow_repository="owner/repo", workflow_branch="main",
                    workflow_status="completed", workflow_conclusion="failure")
        for change in ({"workflow_repository": "fork/repo"}, {"workflow_branch": "feature"},
                       {"workflow_path": ".github/workflows/other.yml"}, {"workflow_sha": "b" * 7},
                       {"workflow_status": "in_progress"}, {"workflow_conclusion": ""},
                       {"event_sha": "not-a-sha"}, {"workflow_name": "untrusted"}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                select_source("workflow_run", **(base | change))

    def test_nonperformance_producers_still_require_success_and_keep_source_identity(self):
        for name in ("CI", "Services CI", "Security KPI snapshot"):
            base = dict(event_sha="a" * 40, repository="owner/repo", workflow_name=name,
                        workflow_path=WORKFLOW_PATHS[name], workflow_sha="b" * 40,
                        workflow_repository="owner/repo", workflow_branch="main", workflow_status="completed")
            with self.assertRaises(ValueError):
                select_source("workflow_run", **base, workflow_conclusion="failure")
            self.assertEqual(select_source("workflow_run", **base, workflow_conclusion="success"),
                             "a" * 40 if name == "Security KPI snapshot" else "b" * 40)

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
        self.assertIn('workflows: ["CI", "Services CI", "Security KPI snapshot", "Performance gate", "Perf baseline"]', source)
        self.assertNotIn('"Pact drift check"', source)
        self.assertIn("python3 .github/scripts/select_admin_ui_evidence_datasets.py", source)
        self.assertIn("dataset: ${{ fromJSON(needs.authorize-source.outputs.datasets) }}", source)
        self.assertIn('if [ -n "$ROLLBACK_SNAPSHOT" ] && [ "$SELECTED_DATASET" = all ]; then', source)


if __name__ == "__main__":
    unittest.main()
