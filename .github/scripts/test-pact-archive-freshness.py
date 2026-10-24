#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise the actual workflow restore/coverage blocks with synthetic archives."""
import io
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import time
import unittest

import yaml

WORKFLOW = Path(__file__).resolve().parents[1] / 'workflows/pact-drift-check.yml'
JVM = 'pacts/openbank-fixture-openbank-provider.json'
JS = 'pacts/openbank-ui-openbank-provider.json'


class ArchiveFreshnessTest(unittest.TestCase):
    def test_publication_follows_freshness_proofs_without_cross_runner_marker(self):
        workflow = yaml.safe_load(WORKFLOW.read_text())['jobs']['drift-check']['steps']
        names = [step.get('name') for step in workflow]
        archive = names.index('Verify shard coverage and restore regenerated JVM contracts')
        freshness = names.index('Verify every committed pact was actually regenerated')
        prepare = names.index('Prepare verified admin UI contracts')
        self.assertLess(archive, freshness)
        self.assertLess(freshness, prepare)
        self.assertIn('SCOPE_MODE', workflow[archive].get('env', {}))
        self.assertIn('all-jvm-pacts.txt', workflow[freshness].get('run', ''))
        self.assertIn('--marker', workflow[prepare].get('run', ''))
        self.assertIn('--jvm-pacts-manifest', workflow[prepare].get('run', ''))
        self.assertIn('verified-jvm-pacts.txt', workflow[prepare].get('run', ''))
        self.assertIn('cp -- "${RUNNER_TEMP}/expected-jvm-pacts.txt"', workflow[freshness].get('run', ''))

    def test_ui_scope_is_only_selected_after_classifier_success(self):
        jobs = yaml.safe_load(WORKFLOW.read_text())['jobs']
        self.assertIn('classify-pact-drift-scope.py', WORKFLOW.read_text())
        classifier = jobs['pact-scope']['steps'][-1]['run']
        self.assertIn('git show "$BASE_SHA:.github/scripts/classify-pact-drift-scope.py"', classifier)
        self.assertIn('PUSH_BASE_SHA', jobs['pact-scope']['steps'][-1].get('env', {}))
        self.assertIn('elif [ "$EVENT_NAME" = push ]', classifier)
        self.assertEqual(jobs['pact-scope']['outputs']['shards'], '${{ steps.scope.outputs.shards }}')
        shard_matrix = jobs['gradle-pacts']['strategy']['matrix']['shard']
        self.assertIn('fromJSON(', shard_matrix)
        self.assertIn("needs.pact-scope.result == 'success'", shard_matrix)
        self.assertIn("needs.pact-scope.outputs.mode == 'ui-only'", shard_matrix)
        self.assertIn("outputs.shards == '[\"ui\"]'", shard_matrix)
        self.assertIn('shards=["0", "1", "2", "3"]', classifier)
        self.assertNotIn('gradle-pacts-ui', jobs)
        self.assertEqual(jobs['drift-check']['needs'], ['pact-scope', 'gradle-pacts'])

    def test_jvm_archive_writer_upload_and_reader_paths_match_both_modes(self):
        jobs = yaml.safe_load(WORKFLOW.read_text())['jobs']
        gradle_steps = jobs['gradle-pacts']['steps']
        writer = next(step for step in gradle_steps
                      if step.get('name') == 'Regenerate this shard and archive only rewritten pacts')
        upload = next(step for step in gradle_steps if 'upload-artifact' in step.get('uses', ''))
        self.assertIn('jvm-pacts-${SHARD}.tar', writer['run'])
        self.assertEqual(upload['with']['path'], '${{ runner.temp }}/jvm-pacts-${{ matrix.shard }}.tar')
        self.assertEqual(upload['with']['name'], 'jvm-pacts-${{ matrix.shard }}')
        reader = next(step for step in jobs['drift-check']['steps']
                      if step.get('name') == 'Verify shard coverage and restore regenerated JVM contracts')
        self.assertIn('jvm-pacts/jvm-pacts-ui.tar', reader['run'])
        self.assertIn('jvm-pacts/jvm-pacts-${shard}.tar', reader['run'])
        self.assertIn('for shard in 0 1 2 3', reader['run'])

    def test_workflow_runs_this_test_for_relevant_changes(self):
        workflow = yaml.safe_load(WORKFLOW.read_text())
        triggers = workflow.get('on', workflow.get(True))
        self.assertIsNotNone(triggers)
        expected = '.github/scripts/test-pact-archive-freshness.py'
        for path in (expected, '.github/scripts/classify-pact-drift-scope.py',
                     '.github/scripts/test-classify-pact-drift-scope.py',
                     '.github/scripts/test-admin-ui-pact-scope.py'):
            self.assertIn(path, triggers['pull_request']['paths'])
            self.assertIn(path, triggers['push']['paths'])

    def fixture(self, root, member=True, missing_archive=None):
        tmp = root / 'runner'
        tmp.mkdir()
        (root / 'pacts').mkdir()
        (root / JVM).write_text('stale checkout')
        (tmp / 'jvm-pacts').mkdir()
        (tmp / 'expected-jvm-pacts.txt').write_text(JVM + '\n')
        (tmp / 'pact-uncovered.txt').write_text('')
        for shard in range(4):
            if shard == missing_archive:
                continue
            with tarfile.open(tmp / f'jvm-pacts/jvm-pacts-{shard}.tar', 'w') as archive:
                if shard == 0 and member:
                    data = b'fresh generated contract'
                    info = tarfile.TarInfo(JVM)
                    info.size = len(data)
                    info.mtime = 1  # Archive timestamps are deliberately older than the marker.
                    archive.addfile(info, io.BytesIO(data))
        return tmp

    def run_step(self, name, root, tmp, prefix='', step_env=None):
        jobs = yaml.safe_load(WORKFLOW.read_text())['jobs']
        steps = [s for job in jobs.values() for s in job.get('steps', []) if s.get('name') == name]
        self.assertEqual(len(steps), 1)
        env = dict(os.environ, RUNNER_TEMP=str(tmp), SCOPE_MODE='full', SCOPE_RESULT='success')
        env.update(step_env or {})
        return subprocess.run(
            ['bash', '-c', prefix + '\n' + steps[0]['run']], cwd=root,
            env=env, capture_output=True, text=True, check=False,
        )

    def test_failed_dependency_still_runs_guard_step(self):
        jobs = yaml.safe_load(WORKFLOW.read_text())['jobs']
        job = jobs['drift-check']
        self.assertEqual(job.get('needs'), ['pact-scope', 'gradle-pacts'])
        # This is a static policy assertion, not a simulation of GitHub's cancellation evaluator.
        self.assertEqual(job.get('if'), '${{ !cancelled() }}')
        steps = job.get('steps', [])
        self.assertEqual(steps[0].get('name'), 'Require the selected JVM Pact scope to succeed')
        self.assertEqual(steps[0].get('env', {}).get('FULL_RESULT'), '${{ needs.gradle-pacts.result }}')
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            tmp = root / 'runner'
            tmp.mkdir()
            r = self.run_step('Require the selected JVM Pact scope to succeed', root, tmp,
                              step_env={'SCOPE_RESULT': 'success', 'SCOPE_MODE': 'ui-only',
                                        'FULL_RESULT': 'failure'})
            self.assertNotEqual(r.returncode, 0)
            self.assertIn('Admin UI JVM Pact producers did not complete: failure', r.stdout)
            r = self.run_step('Require the selected JVM Pact scope to succeed', root, tmp,
                              step_env={'SCOPE_RESULT': 'failure', 'SCOPE_MODE': 'ui-only',
                                        'FULL_RESULT': 'success'})
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def ui_fixture(self, root, extra_member=False):
        tmp = root / 'runner'
        tmp.mkdir()
        (root / 'pacts').mkdir()
        selected = 'pacts/openbank-admin-ui-openbank-provider.json'
        unrelated = 'pacts/openbank-other-openbank-provider.json'
        js = 'pacts/openbank-ui-openbank-provider.json'
        (root / selected).write_text('old selected')
        (root / unrelated).write_text('untouched unrelated JVM')
        (root / js).write_text('old PactJS')
        (tmp / 'jvm-pacts').mkdir()
        (tmp / 'expected-jvm-pacts.txt').write_text(selected + '\n')
        (tmp / 'all-jvm-pacts.txt').write_text(selected + '\n' + unrelated + '\n')
        (tmp / 'pact-uncovered.txt').write_text('')
        with tarfile.open(tmp / 'jvm-pacts/jvm-pacts-ui.tar', 'w') as archive:
            for name, content in [(selected, b'fresh selected')]:
                info = tarfile.TarInfo(name)
                info.size = len(content)
                info.mtime = 1
                archive.addfile(info, io.BytesIO(content))
            if extra_member:
                content = b'unselected output'
                info = tarfile.TarInfo(unrelated)
                info.size = len(content)
                info.mtime = 1
                archive.addfile(info, io.BytesIO(content))
        return tmp, selected, unrelated, js

    def test_ui_archive_must_cover_exact_selected_jvm_set_and_preserve_full_receipt(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            tmp, selected, unrelated, js = self.ui_fixture(root)
            r = self.run_step('Verify shard coverage and restore regenerated JVM contracts', root, tmp,
                              step_env={'SCOPE_MODE': 'ui-only', 'SCOPE_RESULT': 'success'})
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertEqual((root / selected).read_text(), 'fresh selected')
            self.assertEqual((root / unrelated).read_text(), 'untouched unrelated JVM')
            os.utime(root / js, ns=(time.time_ns() + 1_000_000_000,) * 2)
            r = self.run_step('Verify every committed pact was actually regenerated', root, tmp,
                              step_env={'SCOPE_MODE': 'ui-only', 'SCOPE_RESULT': 'success'})
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertEqual((tmp / 'verified-jvm-pacts.txt').read_text(), selected + '\n')

    def test_ui_archive_rejects_unselected_jvm_output(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            tmp, _, _, _ = self.ui_fixture(root, extra_member=True)
            r = self.run_step('Verify shard coverage and restore regenerated JVM contracts', root, tmp,
                              step_env={'SCOPE_MODE': 'ui-only', 'SCOPE_RESULT': 'success'})
            self.assertNotEqual(r.returncode, 0)
            self.assertIn('did not regenerate exactly', r.stdout)

    def test_ui_mode_still_rejects_stale_pactjs(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            tmp, _, _, js = self.ui_fixture(root)
            r = self.run_step('Verify shard coverage and restore regenerated JVM contracts', root, tmp,
                              step_env={'SCOPE_MODE': 'ui-only', 'SCOPE_RESULT': 'success'})
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = self.run_step('Verify every committed pact was actually regenerated', root, tmp,
                              step_env={'SCOPE_MODE': 'ui-only', 'SCOPE_RESULT': 'success'})
            self.assertNotEqual(r.returncode, 0)
            self.assertIn(js, r.stdout)
            self.assertFalse((tmp / 'verified-jvm-pacts.txt').exists())

    def test_missing_shard_archive_fails_coverage_step(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            tmp = self.fixture(root, missing_archive=2)
            r = self.run_step('Verify shard coverage and restore regenerated JVM contracts', root, tmp)
            self.assertNotEqual(r.returncode, 0)
            self.assertIn('Missing regenerated JVM Pact archive:', r.stdout)
            self.assertIn('jvm-pacts-2.tar', r.stdout)

    def test_equal_timestamp_restored_jvm_is_accepted(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            tmp = self.fixture(root)
            r = self.run_step('Verify shard coverage and restore regenerated JVM contracts', root, tmp)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            marker = (tmp / 'pact-regen-marker').stat()
            os.utime(root / JVM, ns=(marker.st_atime_ns, marker.st_mtime_ns))
            self.assertEqual((root / JVM).read_text(), 'fresh generated contract')
            r = self.run_step('Verify every committed pact was actually regenerated', root, tmp)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertEqual((tmp / 'verified-jvm-pacts.txt').read_text(), JVM + '\n')

    def test_missing_archive_member_cannot_reuse_stale_checkout(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            tmp = self.fixture(root, member=False)
            r = self.run_step('Verify shard coverage and restore regenerated JVM contracts', root, tmp)
            self.assertNotEqual(r.returncode, 0)
            self.assertIn('did not regenerate exactly', r.stdout)

    def test_extractor_noop_cannot_reuse_stale_checkout(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            tmp = self.fixture(root)
            # Listing succeeds, but extraction silently writes nothing: old destinations must not count.
            r = self.run_step('Verify shard coverage and restore regenerated JVM contracts', root, tmp,
                              'tar() { if [[ "$1" == -tf ]]; then command tar "$@"; fi; }')
            self.assertNotEqual(r.returncode, 0)

    def test_stale_pactjs_still_fails(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            tmp = self.fixture(root)
            r = self.run_step('Verify shard coverage and restore regenerated JVM contracts', root, tmp)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            (root / JS).write_text('not generated')
            os.utime(root / JS, (1, 1))
            r = self.run_step('Verify every committed pact was actually regenerated', root, tmp)
            self.assertNotEqual(r.returncode, 0)
            self.assertIn(JS, r.stdout)
            self.assertFalse((tmp / 'verified-jvm-pacts.txt').exists())


if __name__ == '__main__':
    unittest.main()
