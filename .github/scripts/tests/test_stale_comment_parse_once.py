#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    'stale_comments', Path(__file__).resolve().parents[1] / 'check-stale-comment-references.py',
)
scanner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scanner)


class ParseOnceTest(unittest.TestCase):
    def test_run_retains_path_repo_and_waiver_semantics_with_one_parse(self):
        files = ['src/Example.kt', 'docs/existing.md']
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'src').mkdir()
            (root / files[0]).write_text(
                '// See docs/missing.yml and JiRaska/openbank-retired\n'
                'class Example\n'
                '/* docs/waived.yml JiRaska/openbank-waived\n'
                ' * stale-ref-ok: intentionally hypothetical example */\n',
            )
            with patch.object(scanner, 'tracked_files', return_value=files), \
                    patch.object(scanner, 'gitignored', return_value=set()), \
                    patch.object(scanner, 'resolve_repo', return_value=('archived', 'archived')) as repo, \
                    patch.object(scanner, 'comments_of', wraps=scanner.comments_of) as parser:
                findings = scanner.run(root, want_network=True)
            self.assertEqual([(f.rule, f.line, f.ref) for f in findings], [
                ('path', 1, 'docs/missing.yml'),
                ('repo', 1, 'JiRaska/openbank-retired'),
            ])
            parser.assert_called_once()
            repo.assert_called_once_with('openbank-retired')


if __name__ == '__main__':
    unittest.main()
