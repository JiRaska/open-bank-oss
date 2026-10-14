#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Real git histories and the actual workflow publication block; no remote writes."""
import importlib.util
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('publication', SCRIPTS / 'deploy-publication-race.py')
publication = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publication)
WORKFLOW = (SCRIPTS.parent / 'workflows/auto-deploy.yml').read_text()


class PublicationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.previous = Path.cwd()
        os.chdir(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)
        self.addCleanup(os.chdir, self.previous)
        self.git('init', '-q')
        self.git('config', 'user.email', 'test@example.com')
        self.git('config', 'user.name', 'test')
        self.git('config', 'commit.gpgsign', 'false')
        self.manifest = 'openbank-infra/gitops/components/example/service.yaml'
        self.write(self.manifest, 'image: registry.example/openbank-example:sandbox-11111111\n')
        self.write('service/version.txt', '1.0\n')
        self.base = self.commit()
        self.write(self.manifest, f'image: registry.example/openbank-example:sandbox-{self.base[:8]}\n')
        self.tip = self.commit()

    def git(self, *args):
        return subprocess.check_output(['git', *args], stderr=subprocess.DEVNULL).decode().strip()

    def write(self, path, value):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        Path(path).write_text(value)

    def commit(self):
        paths = self.git('ls-files', '--others', '--modified', '--exclude-standard').splitlines()
        self.git('add', '--', *paths)
        self.git('commit', '-qm', 'fixture')
        return self.git('rev-parse', 'HEAD')

    def decide(self, **kwargs):
        args = {'event': 'schedule', 'reconcile': '', 'initial': 'sandbox-11111111',
                    'current': 'sandbox-' + self.base[:8], 'candidate': 'sandbox-' + self.tip[:8], 'source': self.tip}
        args.update(kwargs)
        return publication.decision(**args)

    def test_concurrent_pin_only_advance_preserved(self):
        self.assertEqual(self.decide(), 'preserve')
        self.assertEqual(self.decide(event='workflow_dispatch', reconcile='true'), 'preserve')

    def test_all_changed_inputs_publish(self):
        for path in ['service/src/main/App.kt', 'service/version.txt', 'gradle/libs.versions.toml',
                     'service/Dockerfile', '.github/workflows/auto-deploy.yml', 'unknown/input']:
            with self.subTest(path=path):
                self.write(path, 'changed\n')
                tip = self.commit()
                self.assertEqual(self.decide(candidate='sandbox-' + tip[:8], source=tip), 'publish')
                self.git('reset', '--hard', self.tip)

    def test_other_manifest_changes_publish(self):
        self.write(self.manifest, 'image: registry.example/openbank-example:sandbox-22222222\nreplicas: 2\n')
        tip = self.commit()
        self.assertEqual(self.decide(candidate='sandbox-' + tip[:8], source=tip), 'publish')

    def test_manual_and_provenance_refresh_publish(self):
        self.assertEqual(self.decide(event='workflow_dispatch'), 'publish')
        self.assertEqual(self.decide(event='push'), 'publish')
        self.assertEqual(self.decide(candidate='sandbox-' + self.tip[:8] + '-run123'), 'publish')
        self.assertEqual(self.decide(current='sandbox-' + self.base[:8] + '-run123'), 'preserve')

    def test_no_concurrent_advance_publish(self):
        self.assertEqual(self.decide(initial='sandbox-' + self.base[:8]), 'publish')

    def test_unknown_or_malformed_evidence_fails_closed(self):
        for change in [{'current': 'sandbox-pending'}, {'current': 'sandbox-ffffffff'},
                       {'candidate': 'sandbox-nope'}, {'source': 'ffffffff'}, {'source': self.base}]:
            with self.subTest(change=change), self.assertRaises((ValueError, subprocess.CalledProcessError)):
                self.decide(**change)

    def test_workflow_initial_manifest_evidence_missing_aborts(self):
        start = WORKFLOW.index('              initial_tag=')
        end = WORKFLOW.index('              rm -f', start)
        block = WORKFLOW[start:end]
        for missing_file in (True, False):
            with self.subTest(missing_file=missing_file):
                path = 'openbank-infra/gitops/components/new/service.yaml' if missing_file else self.manifest
                source = self.tip
                if not missing_file:
                    self.write(path, 'replicas: 1\n')
                    source = self.commit()
                live = 'image: registry.example/openbank-example:sandbox-' + self.base[:8] + '\n'
                self.write(path, live)
                env = dict(os.environ, GITHUB_SHA=source, GITHUB_EVENT_NAME='schedule', INPUT_RECONCILE='',
                           image='registry.example/openbank-example', f=path, old_tag='sandbox-' + self.base[:8],
                           tag='sandbox-' + source[:8], svc='openbank-example')
                result = subprocess.run(['/bin/bash', '-c', 'set -euo pipefail\nfor once in 1; do\n' + block + '\ndone'],
                                        env=env, capture_output=True, text=True, check=False)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(Path(path).read_text(), live)
                if missing_file:
                    Path(path).unlink()

    def test_actual_workflow_block_preserves_concurrent_pin(self):
        # Execute the extracted block, including continue and sed, rather than a transcription.
        start = WORKFLOW.index('              initial_tag=')
        end = WORKFLOW.index('              rm -f', start)
        block = WORKFLOW[start:end]
        self.write('openbank-infra/gitops/components/admin/ui.yaml', 'image: registry.example/openbank-ui:sandbox-11111111\n')
        intermediate = self.commit()
        self.write('openbank-infra/gitops/components/admin/ui.yaml', 'image: registry.example/openbank-ui:sandbox-22222222\n')
        source = self.commit()
        target = Path('.github/scripts/deploy-publication-race.py')
        target.parent.mkdir(parents=True)
        target.write_bytes((SCRIPTS / target.name).read_bytes())
        self.write(self.manifest, f'image: registry.example/openbank-example:sandbox-{intermediate[:8]}\n')
        env = dict(os.environ, GITHUB_SHA=source, GITHUB_EVENT_NAME='schedule', INPUT_RECONCILE='',
                   image='registry.example/openbank-example', f=self.manifest,
                   old_tag='sandbox-' + intermediate[:8], tag='sandbox-' + source[:8], svc='openbank-example')
        result = subprocess.run(['/bin/bash', '-c', 'set -euo pipefail\nfor once in 1; do\n' + block + '\ndone'],
                                env=env, capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('preserving', result.stdout)
        self.assertNotIn(env['tag'], Path(self.manifest).read_text())


if __name__ == '__main__':
    unittest.main()
