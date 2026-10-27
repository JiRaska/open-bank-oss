#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise producer branch identity and the actual workflow carry block."""
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]


class ProducerIsolationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = yaml.safe_load((ROOT / '.github/workflows/auto-deploy.yml').read_text())
        cls.steps = cls.workflow['jobs']['gitops-pr']['steps']

    def test_same_source_independent_runs_have_distinct_candidate_branches(self):
        create = next(step for step in self.steps if step.get('id') == 'gitops_pr')
        template = create['with']['branch']
        def branch(run_id):
            return template.replace('${{ github.sha }}', 'b' * 40).replace('${{ needs.changes.outputs.source_commit }}', 'a' * 40).replace('${{ github.run_id }}', str(run_id))
        self.assertNotEqual(branch(101), branch(102))
        self.assertNotIn('github.run_attempt', template)  # retry retains producer identity
        self.assertEqual(branch(101), 'chore/gitops-auto-deploy-' + 'a' * 40 + '-run101')
        self.assertTrue(branch(101).startswith('chore/gitops-auto-deploy-'))
        self.assertNotIn('b' * 40, branch(101))  # workflow identity is not the promoted source
        self.assertNotIn('${{', branch(101))

    def test_gate_stops_after_a_failed_decision_suite(self):
        gates = yaml.safe_load((ROOT / '.github/gates/gates.yaml').read_text())
        entries = gates if isinstance(gates, list) else gates['gates']
        command = next(gate['run'] for gate in entries if gate['id'] == 'deploy-window-decision')
        with tempfile.TemporaryDirectory() as d:
            directory = Path(d)
            marker = directory / 'calls'
            stub = directory / 'python3'
            stub.write_text('#!/bin/sh\necho "$*" >> "$MARKER"\nexit 1\n')
            stub.chmod(0o755)
            result = subprocess.run(['bash', '-c', command], env={**os.environ,
                                    'PATH': d + os.pathsep + os.environ['PATH'], 'MARKER': str(marker)},
                                    capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(marker.read_text().splitlines(), ['.github/scripts/deploy-window.py --self-test'])

    def test_final_freshness_restores_history_without_accepting_stale_sources(self):
        verify = next(step for step in self.steps if step.get('name') == 'Verify GitOps image source freshness')
        clean_env = {key: value for key, value in os.environ.items() if not key.startswith('GIT_')}
        clean_env['GIT_CONFIG_NOSYSTEM'] = '1'
        for case in ('equivalent', 'changed', 'unknown', 'fetch-failed'):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as d:
                directory = Path(d)
                origin = directory / 'origin'
                origin.mkdir()

                def git(*args, cwd=origin, check=True):
                    return subprocess.run(['git', '-c', 'commit.gpgsign=false', '-c', 'core.hooksPath=/dev/null',
                                           *args], cwd=cwd, env=clean_env, check=check, capture_output=True, text=True)

                git('init', '-q', '-b', 'main')
                git('config', 'user.email', 'test@example.invalid')
                git('config', 'user.name', 'test')
                scripts = origin / '.github/scripts'
                scripts.mkdir(parents=True)
                for name in ('deploy-window.py', 'pact-version-tree-equivalent.sh'):
                    shutil.copyfile(ROOT / '.github/scripts' / name, scripts / name)
                workflow = origin / '.github/workflows/auto-deploy.yml'
                workflow.parent.mkdir()
                workflow.write_text('env:\n  ANALYTICS_SINK_TYPE: clickhouse\n  AGENT_SVID_NONCE_STORE: redis\n')
                service = origin / 'openbank-test-service'
                service.mkdir()
                (service / 'build.gradle.kts').write_text('// fixture\n')
                source = service / 'App.kt'
                source.write_text('v1\n')
                git('add', '.github/scripts', '.github/workflows/auto-deploy.yml', 'openbank-test-service')
                git('commit', '-qm', 'seed source')
                image_source = git('rev-parse', 'HEAD').stdout.strip()
                (origin / 'docs').mkdir()
                (origin / 'docs/note.md').write_text('unrelated documentation\n')
                git('add', 'docs/note.md')
                git('commit', '-qm', 'advance main without changing image inputs')
                work = directory / 'work'
                git('clone', '-q', '--depth=100', origin.as_uri(), str(work))
                git('checkout', '--detach', cwd=work)
                # Reproduce create-pull-request's fetch while preserving the same HEAD and objects.
                git('fetch', '-q', '--depth=1', 'origin', 'main:main', cwd=work)
                self.assertEqual(git('rev-parse', '--verify', image_source, cwd=work).returncode, 0)
                self.assertNotEqual(git('merge-base', '--is-ancestor', image_source, 'HEAD',
                                        cwd=work, check=False).returncode, 0)
                if case == 'changed':
                    source.write_text('v2\n')
                    git('add', 'openbank-test-service/App.kt')
                    git('commit', '-qm', 'change the actual image source')
                if case == 'fetch-failed':
                    git('remote', 'set-url', 'origin', (directory / 'missing').as_uri(), cwd=work)
                tag = 'sandbox-' + (image_source[:8] if case != 'unknown' else 'ffffffff')
                diff = directory / 'proposal.diff'
                diff.write_text('--- a/openbank-infra/gitops/test.yaml\n'
                                '+++ b/openbank-infra/gitops/test.yaml\n'
                                '-image: registry.invalid/openbank-test-service:sandbox-00000000\n'
                                f'+image: registry.invalid/openbank-test-service:{tag}\n')
                marker = directory / 'reads'
                env = {**clean_env, 'PR_NUMBER': '1', 'GITHUB_REPOSITORY': 'example/repo',
                       'PROPOSAL_DIFF': str(diff), 'READ_MARKER': str(marker)}
                gh = 'gh() { printf "read\\n" >> "$READ_MARKER"; cat "$PROPOSAL_DIFF"; }\n'
                result = subprocess.run(['bash', '-c', gh + verify['run']], cwd=work, env=env,
                                        capture_output=True, text=True, check=False)
                self.assertEqual(result.returncode == 0, case == 'equivalent', result.stdout + result.stderr)
                if case == 'fetch-failed':
                    self.assertFalse(marker.exists(), 'failed history refresh must stop before PR lookup')
                else:
                    self.assertEqual(marker.read_text().splitlines(), ['read'])
                    self.assertEqual(git('rev-parse', 'HEAD', cwd=work).stdout, git('rev-parse', 'HEAD').stdout)
                    if case != 'equivalent':
                        self.assertIn('stale GitOps image pin:', result.stdout + result.stderr)

    def test_targeted_recovery_does_not_read_or_carry_unrelated_proposals(self):
        rewrite = next(step for step in self.steps if step.get('id') == 'rewrite')
        self.assertEqual(rewrite['env']['RECONCILE_TARGETS'], '${{ github.event.inputs.reconcile_services }}')
        script = rewrite['run']
        start = script.index('# An explicitly bounded recovery')
        end = script.index('if [ "${#CARRIED_PRS[@]}" -gt 0 ]; then', start)
        carry = script[start:end]
        for targets, token, expected_reads in (('openbank-pid-service', 'test', 0), ('', 'test', 1), ('', '', 0)):
            with self.subTest(targets=targets, token=bool(token)), tempfile.TemporaryDirectory() as d:
                directory = Path(d)
                marker = directory / 'reads'
                stub = directory / 'deploy-hold-venv/bin/python'
                stub.parent.mkdir(parents=True)
                stub.write_text('#!/bin/sh\nexit 0\n')
                stub.chmod(0o755)
                env = {**os.environ, 'RUNNER_TEMP': d, 'MARKER': str(marker),
                       'RECONCILE_TARGETS': targets, 'GH_TOKEN': token,
                       'IMAGE_TAGS': '{"openbank-pid-service":"sandbox-aaaaaaaa"}',
                       'ECR_REGISTRY': 'registry.invalid', 'GITHUB_REPOSITORY': 'example/repo'}
                prelude = 'set -euo pipefail\nCARRIED_PRS=()\nUPDATED=()\ngh() { echo read >> "$MARKER"; }\n'
                result = subprocess.run(['bash', '-c', prelude + carry], env=env, capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(len(marker.read_text().splitlines()) if marker.exists() else 0, expected_reads)

    def test_final_freshness_restores_history_but_still_rejects_changed_sources(self):
        verify = next(step['run'] for step in self.steps
                      if step.get('name') == 'Verify GitOps image source freshness')
        # These are real file:// Git fetches and the actual workflow/helper, with only
        # GitHub's PR-diff transport stubbed. No registry, token or remote repo is used.
        for change in ('docs', 'service', 'global', 'diverged', 'unreachable'):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as d:
                directory = Path(d)
                origin, checkout = directory / 'origin', directory / 'checkout'
                env = {key: value for key, value in os.environ.items() if not key.startswith('GIT_')}
                env.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM='1',
                           GIT_TERMINAL_PROMPT='0')

                def git(root, *args, check=True, env=env):
                    return subprocess.run(['git', '-c', 'commit.gpgSign=false',
                                           '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                                           *args], cwd=root, env=env, capture_output=True,
                                          text=True, check=check)

                def write(root, relative, text):
                    path = root / relative
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(text)

                def commit(origin=origin):
                    git(origin, 'add', 'openbank-example', 'docs', 'config', '.github/workflows/auto-deploy.yml')
                    git(origin, 'commit', '-qm', 'test fixture')
                    return git(origin, 'rev-parse', 'HEAD').stdout.strip()

                origin.mkdir()
                git(origin, 'init', '-q', '-b', 'main')
                write(origin, 'openbank-example/build.gradle.kts', 'plugins {}\n')
                write(origin, 'docs/fixture.md', 'first\n')
                write(origin, 'config/fixture.txt', 'first\n')
                write(origin, '.github/workflows/auto-deploy.yml',
                      'env:\n  ANALYTICS_SINK_TYPE: clickhouse\n  AGENT_SVID_NONCE_STORE: redis\n')
                source = commit()
                if change == 'service':
                    write(origin, 'openbank-example/build.gradle.kts', 'plugins {} // changed\n')
                elif change == 'global':
                    write(origin, 'config/fixture.txt', 'changed\n')
                elif change == 'diverged':
                    git(origin, 'checkout', '-qb', 'side')
                    write(origin, 'docs/fixture.md', 'side\n')
                    source = commit()
                    git(origin, 'checkout', '-q', 'main')
                write(origin, 'docs/fixture.md', 'later\n')
                head = commit()
                git(directory, 'clone', '-q', '--no-tags', '--depth=100', origin.as_uri(), str(checkout))
                if change == 'diverged':
                    git(checkout, 'fetch', '-q', 'origin', source)
                for name in ('deploy-window.py', 'pact-version-tree-equivalent.sh'):
                    destination = checkout / '.github/scripts' / name
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(ROOT / '.github/scripts' / name, destination)
                image = 'registry.invalid/openbank-example'
                diff = (f'--- a/manifests.yaml\n+++ b/manifests.yaml\n'
                        f'-image: {image}:sandbox-00000000\n+image: {image}:sandbox-{source[:8]}\n')

                def check_pin(checkout=checkout, env=env, diff=diff):
                    return subprocess.run(['python3', '.github/scripts/deploy-window.py', 'verify'],
                                          cwd=checkout, env=env, input=diff, capture_output=True, text=True,
                                          check=False)

                if change in ('docs', 'unreachable'):
                    self.assertEqual(check_pin().returncode, 0)  # carry's original proof
                # This is the history truncation performed by create-pull-request.
                git(checkout, 'fetch', '-q', '--depth=1', 'origin', 'main:refs/remotes/origin/main')
                self.assertEqual(git(checkout, 'rev-parse', '--is-shallow-repository').stdout.strip(), 'true')
                self.assertNotEqual(check_pin().returncode, 0)
                if change == 'unreachable':
                    git(checkout, 'remote', 'set-url', 'origin', (directory / 'missing').as_uri())
                diff_path = directory / 'pr.diff'
                diff_path.write_text(diff)
                env.update(PR_NUMBER='1', GITHUB_REPOSITORY='example/repo', PR_DIFF=str(diff_path))
                result = subprocess.run(['bash', '-c', 'gh() { cat "$PR_DIFF"; }\n' + verify],
                                        cwd=checkout, env=env, capture_output=True, text=True, check=False)
                self.assertEqual(result.returncode == 0, change == 'docs', result.stderr)
                self.assertEqual(git(checkout, 'rev-parse', 'HEAD').stdout.strip(), head)
                self.assertEqual(git(checkout, 'status', '--porcelain', '--untracked-files=no').stdout, '')


if __name__ == '__main__':
    unittest.main()
