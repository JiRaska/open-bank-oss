#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise producer branch identity and the actual workflow carry block."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

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
            return template.replace('${{ github.sha }}', 'a' * 40).replace('${{ github.run_id }}', str(run_id))
        self.assertNotEqual(branch(101), branch(102))
        self.assertNotIn('github.run_attempt', template)  # retry retains producer identity
        self.assertEqual(branch(101), 'chore/gitops-auto-deploy-' + 'a' * 40 + '-run101')
        self.assertTrue(branch(101).startswith('chore/gitops-auto-deploy-'))

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


if __name__ == '__main__':
    unittest.main()
