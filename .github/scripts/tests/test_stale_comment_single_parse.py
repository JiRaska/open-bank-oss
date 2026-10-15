# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
import importlib.util
import io
import tempfile
import threading
import unittest
from contextlib import redirect_stdout
from concurrent.futures import ThreadPoolExecutor
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

    def test_repo_results_keep_findings_and_notice_order(self):
        source = "openbank-example/src/main/kotlin/Example.kt"
        outcomes = {
            "archived-repo": ("archived", "repository is ARCHIVED"),
            "invisible-repo": ("invisible", "404 — deleted, or private"),
            "ok-repo": ("ok", ""),
            "unknown-repo": ("unknown", "temporary API failure"),
        }
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / source
            path.parent.mkdir(parents=True)
            path.write_text(
                "// JiRaska/unknown-repo JiRaska/archived-repo "
                "JiRaska/invisible-repo JiRaska/ok-repo JiRaska/archived-repo\n"
            )
            with (
                patch.object(subject, "tracked_files", return_value=[source]),
                patch.object(subject, "gitignored", return_value=set()),
                patch.object(subject, "resolve_repo", side_effect=outcomes.__getitem__) as lookup,
                redirect_stdout(io.StringIO()) as output,
            ):
                findings = subject.run(root, want_network=True)
        self.assertEqual(lookup.call_count, 4)
        self.assertEqual(
            [(finding.rule, finding.line, finding.ref) for finding in findings],
            [("repo", 1, "JiRaska/archived-repo"), ("repo", 1, "JiRaska/archived-repo")],
        )
        self.assertIn("JiRaska/invisible-repo, JiRaska/unknown-repo", output.getvalue())
        self.assertIn("404 — deleted, or private", output.getvalue())

    def test_resolve_repos_offline_empty_duplicate_and_bounded(self):
        with patch.object(subject, "resolve_repo") as network:
            self.assertEqual(subject.resolve_repos(set(), True), {})
            self.assertEqual(
                subject.resolve_repos({"b", "a", "a"}, False),
                {"a": ("unknown", "network skipped"), "b": ("unknown", "network skipped")},
            )
            network.assert_not_called()

        active = 0
        maximum = 0
        lock = threading.Lock()
        all_started = threading.Event()
        release = threading.Event()
        calls = []

        def resolve(slug):
            nonlocal active, maximum
            with lock:
                calls.append(slug)
                active += 1
                maximum = max(maximum, active)
                if active == subject.REPO_LOOKUP_WORKERS:
                    all_started.set()
            self.assertTrue(release.wait(timeout=5), "resolver workers were not released")
            with lock:
                active -= 1
            return ("ok", "")

        with patch.object(subject, "resolve_repo", side_effect=resolve):
            with ThreadPoolExecutor(max_workers=1) as caller:
                future = caller.submit(
                    subject.resolve_repos,
                    {"d", "b", "a", "c", "e", "a"},
                    True,
                )
                try:
                    self.assertTrue(all_started.wait(timeout=5), "four resolver calls did not start")
                    self.assertEqual(maximum, subject.REPO_LOOKUP_WORKERS)
                finally:
                    release.set()
                result = future.result(timeout=5)
        self.assertEqual(maximum, subject.REPO_LOOKUP_WORKERS)
        self.assertEqual(len(calls), 5)
        self.assertEqual(list(result), ["a", "b", "c", "d", "e"])
        self.assertEqual(set(result.values()), {("ok", "")})


if __name__ == "__main__":
    unittest.main()
