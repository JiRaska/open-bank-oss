#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise the actual workflow restore/coverage blocks with synthetic archives."""
import io
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest

import yaml

WORKFLOW = Path(__file__).resolve().parents[1] / 'workflows/pact-drift-check.yml'
JVM = 'pacts/openbank-fixture-openbank-provider.json'
JS = 'pacts/openbank-ui-openbank-provider.json'


class ArchiveFreshnessTest(unittest.TestCase):
    def test_workflow_runs_this_test_for_relevant_changes(self):
        workflow = yaml.safe_load(WORKFLOW.read_text())
        triggers = workflow.get('on', workflow.get(True))
        self.assertIsNotNone(triggers)
        expected = '.github/scripts/test-pact-archive-freshness.py'
        self.assertIn(expected, triggers['pull_request']['paths'])
        self.assertIn(expected, triggers['push']['paths'])

    def test_failed_dependency_still_runs_guard_step(self):
        jobs = yaml.safe_load(WORKFLOW.read_text())['jobs']
        job = jobs['drift-check']
        self.assertEqual(job.get('needs'), ['regeneration-scope', 'gradle-pacts'])
        # This is a static policy assertion, not a simulation of GitHub's cancellation evaluator.
        self.assertEqual(job.get('if'), '${{ !cancelled() }}')
        steps = job.get('steps', [])
        self.assertEqual(steps[0].get('name'), 'Require every JVM shard to succeed')
        self.assertEqual(steps[0].get('env', {}).get('SHARD_RESULT'), '${{ needs.gradle-pacts.result }}')
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            tmp = root / 'runner'
            tmp.mkdir()
            r = self.run_step('Require every JVM shard to succeed', root, tmp,
                              step_env={'SCOPE_RESULT': 'success', 'SHARD_RESULT': 'failure'})
            self.assertNotEqual(r.returncode, 0)
            self.assertIn('Unexpected JVM scope/shard result: true:failure', r.stdout)

    def test_missing_shard_archive_fails_coverage_step(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            tmp = self.fixture(root, missing_archive=2)
            r = self.run_step('Verify shard coverage and restore regenerated JVM contracts', root, tmp)
            self.assertNotEqual(r.returncode, 0)
            self.assertIn('Missing regenerated Pact artifact for shard 2', r.stdout)

    def fixture(self, root, member=True, include_js=False, missing_archive=None):
        tmp = root / 'runner'
        tmp.mkdir()
        (root / 'pacts').mkdir()
        (root / JVM).write_text('stale checkout')
        (tmp / 'jvm-pacts').mkdir()
        (tmp / 'expected-jvm-pacts.txt').write_text(JVM + '\n')
        (tmp / 'pact-uncovered.txt').write_text('')
        (tmp / 'expected-js-pacts.txt').write_text(JS + '\n' if include_js else '')
        marker = tmp / 'pact-regen-marker'
        marker.touch()
        os.utime(marker, (100, 100))
        if include_js:
            (root / JS).write_text('stale PactJS output')
            os.utime(root / JS, (1, 1))
        for shard in range(4):
            if shard == missing_archive:
                continue
            with tarfile.open(tmp / f'jvm-pacts/shard-{shard}.tar', 'w') as archive:
                if shard == 0 and member:
                    data = b'fresh generated contract'
                    info = tarfile.TarInfo(JVM)
                    info.size = len(data)
                    info.mtime = 1  # Archive timestamps are deliberately older than the marker.
                    archive.addfile(info, io.BytesIO(data))
        return tmp

    def run_step(self, name, root, tmp, prefix='', jvm_required='true', step_env=None):
        jobs = yaml.safe_load(WORKFLOW.read_text())['jobs']
        steps = [s for job in jobs.values() for s in job.get('steps', []) if s.get('name') == name]
        self.assertEqual(len(steps), 1)
        env = dict(os.environ, RUNNER_TEMP=str(tmp), JVM_REQUIRED=jvm_required)
        env.update(step_env or {})
        return subprocess.run(
            ['bash', '-c', prefix + '\n' + steps[0]['run']], cwd=root,
            env=env,
            capture_output=True, text=True, check=False,
        )

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
            tmp = self.fixture(root, include_js=True)
            r = self.run_step('Verify shard coverage and restore regenerated JVM contracts', root, tmp)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            (root / JS).write_text('not generated')
            os.utime(root / JS, (1, 1))
            r = self.run_step('Verify every committed pact was actually regenerated', root, tmp)
            self.assertNotEqual(r.returncode, 0)
            self.assertIn(JS, r.stdout)

    def test_js_only_scope_keeps_js_freshness_and_ignores_jvm_output(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            tmp = self.fixture(root, include_js=True)
            (tmp / 'expected-jvm-pacts.txt').unlink()
            stale = self.run_step('Verify every committed pact was actually regenerated', root, tmp,
                                  jvm_required='false')
            self.assertNotEqual(stale.returncode, 0)
            self.assertIn(JS, stale.stdout)

            marker_time = (tmp / 'pact-regen-marker').stat().st_mtime
            os.utime(root / JS, (marker_time + 10, marker_time + 10))
            fresh = self.run_step('Verify every committed pact was actually regenerated', root, tmp,
                                  jvm_required='false')
            self.assertEqual(fresh.returncode, 0, fresh.stdout + fresh.stderr)


if __name__ == '__main__':
    unittest.main()
