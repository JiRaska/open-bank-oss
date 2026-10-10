"""Reject incomplete experimental model receipts before a success verdict."""
import importlib.util
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

    def test_missing_model_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory, self.assertRaises(ValueError):
            MODULE.check_inventory(Path(directory), ['openbank-a'])


if __name__ == '__main__':
    unittest.main()
