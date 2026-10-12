#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run the workflow download/parser shell with a local artifact stand-in."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).parents[2]


class ArtifactTest(unittest.TestCase):
    def test_prior_attempt_artifacts(self):
        workflow = (ROOT / '.github/workflows/auto-retry-cancelled.yml').read_text()
        producer = (ROOT / '.github/workflows/_service-ci.yml').read_text()
        block = workflow.split('              artifact_dir=', 1)[1].split('\n            fi', 1)[0]
        block = textwrap.dedent('              artifact_dir=' + block)
        for lane, prefix in [('build', 'quality-evidence'), ('contract', 'contract-junit-xml')]:
            self.assertIn('name: ' + prefix + '-${{ inputs.service }}-a${{ github.run_attempt }}', producer)
            for missing in [False, True]:
                with self.subTest(lane=lane, missing=missing), tempfile.TemporaryDirectory() as directory:
                    env = dict(os.environ, RUNNER_TEMP=directory, RUN_ID='123', PREV_ATTEMPT='2', RUN_URL='run', i='1', service='openbank-example', record=json.dumps({'job': f'build (openbank-example) / openbank-example ({lane})'}), EXPECTED=f'{prefix}-openbank-example-a2', MISSING=str(int(missing)))
                    script = '''set -euo pipefail
                    gh() {
                      [ "$1 $2 $3 $4 $5 $6" = "run download 123 -n $EXPECTED -D" ] || return 9
                      [ "$MISSING" = 0 ] || return 1
                      mkdir -p "$7/test-results/test" "$7/reports/kover"
                      printf '%s' '<testsuite><testcase classname="Example" name="regression"><failure message="fixture failure"/></testcase><testcase name="green"/></testsuite>' > "$7/test-results/test/result.xml"
                      printf '%s' '<report><package name="example"/></report>' > "$7/reports/kover/report.xml"
                    }
                    tests_file="$RUNNER_TEMP/tests.json"
                    printf '[]' > "$tests_file"
                    ''' + block
                    result = subprocess.run(['bash', '-c', textwrap.dedent(script)], env=env, cwd=ROOT, capture_output=True, text=True)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    tests = json.loads((Path(directory) / 'tests.json').read_text())
                    self.assertEqual(len(tests), 0 if missing else 1)
                    if tests:
                        self.assertEqual((tests[0]['classname'], tests[0]['name']), ('Example', 'regression'))


if __name__ == '__main__':
    unittest.main()
