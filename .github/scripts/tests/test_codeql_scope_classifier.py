#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run the real CodeQL workflow classifier, including pipefail and large diffs."""
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path

WORKFLOW = Path(__file__).resolve().parents[2] / "workflows/security.yml"


class CodeqlScopeClassifierTest(unittest.TestCase):
    def classify(self, paths):
        workflow = WORKFLOW.read_text()
        start = workflow.index("          JAVA_RE=")
        end = workflow.index("          # schedule / manual:", start)
        script = textwrap.dedent(workflow[start:end])
        script += '\nclassify "$(< "$1")"\nprintf "%s %s\\n" "$J" "$S"\n'
        with tempfile.TemporaryDirectory() as directory:
            changed = Path(directory) / "changed.txt"
            changed.write_text(paths)
            result = subprocess.run(
                ["bash", "-e", "-o", "pipefail", "-c", script, "scope", str(changed)],
                check=True, capture_output=True, text=True,
            )
        return result.stdout.strip()

    def java_on_pr(self, paths, head_ref="", switch=""):
        workflow = WORKFLOW.read_text()
        start = workflow.index("          JAVA_RE=")
        end = workflow.index("          # schedule / manual:", start)
        script = textwrap.dedent(workflow[start:end])
        script += '\nif java_leg_on_pr "$(< "$1")"; then echo run; else echo carrier; fi\n'
        with tempfile.TemporaryDirectory() as directory:
            changed = Path(directory) / "changed.txt"
            changed.write_text(paths)
            result = subprocess.run(
                ["bash", "-e", "-o", "pipefail", "-c", script, "scope", str(changed)],
                check=True, capture_output=True, text=True,
                env={"PATH": "/usr/bin:/bin", "HEAD_REF": head_ref, "CODEQL_JAVA_ON_PR": switch},
            )
        return result.stdout.strip()

    def test_java_leg_runs_on_carriers_not_on_every_pull_request(self):
        kotlin = "openbank-ledger-service/src/main/kotlin/Service.kt\n"
        for head_ref, paths, switch, expected in (
            ("feat/ledger-thing", kotlin, "", "carrier"),
            ("agent/x", kotlin, "", "carrier"),
            ("train/43072622258-1", kotlin, "", "run"),
            ("train/strict-43014906065-1", kotlin, "", "run"),
            # Only the train's own branch shape counts, never a look-alike.
            ("train/evil", kotlin, "", "carrier"),
            ("feat/train/1-1", kotlin, "", "carrier"),
            ("xtrain/1-1", kotlin, "", "carrier"),
            # Changing the analysis itself must exercise real extraction on that PR.
            ("ci/x", ".github/workflows/security.yml\n", "", "run"),
            ("ci/x", kotlin + ".github/scripts/codeql-force-compilers.init.gradle\n", "", "run"),
            ("ci/x", "docs/.github/workflows/security.yml\n", "", "carrier"),
            # Kill switch: exactly "true" restores the leg everywhere.
            ("feat/ledger-thing", kotlin, "true", "run"),
            ("feat/ledger-thing", kotlin, "false", "carrier"),
            ("feat/ledger-thing", kotlin, "1", "carrier"),
        ):
            with self.subTest(head_ref=head_ref, paths=paths.strip(), switch=switch):
                self.assertEqual(self.java_on_pr(paths, head_ref, switch), expected)

    def test_pull_request_branch_applies_the_carrier_rule(self):
        # The rule is only worth anything if the pull_request path calls it, on both of its arms.
        workflow = WORKFLOW.read_text()
        start = workflow.index('          if [ "$EVENT" = "pull_request" ]; then')
        end = workflow.index("          # First push of a branch", start)
        self.assertEqual(workflow[start:end].count("java_leg_on_pr"), 2)
        self.assertNotIn("emit_matrix true true", workflow[start:end])
        self.assertIn("HEAD_REF: ${{ github.head_ref }}", workflow)

    def test_large_mixed_diff_retains_both_languages(self):
        paths = "openbank-ledger-service/src/main/kotlin/Service.kt\nopenbank-admin-ui/src/App.tsx\n"
        self.assertEqual(self.classify(paths + "docs/generated-long-path.md\n" * 30000), "true true")

    def test_language_scoping_and_docs_remain_distinct(self):
        for paths, expected in (
            ("openbank-ledger-service/src/main/kotlin/Service.kt\n", "true false"),
            ("openbank-admin-ui/src/App.tsx\n", "false true"),
            ("docs/generated-long-path.md\n" * 30000, "false false"),
            (".github/scripts/codeql-force-compilers.init.gradle\n", "true false"),
            (".github/workflows/security.yml\n", "true false"),
            (".github/workflows/issue-hygiene.yml\n", "false false"),
        ):
            with self.subTest(expected=expected):
                self.assertEqual(self.classify(paths), expected)


if __name__ == "__main__":
    unittest.main()
