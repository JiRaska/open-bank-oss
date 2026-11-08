#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Exercise the deployed sparse-checkout script against a local Git repository."""
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import unittest

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
MANIFEST = ROOT / 'openbank-infra/gitops/components/flaky-test-hunter/flaky-test-hunter.yaml'
DEPLOYMENT = next(d for d in yaml.safe_load_all(MANIFEST.read_text()) if d['kind'] == 'Deployment')
POD = DEPLOYMENT['spec']['template']['spec']
INIT = next(c for c in POD['initContainers'] if c['name'] == 'bounded-test-sources')
APP = next(c for c in POD['containers'] if c['name'] == 'flaky-test-hunter')
SOURCE = ROOT / 'openbank-flaky-test-hunter/src/main/kotlin/com/openbank/flakytest/infrastructure/adapter/GitHubProposalAdapter.kt'
PREFIX = re.search(r'OWN_TEST_SOURCE_PREFIX\s*=\s*"([^"]+)"', SOURCE.read_text()).group(1)
REMOTE = 'https://github.com/JiRaska/open-bank-oss.git'


class PilotSourceTest(unittest.TestCase):
    def run_checkout(self, *, missing_source=False, unavailable=False):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            repo = root/'upstream'; repo.mkdir()
            files = {'openbank-other/src/test/kotlin/Other.kt': 'other-module',
                     'openbank-flaky-test-hunter/src/main/kotlin/Production.kt': 'production-code'}
            if not missing_source:
                files[PREFIX+'ExampleTest.kt'] = 'class ExampleTest {}\n'
            for name, content in files.items():
                path = repo/name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(content)
            def git(*args):
                return subprocess.run(['git', '-C', str(repo), *args], check=True,
                                      capture_output=True, text=True, timeout=10)
            git('init', '-q', '-b', 'main')
            git('add', *files)
            git('-c', 'commit.gpgsign=false', '-c', 'user.name=Fixture',
                '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'fixture')
            destination = root/'source'/'repo'; destination.parent.mkdir()
            # Prove retries clean only the owned scratch checkout.
            destination.mkdir(); (destination/'incomplete').write_text('partial clone')
            upstream = root/'unavailable' if unavailable else repo
            script = INIT['args'][0].replace(REMOTE, str(upstream)).replace('/source/repo', str(destination))
            commands = root/'commands'; commands.mkdir()
            # macOS lacks GNU timeout. The fixture supplies the same bounded command
            # semantics; CI executes the image's native coreutils timeout.
            timeout = commands/'timeout'
            timeout.write_text('#!'+sys.executable+'\nimport subprocess,sys\n'
                               'assert sys.argv[1]=="120s"\n'
                               'sys.exit(subprocess.run(sys.argv[2:],timeout=120).returncode)\n')
            timeout.chmod(0o700)
            result = subprocess.run(['bash', '-ec', script], capture_output=True, text=True, timeout=20,
                                    env={**os.environ, 'PATH':str(commands)+os.pathsep+os.environ['PATH']})
            present = sorted(str(p.relative_to(destination)) for p in destination.rglob('*')
                             if p.is_file() and '.git' not in p.relative_to(destination).parts)
            revision = git('rev-parse', 'HEAD').stdout.strip()
            return result, present, revision

    def test_only_the_writer_module_test_tree_is_checked_out_and_revision_is_reported(self):
        result, present, revision = self.run_checkout()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(present, [PREFIX+'ExampleTest.kt'])
        self.assertEqual(result.stdout.strip().splitlines()[-1], revision)

    def test_unavailable_repository_does_not_become_a_successful_empty_scan(self):
        result, _, _ = self.run_checkout(unavailable=True)
        self.assertNotEqual(result.returncode, 0)

    def test_missing_test_tree_fails_startup(self):
        result, _, _ = self.run_checkout(missing_source=True)
        self.assertNotEqual(result.returncode, 0)

    def test_snapshot_is_read_only_and_the_fetcher_has_no_secret_inputs(self):
        mount = next(m for m in APP['volumeMounts'] if m['name']=='test-sources')
        self.assertIs(mount['readOnly'], True)
        self.assertEqual(mount['mountPath'], '/source')
        self.assertEqual(INIT.get('env', []), [])
        self.assertEqual(INIT.get('envFrom', []), [])
        self.assertIs(POD['automountServiceAccountToken'], False)
        self.assertEqual([m['name'] for m in INIT['volumeMounts']], ['test-sources'])
        self.assertIs(INIT['securityContext']['readOnlyRootFilesystem'], True)
        env = {e['name']:e.get('value') for e in APP['env']}
        self.assertEqual(env['FLAKY_TEST_HUNTER_REPO_ROOT'], '/source/repo')
        self.assertEqual(env['FLAKY_TEST_HUNTER_POLICY_GATE_ENABLED'], 'true')
        runner = (ROOT/'openbank-infra/aws/envs/sandbox-platform/arc-runners.tf').read_text()
        self.assertIn(INIT['image'].split('@sha256:')[1], runner)


if __name__ == '__main__':
    unittest.main()
