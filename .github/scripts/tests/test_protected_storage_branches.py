"""Regression tests for branch cleanup's long-lived storage ref discovery."""

import importlib.util
from pathlib import Path
import unittest


SOURCE = Path(__file__).resolve().parents[1] / "protected-storage-branches.py"
SPEC = importlib.util.spec_from_file_location("protected_storage_branches", SOURCE)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class StorageBranchTests(unittest.TestCase):
    def test_valid_literal_forms_are_protected(self) -> None:
        text = """
          git clone --branch codex/cflite-corpus-seeds \\
          git clone --branch=codex/equal-form repo
          storage-repo-branch: 'codex/quoted-single'
          storage-repo-branch-coverage: "codex/quoted-double"
        """
        self.assertEqual(
            MODULE.branches_in(text),
            {
                "codex/cflite-corpus-seeds",
                "codex/equal-form",
                "codex/quoted-single",
                "codex/quoted-double",
            },
        )

    def test_comments_and_dynamic_refs_are_not_promoted_to_literals(self) -> None:
        text = """
          # git clone --branch codex/obsolete
          git clone --branch "$DYNAMIC_BRANCH" repo
          storage-repo-branch: ${{ inputs.branch }}
        """
        self.assertEqual(MODULE.branches_in(text), set())


if __name__ == "__main__":
    unittest.main()
