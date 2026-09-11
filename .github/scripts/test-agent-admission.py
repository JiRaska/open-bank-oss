#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Exercise admission boundaries and workflow wiring without credentials or network."""
import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('admission', Path(__file__).with_name('agent-admission.py'))
admission = importlib.util.module_from_spec(spec)
spec.loader.exec_module(admission)


def pr(number, branch='agent/fix', **extra):
    return dict(number=number, head={'ref': branch}, state='open', **extra)


class AdmissionTest(unittest.TestCase):
    prefixes = ('agent/', 'codex/')

    def test_empty_queue(self):
        self.assertEqual(admission.admit([[]], self.prefixes), (True, 0))

    def test_capacity_boundary(self):
        self.assertEqual(admission.admit([[pr(1), pr(2, 'codex/fix')]], self.prefixes), (True, 2))
        self.assertEqual(admission.admit([[pr(1), pr(2, 'codex/fix'), pr(3)]], self.prefixes), (False, 3))

    def test_pagination_drafts_and_other_branches(self):
        self.assertEqual(admission.admit([[pr(1, draft=True), pr(2, 'codex/fix')],
                                          [pr(3), pr(4, 'fix/human')]], self.prefixes), (False, 3))

    def test_invalid_snapshot_fails_closed(self):
        for pages in (None, {}, [], [None], [[{}]], [[pr(1), pr(1)]],
                      [[{'number': 1, 'state': 'closed', 'head': {'ref': 'agent/x'}}]]):
            with self.subTest(pages=pages), self.assertRaises(ValueError):
                admission.admit(pages, self.prefixes)

    def test_rules_are_the_prefix_authority(self):
        self.assertIn('agent/', admission.load_prefixes())
        self.assertIn('codex/', admission.load_prefixes())

    def test_invalid_rules_fail_closed(self):
        import tempfile
        for content in ('{}', 'autonomous_agent_prs: {}', 'autonomous_agent_prs:\n  agent_branch_prefixes: []'):
            with tempfile.NamedTemporaryFile(mode='w', suffix='.yaml') as rules:
                rules.write(content)
                rules.flush()
                with self.assertRaises(ValueError):
                    admission.load_prefixes(rules.name)


if __name__ == '__main__':
    unittest.main()
