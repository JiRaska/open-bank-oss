#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Exercise the deployment shell against real ZIPs and the real envelope validator."""
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import textwrap
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = (ROOT / '.github/workflows/admin-ui-deploy.yml').read_text()
START = WORKFLOW.index('          STAGING_TMP=')
END = WORKFLOW.index('\n      - name: Stage customer-app', START)
SHELL = textwrap.dedent(WORKFLOW[START:END])

# Each invocation records its lifetime under an interprocess lock. Unlike a timing
# speed assertion, this detects both excess concurrency and an early return.
FAKE_API = '''import fcntl,json,os,pathlib,shutil,sys,time
root=pathlib.Path(os.environ['FIXTURE_ROOT'])
identity=sys.argv[1].split('/')[-2]
def record(kind):
 with (root/'events').open('a') as out:
  fcntl.flock(out,fcntl.LOCK_EX)
  out.write(json.dumps([kind,identity])+'\\n');out.flush()
record('start')
time.sleep(0.12 if identity!='1' else 0.02)
try:
 if os.environ.get('FAIL_DOWNLOAD')==identity: sys.exit(1)
 shutil.copyfile(root/(identity+'.zip'),sys.argv[sys.argv.index('-o')+1])
finally: record('end')
'''


class StagingPoolTests(unittest.TestCase):
    def stage(self, base, workers=4, failure=None):
        root = base / f'work-{workers}-{failure or "ok"}'
        root.mkdir()
        (root / '.github').mkdir()
        (root / '.github/scripts').symlink_to(ROOT / '.github/scripts', target_is_directory=True)
        fixtures = root / 'fixtures'
        fixtures.mkdir()
        (fixtures / 'api.py').write_text(FAKE_API)
        services = [f'openbank-pool-fixture-{i}' for i in range(1, 9)]
        for i, service in enumerate(services, 1):
            envelope = {
                'schemaVersion': 1, 'component': service, 'suites': [], 'coverage': None,
                'testInfrastructure': {'declared': [], 'observed': []},
                'run': {'id': str(i), 'attempt': 1, 'commit': 'abcdef012345',
                        'branch': 'main', 'workflow': 'Services CI', 'url': '',
                        'observedAt': '2026-10-22T12:00:00Z'},
            }
            if failure == 'component' and i == 1:
                envelope['component'] = 'openbank-other'
            archive = fixtures / f'{i}.zip'
            if failure == 'zip' and i == 1:
                archive.write_bytes(b'not a ZIP')
                continue
            with zipfile.ZipFile(archive, 'w') as zipped:
                prefix = '' if i % 2 else 'legacy/build/'
                zipped.writestr(prefix + ('run.json' if i % 2 else 'test-intelligence/run.json'), json.dumps(envelope))
                zipped.writestr(prefix + 'test-results/test/TEST-example.xml', f'<testsuite name="{service}"/>')
                zipped.writestr(prefix + 'reports/kover/report.xml', f'<report name="{service}"/>')
        # Include an explicit no-artifact verdict: it must remain a skip.
        services.append('openbank-pool-no-artifact')
        prelude = 'set -euo pipefail\nREPO=fixture/repo\n'
        prelude += 'SERVICES=(' + ' '.join(map(shlex.quote, services)) + ')\n'
        prelude += 'ARTIFACT_IDS=(1 2 3 4 5 6 7 8 "")\n'
        prelude += 'api() { python3 "$FIXTURE_ROOT/api.py" "$@"; }\n'
        script = prelude + SHELL.replace('"${#PIDS[@]}" -eq 4', f'"${{#PIDS[@]}}" -eq {workers}')
        result = subprocess.run(['bash', '-c', script], cwd=root, env=os.environ | {
            'FIXTURE_ROOT': str(fixtures), 'FAIL_DOWNLOAD': '1' if failure == 'download' else '',
        }, text=True, capture_output=True, timeout=30)
        events = [json.loads(line) for line in (fixtures / 'events').read_text().splitlines()]
        active = maximum = 0
        for kind, _ in events:
            active += 1 if kind == 'start' else -1
            maximum = max(maximum, active)
        self.assertEqual(active, 0, 'returned before all downloads drained')
        self.assertEqual(sorted(identity for kind, identity in events if kind == 'end'), list('12345678'))
        outputs = {str(p.relative_to(root)): p.read_bytes() for service in services
                   for p in (root / service).rglob('*') if p.is_file()}
        return result, outputs, maximum

    def test_serial_parallel_file_equivalence_and_bound(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            serial, expected, serial_max = self.stage(base, workers=1)
            parallel, actual, parallel_max = self.stage(base)
            self.assertEqual(serial.returncode, 0, serial.stderr)
            self.assertEqual(parallel.returncode, 0, parallel.stderr)
            self.assertEqual(actual, expected)
            self.assertEqual(len(actual), 24)  # envelope + JUnit + Kover, eight services
            self.assertEqual(serial_max, 1)
            self.assertGreater(parallel_max, 1)
            self.assertLessEqual(parallel_max, 4)
            self.assertIn('8/9 services', parallel.stdout)

    def test_download_archive_and_identity_failure_drain_and_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            for failure in ('download', 'zip', 'component'):
                with self.subTest(failure=failure):
                    result, outputs, maximum = self.stage(Path(directory), failure=failure)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertLessEqual(maximum, 4)
                    self.assertIn('refusing incomplete deployment snapshot', result.stdout)
                    self.assertEqual(len(outputs), 21)  # all seven valid workers finish
                    self.assertNotIn('8/9 services', result.stdout)


if __name__ == '__main__':
    unittest.main()
