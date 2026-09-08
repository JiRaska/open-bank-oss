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
    def test_empty_queue(self):
        self.assertEqual(admission.admit([[]]), (True, 0))

    def test_capacity_boundary(self):
        self.assertEqual(admission.admit([[pr(1), pr(2)]]), (True, 2))
        self.assertEqual(admission.admit([[pr(1), pr(2), pr(3)]]), (False, 3))

    def test_pagination_drafts_and_other_branches(self):
        self.assertEqual(admission.admit([[pr(1, draft=True), pr(2)],
                                          [pr(3), pr(4, 'fix/human')]]), (False, 3))

    def test_invalid_snapshot_fails_closed(self):
        for pages in (None, {}, [], [None], [[{}]], [[pr(1), pr(1)]],
                      [[{'number': 1, 'state': 'closed', 'head': {'ref': 'agent/x'}}]]):
            with self.subTest(pages=pages), self.assertRaises(ValueError):
                admission.admit(pages)


if __name__ == '__main__':
    unittest.main()
