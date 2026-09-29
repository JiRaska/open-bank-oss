#!/usr/bin/env python3
"""Exercise the actual required Admin UI aggregate script with failing selections."""

import os
from pathlib import Path
import subprocess
import unittest


WORKFLOW = Path(__file__).resolve().parents[2] / 'workflows/ci.yml'


def aggregate_script():
    lines = WORKFLOW.read_text().splitlines()
    start = lines.index('  ui:')
    step = lines.index('      - name: Check build result', start)
    run = lines.index('        run: |', step) + 1
    body = []
    for line in lines[run:]:
        if line and not line.startswith('          '):
            break
        body.append(line[10:] if line else '')
    if not body or 'set -euo pipefail' not in body:
        raise ValueError('Admin UI aggregate shell script not found')
    return '\n'.join(body) + '\n'


class AdminUiAggregateTest(unittest.TestCase):
    def test_required_context_fails_closed(self):
        script = aggregate_script()
        base = {
            'DETECTION_RESULT': 'success',
            'UI_CHANGED': 'false',
            'RESULT': 'skipped',
            'RBAC_TESTS': '',
            'REGISTRY_TEST': 'false',
            'RBAC_RESULT': 'skipped',
            'EVENT': 'pull_request',
            'REF': 'refs/pull/1/merge',
        }
        cases = [
            ('no UI inputs', {}, True),
            ('UI build passed', {'UI_CHANGED': 'true', 'RESULT': 'success', 'REGISTRY_TEST': ''}, True),
            ('registry guard passed', {'REGISTRY_TEST': 'true', 'RBAC_RESULT': 'success'}, True),
            ('RBAC guard passed', {'RBAC_TESTS': 'treasury-rbac.guard.test.ts', 'RBAC_RESULT': 'success'}, True),
            ('detection failed', {'DETECTION_RESULT': 'failure'}, False),
            ('selection missing', {'UI_CHANGED': ''}, False),
            ('registry selection missing', {'REGISTRY_TEST': ''}, False),
            ('changed UI build skipped', {'UI_CHANGED': 'true', 'RESULT': 'skipped'}, False),
            ('unchanged UI build ran', {'RESULT': 'success'}, False),
            ('UI build failed', {'UI_CHANGED': 'true', 'RESULT': 'failure'}, False),
            ('selected guard skipped', {'REGISTRY_TEST': 'true'}, False),
            ('selected guard cancelled', {'REGISTRY_TEST': 'true', 'RBAC_RESULT': 'cancelled'}, False),
            ('PR build cancelled', {'UI_CHANGED': 'true', 'RESULT': 'cancelled'}, False),
            ('main build cancelled', {'UI_CHANGED': 'true', 'RESULT': 'cancelled', 'EVENT': 'push'}, True),
        ]
        for name, overrides, allowed in cases:
            with self.subTest(name=name):
                result = subprocess.run(
                    ['bash'], input=script, text=True, capture_output=True,
                    env={**os.environ, **base, **overrides}, check=False,
                )
                self.assertEqual(result.returncode == 0, allowed, result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
