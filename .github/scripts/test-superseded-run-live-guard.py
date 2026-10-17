#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise the actual watchdog shell guard; never contact GitHub."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


class LiveRunGuardTest(unittest.TestCase):
    def run_cancel_step(self, *, cancel_exit=0, error='', status_after='queued',
                        dry_run=False, pr_is_current=False):
        source = (Path(__file__).parents[1] / 'workflows/service-build-stall-watch.yml').read_text()
        step = next(part for part in source.split('      - name: ')
                    if part.splitlines()[0].endswith('only superseded queued PR runs'))
        shell = textwrap.dedent(step.split('        run: |\n', 1)[1])
        stale = 'a' * 40
        run = {'id': 123, 'status': 'queued', 'head_sha': stale, 'event': 'pull_request',
               'pull_requests': [{'number': 7}]}
        with tempfile.TemporaryDirectory(prefix='superseded-run-test-') as directory:
            calls = Path(directory) / 'calls'
            env = dict(os.environ, CALLS=str(calls), RUNS=json.dumps([{'workflow_runs': [run]}]),
                       PULLS=json.dumps([[{'number': 7, 'head': {'sha': 'b' * 40}}]]),
                       LIVE_PR=json.dumps({'state': 'open', 'head': {'sha': stale if pr_is_current else 'b' * 40}}),
                       LIVE_RUN=json.dumps(run), CANCEL_EXIT=str(cancel_exit), CANCEL_ERROR=error,
                       STATUS_AFTER=status_after, DRY_RUN=str(dry_run).lower(), REPO='example/repo')
            mock = r'''
gh() {
  printf '%s\n' "$*" >> "$CALLS"
  case "$*" in
    *status=queued*) printf '%s' "$RUNS" ;;
    *pulls?state=open*) printf '%s' "$PULLS" ;;
    *pulls/7*) printf '%s' "$LIVE_PR" ;;
    *--method\ POST*) printf '%s' "$CANCEL_ERROR" >&2; return "$CANCEL_EXIT" ;;
    *actions/runs/123*--jq*) printf '%s' "$STATUS_AFTER" ;;
    *actions/runs/123*) printf '%s' "$LIVE_RUN" ;;
    *) printf 'unexpected mocked request\n' >&2; return 99 ;;
  esac
}
'''
            result = subprocess.run(['bash', '-c', mock + shell.replace('/tmp/', directory + '/')],
                                    cwd=Path(__file__).parents[2], env=env, capture_output=True, text=True)
            posts = [line for line in calls.read_text().splitlines() if '--method POST' in line]
        return result, posts

    def test_queued_cancellation_requests_standard_endpoint(self):
        result, posts = self.run_cancel_step()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(posts, ['api --method POST repos/example/repo/actions/runs/123/cancel'])
        self.assertIn('requested cancellation of superseded queued run 123', result.stdout)
        self.assertNotIn('force-cancelled', result.stdout)

    def test_cancellation_errors_remain_visible(self):
        for code in [403, 409]:
            with self.subTest(code=code):
                result, posts = self.run_cancel_step(cancel_exit=1, error=f'gh: HTTP {code}')
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(f'HTTP {code}', result.stderr)
                self.assertEqual(posts, ['api --method POST repos/example/repo/actions/runs/123/cancel'])
                self.assertNotIn('requested cancellation', result.stdout)

    def test_completed_race_is_retained(self):
        result, posts = self.run_cancel_step(cancel_exit=1, error='gh: HTTP 409', status_after='completed')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(posts, ['api --method POST repos/example/repo/actions/runs/123/cancel'])
        self.assertIn('retaining run 123', result.stdout)
        self.assertNotIn('requested cancellation', result.stdout)

    def test_current_head_and_dry_run_never_request_cancellation(self):
        for options in [{'pr_is_current': True}, {'dry_run': True}]:
            with self.subTest(options=options):
                result, posts = self.run_cancel_step(**options)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(posts, [])
                if options.get('dry_run'):
                    self.assertIn('would request cancellation', result.stdout)

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
