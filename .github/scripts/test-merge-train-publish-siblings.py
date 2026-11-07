#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Publish admission must have the same trusted sibling scripts as settle."""
import contextlib
import importlib.util
import io
import pathlib
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('merge_train_publish', pathlib.Path(__file__).with_name('merge-train.py'))
train = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = train
spec.loader.exec_module(train)


class PublishSiblingsTest(unittest.TestCase):
    def test_missing_publish_admission_siblings_are_rejected(self):
        source = train.WORKFLOW.read_text()
        publish = train.job_sections(source)['publish']
        for sibling in ('check-security-checklist.py', 'merge_train_carrier.py'):
            with self.subTest(sibling=sibling), tempfile.TemporaryDirectory() as directory:
                mutated = publish.replace(sibling, '')
                path = pathlib.Path(directory) / 'workflow.yml'
                path.write_text(source.replace(publish, mutated))
                original = train.WORKFLOW
                try:
                    train.WORKFLOW = path
                    with contextlib.redirect_stdout(io.StringIO()) as output:
                        result = train.check_declaration()
                    self.assertNotEqual(result, 0)
                    self.assertIn('publish copies the admission gate', output.getvalue())
                finally:
                    train.WORKFLOW = original


if __name__ == '__main__':
    unittest.main()
