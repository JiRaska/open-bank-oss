# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "stale_comment_single_parse_subject", Path(__file__).resolve().parents[1] / "check-stale-comment-references.py"
)
subject = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(subject)


class SingleParseTest(unittest.TestCase):
    def test_scan_reuses_comments_and_preserves_path_and_repo_findings(self):
        source = "openbank-example/src/main/kotlin/Example.kt"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / source
            path.parent.mkdir(parents=True)
            path.write_text(
                "// See docs/missing.md and JiRaska/sample-archived-repo\n"
                "// Also JiRaska/sample-archived-repo\nclass Example {}\n"
            )
            with (
                patch.object(subject, "tracked_files", return_value=[source, "docs/current.md"]),
                patch.object(subject, "gitignored", return_value=set()),
                patch.object(subject, "resolve_repo", return_value=("archived", "repository is ARCHIVED")) as network,
                patch.object(subject, "comments_of", wraps=subject.comments_of) as parse,
            ):
                findings = subject.run(root, want_network=True)
            self.assertEqual(parse.call_count, 1)
            network.assert_called_once_with("sample-archived-repo")
            self.assertEqual(
                [(finding.rule, finding.path, finding.line, finding.ref) for finding in findings],
                [("path", source, 1, "docs/missing.md"),
                 ("repo", source, 1, "JiRaska/sample-archived-repo"),
                 ("repo", source, 2, "JiRaska/sample-archived-repo")],
            )


if __name__ == "__main__":
    unittest.main()
