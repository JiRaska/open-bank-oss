"""Reject incomplete experimental model receipts before a success verdict."""
import importlib.util
import contextlib
import io
from types import SimpleNamespace
from unittest import mock
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location(
    'global_metadata', Path(__file__).with_name('experimental-global-metadata.py'))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class GlobalMetadataInventoryTest(unittest.TestCase):
    def check(self, model):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'openbank-a.json').write_text(json.dumps(model))
            return MODULE.check_inventory(root, ['openbank-a'])

    def test_omitted_configuration_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'inventory'):
            self.check({'configurations': ['compileClasspath', 'runtimeClasspath'],
                        'receipts': {'compileClasspath': []}})

    def test_empty_receipts_cannot_hide_declared_configuration(self):
        with self.assertRaisesRegex(ValueError, 'inventory'):
            self.check({'configurations': ['runtimeClasspath'], 'receipts': {}})

    def test_empty_graph_is_valid_only_with_empty_declared_inventory(self):
        self.assertEqual(self.check({'configurations': [], 'receipts': {}}), (0, set()))
        with self.assertRaises(ValueError):
            self.check({'receipts': {}})

    def test_malformed_artifact_is_rejected(self):
        for row in [None, {'component': 'g:a:1', 'artifact': 'a.jar', 'sha256': 'invalid'}]:
            with self.subTest(row=row), self.assertRaises(ValueError):
                self.check({'configurations': ['runtimeClasspath'],
                            'receipts': {'runtimeClasspath': [row]}})

    def test_same_name_in_project_and_buildscript_is_distinct(self):
        project = {'component': 'g:app:1', 'artifact': 'app.jar', 'sha256': 'a' * 64}
        plugin = {'component': 'g:plugin:1', 'artifact': 'plugin.jar', 'sha256': 'b' * 64}
        model = {'configurations': ['project:classpath', 'buildscript:classpath'],
                 'receipts': {'project:classpath': [project], 'buildscript:classpath': [plugin]}}
        count, artifacts = self.check(model)
        self.assertEqual(count, 2)
        self.assertEqual(len(artifacts), 2)
        del model['receipts']['buildscript:classpath']
        with self.assertRaisesRegex(ValueError, 'inventory'):
            self.check(model)

    def test_missing_model_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory, self.assertRaises(ValueError):
            MODULE.check_inventory(Path(directory), ['openbank-a'])


class GlobalMetadataBatchTest(unittest.TestCase):
    def test_partition_covers_fleet_once(self):
        modules = [f'openbank-{index}' for index in range(79)]
        batches = MODULE.module_batches(modules, 16)
        self.assertEqual([len(batch) for batch in batches], [16, 16, 16, 16, 15])
        self.assertEqual([m for batch in batches for m in batch], modules)
        with self.assertRaises(ValueError):
            MODULE.module_batches(modules, 0)

    def exercise(self, fail_call=None, omit_call=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('openbank-a', 'openbank-b', 'openbank-c', 'openbank-d', 'openbank-e'):
                (root / name).mkdir()
                (root / name / 'build.gradle.kts').touch()
            calls = []
            def run(command, env):
                calls.append((command, env))
                if len(calls) == fail_call:
                    return SimpleNamespace(returncode=1)
                if 'OB_PLUGIN_MODEL_DIR' in env:
                    output = Path(env['OB_PLUGIN_MODEL_DIR'])
                    names = ['openbank', 'build-logic']
                else:
                    output = Path(env['OB_METADATA_MODEL_DIR'])
                    names = [name[1:] for name in env['OB_METADATA_PROJECTS'].split(',')]
                output.mkdir(parents=True)
                if len(calls) == omit_call:
                    names = names[:-1]
                for name in names:
                    (output / (name + '.json')).write_text(json.dumps({'configurations': [], 'receipts': {}}))
                return SimpleNamespace(returncode=0)
            text = io.StringIO()
            with mock.patch.object(MODULE.Path, 'cwd', return_value=root), mock.patch.object(MODULE.subprocess, 'run', side_effect=run), contextlib.redirect_stdout(text):
                try:
                    code = MODULE.main(['--batch-size', '2'])
                except ValueError as error:
                    code = error
            return code, calls, text.getvalue()

    def test_all_batches_keep_strict_refresh_and_plugin_phase(self):
        code, calls, output = self.exercise()
        self.assertEqual(code, 0)
        self.assertEqual(len(calls), 4)
        self.assertEqual([len(env['OB_METADATA_PROJECTS'].split(',')) for _,env in calls[1:]], [2,2,1])
        for command, _ in calls:
            self.assertIn('--refresh-dependencies', command)
            self.assertEqual(command[command.index('--dependency-verification')+1], 'strict')
            self.assertNotIn('--write-verification-metadata', command)
        self.assertIn('Strict resolution passed: 5 modules', output)

    def test_failed_batch_stops_without_success_verdict(self):
        code, calls, output = self.exercise(fail_call=3)
        self.assertEqual(code, 2)
        self.assertEqual(len(calls), 3)
        self.assertNotIn('Strict resolution passed', output)

    def test_missing_batch_receipt_is_rejected(self):
        code, calls, output = self.exercise(omit_call=3)
        self.assertIsInstance(code, ValueError)
        self.assertEqual(len(calls), 3)
        self.assertNotIn('Strict resolution passed', output)


if __name__ == '__main__':
    unittest.main()
