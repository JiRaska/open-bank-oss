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
