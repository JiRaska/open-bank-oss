#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Exercise actual settle/strict controllers with bounded fresh main-health reads."""
import importlib.util
import pathlib
import subprocess
import sys
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('fresh_train', pathlib.Path(__file__).with_name('merge-train.py'))
TRAIN = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = TRAIN
SPEC.loader.exec_module(TRAIN)
CTX = 'Validate manifests'


class FreshMainTests(unittest.TestCase):
    def settle(self, results):
        class API:
            def __init__(self):
                self.reads = 0
                self.writes = []
            def checks(self, sha):
                self.reads += 1
                value = results[min(self.reads - 1, len(results) - 1)]
                if isinstance(value, Exception):
                    raise value
                return value
            def merge_guard(self): return None
            def required(self): return [CTX, TRAIN.STATUS_CONTEXT]
            def open_prs(self): return []
            def ci_activity(self, *_args): return None
            def main_sha(self): return 'main'
            def post_status(self, *args, **kwargs): self.writes.append(('status', args))
            def merge(self, *args):
                self.writes.append(('merge', args))
                return 'new-main'
        gh = API()
        old = {'number': 12954, 'head_sha': 'older', 'files': []}
        release = {'number': 14109, 'head_sha': 'release', 'files': []}
        strict = [('pending', old), ('green', release)]
        view = ('main', '', strict, [], [], {}, {CTX: 'ancestor'}, {'main': {CTX: None}})
        def git(*_args, **_kwargs):
            return subprocess.CompletedProcess([], 0, '', '')
        with patch.multiple(TRAIN, settle_view=lambda *_a: view,
                            strict_fixes=lambda *_a: {14109: [CTX]},
                            strict_reservation=lambda *_a, **_k: (14109, []),
                            regen_inventory_fragments=lambda *_a: [],
                            partition=lambda *_a: ([], False), train_turn=lambda *_a: None,
                            live_source_intent=lambda *_a: True,
                            stale_red_refresh=lambda *_a: None):
            summary = TRAIN.mode_settle(gh, git, 'o/r', False)
        return gh, summary

    def test_recovered_before_reservation_blocks_unrelated_release_and_build(self):
        gh, summary = self.settle([{CTX: 'success'}])
        self.assertEqual(gh.writes, [])
        self.assertTrue(summary['in_flight'])
        self.assertIn('main_health_deferred', summary)
        self.assertEqual(gh.reads, 1)

    def test_recovered_immediately_before_write_blocks_every_later_mutation(self):
        gh, summary = self.settle([{CTX: 'failure'}, {CTX: 'success'}])
        self.assertEqual(gh.writes, [])
        self.assertTrue(summary['in_flight'])
        self.assertIn('main_health_deferred', summary)
        self.assertEqual(gh.reads, 2)

    def test_persistent_red_preserves_existing_verified_fix_merge(self):
        gh, summary = self.settle([{CTX: 'failure'}])
        self.assertEqual(summary['merged'], [14109])
        self.assertEqual([kind for kind, _args in gh.writes], ['status', 'merge'])
        self.assertEqual(gh.reads, 2)

    def test_read_failure_defers_without_reporting_success(self):
        gh, summary = self.settle([RuntimeError('permission unavailable')])
        self.assertEqual(gh.writes, [])
        self.assertTrue(summary['in_flight'])
        self.assertEqual(gh.reads, 1)

    def test_pending_missing_preserve_existing_ancestor_red_semantics(self):
        for result in ({CTX: None}, {}, {CTX: "skipped"}, {CTX: "neutral"}):
            with self.subTest(result=result):
                gh, summary = self.settle([result])
                self.assertEqual(summary['merged'], [14109])
                self.assertEqual(gh.reads, 2)


if __name__ == '__main__':
    unittest.main()
