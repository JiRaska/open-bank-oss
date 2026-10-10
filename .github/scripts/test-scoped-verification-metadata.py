#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Exercise scope fallback and preserve real gate verdicts without running Gradle."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))
spec = importlib.util.spec_from_file_location('scoped_gate', Path(__file__).with_name('scoped-verification-metadata.py'))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
COMMIT = 'a' * 40
PROJECTS = {':openbank-account-service', ':openbank-libs'}
MODELS = {p: {'closure': [p], 'receipts': {}} for p in PROJECTS}


class IntegrationTest(unittest.TestCase):
    def invoke(self, verdict, dirty=False, inputs=True, models=MODELS):
        with patch.object(sys, 'argv', ['gate', '--modules', 'openbank-account-service', '--enforce']), \
             patch.object(gate, 'source_identity', return_value=None if dirty else COMMIT), \
             patch.object(gate, 'build_profile_matches', return_value=True), \
             patch.object(gate, 'tracked_projects', return_value=PROJECTS), \
             patch.object(gate, 'collect', return_value=models), \
             patch.object(gate, 'declared_inputs_fit', return_value=inputs), \
             patch.object(gate, 'archive_source') as archive, \
             patch.object(gate, 'run_gate', return_value=verdict) as run:
            result = gate.main()
            return result, run.call_args, archive.call_count

    def test_scoped_gate_preserves_all_verdicts(self):
        for verdict in (0, 1, 2):
            with self.subTest(verdict=verdict):
                result, call, archived = self.invoke(verdict)
                self.assertEqual(result, verdict)
                self.assertEqual(call.args[0].name, 'checkout')
                self.assertEqual(call.args[1:], ('openbank-account-service', True, COMMIT))
                self.assertEqual(archived, 1)

    def test_dirty_source_uses_original_gate(self):
        result, call, archived = self.invoke(2, dirty=True)
        self.assertEqual(result, 2)
        self.assertEqual(call.args[0], Path.cwd())
        self.assertEqual(archived, 0)

    def test_omitted_task_input_uses_original_gate(self):
        result, call, archived = self.invoke(1, inputs=False)
        self.assertEqual(result, 1)
        self.assertEqual(call.args[0], Path.cwd())
        self.assertEqual(archived, 0)

    def test_missing_model_uses_original_gate(self):
        result, call, archived = self.invoke(0, models={})
        self.assertEqual(call.args[0], Path.cwd())
        self.assertEqual(archived, 0)

    def test_unsupported_selection_never_collects(self):
        with patch.object(sys, 'argv', ['gate', '--modules', 'all', '--enforce']), \
             patch.object(gate, 'collect') as collect, patch.object(gate, 'run_gate', return_value=1) as run:
            self.assertEqual(gate.main(), 1)
            collect.assert_not_called()
            run.assert_called_once_with(Path.cwd(), 'all', True)

    def test_malformed_and_omitted_input_receipts_decline_scope(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(gate.subprocess, 'run'):
            directory = Path(tmp)
            valid = [{'task': ':task', 'localInputs': ['openbank-account-service/src/main/A.kt']}]
            (directory/'build-logic.json').write_text(json.dumps(valid))
            for invalid in ([{}], [{'localInputs': ['../escape']}], [{'localInputs': ['/absolute']}],
                            [{'localInputs': ['openbank-libs/src/main/A.kt']}], []):
                with self.subTest(invalid=invalid):
                    (directory/'openbank.json').write_text(json.dumps(invalid))
                    self.assertFalse(gate.declared_inputs_fit(Path.cwd(), [':openbank-account-service'], PROJECTS, directory))


if __name__ == '__main__':
    unittest.main()
