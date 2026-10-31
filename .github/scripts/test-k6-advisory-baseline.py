#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Hermetic workflow/runner contracts; Docker is a local fake, never a workload."""
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / '.github/workflows/perf-baseline.yml'
RUNNER = ROOT / '.github/scripts/run-k6-advisory-baseline.py'


class AdvisoryBaselineTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.fake = self.root / 'docker'
        self.fake.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
pathlib.Path('docker-args.json').write_text(json.dumps(args))
if '--no-thresholds-fail' in args:
    print('unknown flag: --no-thresholds-fail', file=sys.stderr)
    raise SystemExit(104)
summary = next((a.split('=', 1)[1] for a in args if a.startswith('--summary-export=')), None)
if summary is None and '--summary-export' in args:
    summary = args[args.index('--summary-export') + 1]
if os.environ.get('FAKE_SUMMARY') != 'ABSENT':
    pathlib.Path(summary).write_text(os.environ['FAKE_SUMMARY'])
raise SystemExit(int(os.environ.get('FAKE_EXIT', '0')))
''')
        self.fake.chmod(0o755)
        self.env = {**os.environ, 'PATH': str(self.root) + os.pathsep + os.environ['PATH'],
                    'BASE_URL': 'https://example.invalid', 'PERF_READ_TOKEN': 'synthetic-only',
                    'LEDGER_URL': 'https://example.invalid', 'TXN_URL': 'https://example.invalid',
                    'K6_IMAGE': 'synthetic-pinned-image', 'MODULE_DEF': 'fixture.js',
                    'GITHUB_OUTPUT': str(self.root / 'outputs'), 'FAKE_EXIT': '0',
                    'FAKE_SUMMARY': json.dumps({'metrics': {'checks': {'thresholds': {'rate==1': False}}}})}
        self.workflow = yaml.safe_load(WORKFLOW.read_text())

    def workflow_run(self, job='module-read-baselines'):
        step = next(x for x in self.workflow['jobs'][job]['steps'] if x.get('name') == ('Run k6 baseline' if job == 'k6' else 'Run k6 module read baseline'))
        command = step['run'].replace('${{ matrix.id }}', 'fixture').replace('${{ matrix.fixtures }}', '')
        # Execute the real checked-in shell step with a private fake Docker and real runner.
        command = command.replace('.github/scripts/run-k6-advisory-baseline.py', str(RUNNER))
        return subprocess.run(['bash', '-c', command], cwd=self.root, env=self.env, capture_output=True, text=True, check=False)

    def test_real_workflow_runs_supported_cli_for_both_lanes(self):
        for job in ['k6', 'module-read-baselines']:
            with self.subTest(job=job):
                result = self.workflow_run(job)
                self.assertEqual(result.returncode, 0, result.stderr)
                args = json.loads((self.root / 'docker-args.json').read_text())
                self.assertNotIn('--no-thresholds', args)
                self.assertNotIn('--no-thresholds-fail', args)

    def test_real_workflow_keeps_threshold_breach_advisory(self):
        self.env.update(FAKE_EXIT='99', FAKE_SUMMARY=json.dumps({'metrics': {'checks': {'thresholds': {'rate==1': True}}}}))
        result = self.workflow_run()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('::warning::', result.stdout)
        self.assertTrue(json.loads((self.root / 'fixture-summary.json').read_text())['metrics']['checks']['thresholds']['rate==1'])
        collected = subprocess.run(['python3', str(ROOT / '.github/scripts/collect-test-run-evidence.py'),
                                    '--service', str(self.root), '--component', 'openbank-account-service',
                                    '--performance-summary', str(self.root / 'fixture-summary.json'),
                                    '--out', str(self.root / 'run.json')], cwd=ROOT, env=self.env,
                                   capture_output=True, text=True, check=False)
        self.assertEqual(collected.returncode, 0, collected.stderr)
        rows = json.loads((self.root / 'run.json').read_text())['specializedEvidence']
        self.assertEqual(next(row['state'] for row in rows if row['kind'] == 'performance'), 'failed')

    def run_helper(self, *args):
        return subprocess.run(['python3', str(RUNNER), '--definition', 'fixture.js', '--summary', 'summary.json', *args], cwd=self.root, env=self.env, capture_output=True, text=True, check=False)

    def test_execution_errors_never_become_advisory_success(self):
        for code, summary in [('1', self.env['FAKE_SUMMARY']), ('99', self.env['FAKE_SUMMARY']),
                              ('0', 'ABSENT'), ('99', 'ABSENT'), ('0', '{invalid'),
                              ('0', '{}'), ('0', '{"metrics":{"checks":{"thresholds":{"rate==1":"false"}}}}')]:
            with self.subTest(code=code, summary=summary):
                self.env.update(FAKE_EXIT=code, FAKE_SUMMARY=summary)
                # A previous valid file must not make an absent current summary pass.
                (self.root / 'summary.json').write_text('{"metrics":{"checks":{"thresholds":{"rate==1":false}}}}')
                self.assertNotEqual(self.run_helper().returncode, 0)

    def test_required_configuration_skips_without_starting_docker(self):
        for missing in ['BASE_URL', 'PERF_READ_TOKEN', 'PERF_PARTY_ID', 'PERF_ACCOUNT_ID']:
            with self.subTest(missing=missing):
                self.env.update(PERF_PARTY_ID='fixture-party', PERF_ACCOUNT_ID='fixture-account')
                saved = self.env.pop(missing, None)
                result = self.run_helper('--check-config', '--required-fixtures', 'PERF_PARTY_ID PERF_ACCOUNT_ID')
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn('run=false', (self.root / 'outputs').read_text())
                self.assertIn('reason=', (self.root / 'outputs').read_text())
                self.assertFalse((self.root / 'docker-args.json').exists())
                if saved is not None:
                    self.env[missing] = saved

    def test_missing_fixture_produces_not_run_envelope_not_pass(self):
        result = self.run_helper('--check-config', '--required-fixtures', 'PERF_ACCOUNT_ID')
        self.assertEqual(result.returncode, 0, result.stderr)
        reason = next(line.removeprefix('reason=') for line in (self.root / 'outputs').read_text().splitlines() if line.startswith('reason='))
        result = subprocess.run(['python3', str(ROOT / '.github/scripts/collect-test-run-evidence.py'),
                                 '--service', str(self.root), '--component', 'openbank-account-service',
                                 '--performance-summary', str(self.root / 'summary.json'),
                                 '--performance-not-run-detail', reason, '--out', str(self.root / 'run.json')],
                                cwd=ROOT, env=self.env, capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        rows = json.loads((self.root / 'run.json').read_text())['specializedEvidence']
        self.assertEqual(next(row['state'] for row in rows if row['kind'] == 'performance'), 'not-run')

    def test_configured_fixture_names_reach_docker_without_printing_values(self):
        self.env.update(PERF_PARTY_ID='fixture-party', PERF_ACCOUNT_ID='fixture-account')
        result = self.run_helper('--required-fixtures', 'PERF_PARTY_ID PERF_ACCOUNT_ID', '--pass-env', 'PERF_CURRENCY')
        self.assertEqual(result.returncode, 0, result.stderr)
        args = json.loads((self.root / 'docker-args.json').read_text())
        names = [args[i + 1] for i, value in enumerate(args[:-1]) if value == '--env']
        self.assertTrue({'BASE_URL', 'PERF_READ_TOKEN', 'PERF_PARTY_ID', 'PERF_ACCOUNT_ID', 'PERF_CURRENCY'} <= set(names))
        for value in ['fixture-party', 'fixture-account', 'synthetic-only']:
            self.assertNotIn(value, result.stdout + result.stderr)

    def test_matrix_declares_all_required_seeded_fixture_names(self):
        expected = {'account': 'PERF_PARTY_ID PERF_ACCOUNT_ID', 'balance': 'PERF_ACCOUNT_ID',
                    'consent': 'PERF_PARTY_ID PERF_GRANTEE_ID', 'sca': 'PERF_PARTY_ID PERF_SCA_CHALLENGE_ID',
                    'sepa-instant': 'PERF_ACCOUNT_ID', 'transaction': 'PERF_ACCOUNT_ID'}
        job = self.workflow['jobs']['module-read-baselines']
        actual = {x['id']: x.get('fixtures', '') for x in job['strategy']['matrix']['include']}
        for key, fixtures in expected.items():
            self.assertEqual(actual[key], fixtures)
            for name in fixtures.split():
                self.assertIn(name, job['env'])


if __name__ == '__main__':
    unittest.main()
