#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise the actual watchdog shell guard; never contact GitHub."""
import json
import os
from pathlib import Path
import subprocess
import textwrap
import unittest


class LiveRunGuardTest(unittest.TestCase):
    def test_live_run_guard(self):
        source = (Path(__file__).parents[1] / 'workflows/service-build-stall-watch.yml').read_text()
        guard = source.split('            live_run=$(gh api ', 1)[1].split('            if [ "$DRY_RUN"', 1)[0]
        guard = textwrap.dedent('            live_run=$(gh api ' + guard)
        eligible = {'id': 123, 'status': 'queued', 'head_sha': 'a' * 40, 'event': 'pull_request'}
        cases = [('queued', eligible, 0, True)]
        for status in ['in_progress', 'completed', 'pending', None]:
            cases.append((str(status), dict(eligible, status=status), 0, False))
        cases += [('wrong head', dict(eligible, head_sha='b' * 40), 0, False),
                  ('wrong id', dict(eligible, id=124), 0, False),
                  ('wrong event', dict(eligible, event='push'), 0, False),
                  ('missing fields', {}, 0, False),
                  ('malformed JSON', '{', 0, False),
                  ('API error', eligible, 1, False)]
        for name, response, api_exit, cancel in cases:
            with self.subTest(name=name):
                env = dict(os.environ, RESPONSE=response if isinstance(response, str) else json.dumps(response), API_EXIT=str(api_exit))
                script = 'set -euo pipefail\ngh() { printf "%s" "$RESPONSE"; return "$API_EXIT"; }\nREPO=example/repo\nrun_id=123\nstale_sha=' + 'a' * 40 + '\nfor candidate in one; do\n' + guard + '\nprintf "CANCEL_POST\\n"\ndone\n'
                result = subprocess.run(['bash', '-c', script], env=env, capture_output=True, text=True)
                self.assertEqual('CANCEL_POST' in result.stdout, cancel)
                if api_exit:
                    self.assertNotEqual(result.returncode, 0)
                else:
                    self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == '__main__':
    unittest.main()
