#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Stdlib-only, offline execution of the exact all-green roster step."""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/services-ci.yml"


def roster_script():
    lines = WORKFLOW.read_text().splitlines()
    start = lines.index("      - name: Require every selected service build to pass")
    run = next(i for i in range(start, len(lines)) if lines[i] == "        run: |")
    result = []
    for line in lines[run + 1:]:
        if line and not line.startswith("          "):
            break
        result.append(line[10:] if line else "")
    return "\n".join(result) + "\n"


def run_fixture(scenario, script=None):
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        scripts = root / ".github/scripts"
        scripts.mkdir(parents=True)
        for name in ["gh-retry.sh", "gh-transient-patterns.txt"]:
            shutil.copyfile(ROOT / ".github/scripts" / name, scripts / name)
        binary = root / "bin"
        binary.mkdir()
        gh = binary / "gh"
        gh.write_text(f"#!{sys.executable}\n" + '''import json, os, pathlib, sys
root = pathlib.Path(os.environ['FIXTURE_ROOT'])
args = sys.argv[1:]
if args == ['api', 'rate_limit', '--jq', '.resources.core.reset']:
    print(1900)
    sys.exit(0)
assert args == ['api', '--paginate', 'repos/fixture/repo/actions/runs/123/attempts/1/jobs?per_page=100', '--jq', '.jobs[] | {name,conclusion}'], args
counter = root / 'calls'
n = int(counter.read_text()) + 1 if counter.exists() else 1
counter.write_text(str(n))
scenario = os.environ['SCENARIO']
if scenario in ['auth403', 'notfound404']:
    print('HTTP 403: Resource not accessible by integration' if scenario == 'auth403' else 'HTTP 404: Not Found', file=sys.stderr)
    sys.exit(1)
if scenario == 'exhausted' or (scenario in ['rate', 'partial'] and n == 1) or (scenario == 'delayed_rate' and n == 2):
    if scenario == 'partial':
        print(json.dumps({'name': 'build (stale) / stale (build)', 'conclusion': 'success'}))
    print('HTTP 403: API rate limit exceeded for installation', file=sys.stderr)
    sys.exit(1)
if scenario != 'missing' and not (scenario in ['delayed', 'delayed_rate'] and n == 1):
    print(json.dumps({'name': 'build (openbank-fixture) / openbank-fixture (build)', 'conclusion': scenario if scenario in ['skipped', 'failure'] else 'success'}))
if scenario == 'extra':
    print(json.dumps({'name': 'build (extra) / extra (build)', 'conclusion': 'success'}))
''')
        sleep = binary / "sleep"
        sleep.write_text(f"#!{sys.executable}\nimport os,pathlib,sys\n"
                         "assert sys.argv[1:] in [['900'], ['15']], sys.argv\n"
                         "with (pathlib.Path(os.environ['FIXTURE_ROOT'])/'sleeps').open('a') as f: f.write(sys.argv[1]+'\\n')\n")
        date = binary / "date"
        date.write_text(f"#!{sys.executable}\nimport sys\nassert sys.argv[1:] == ['+%s']\nprint(1000)\n")
        for path in [gh, sleep, date]:
            path.chmod(0o755)
        # The workflow's embedded validation must use the same actual Python interpreter.
        (binary / "python3").symlink_to(sys.executable)
        job = root / "jobs.ndjson"
        env = {"PATH": str(binary) + os.pathsep + os.environ.get("PATH", os.defpath),
               "FIXTURE_ROOT": directory, "SCENARIO": scenario, "JOB_FILE": str(job),
               "GITHUB_REPOSITORY": "fixture/repo", "GITHUB_RUN_ID": "123",
               "GITHUB_RUN_ATTEMPT": "1", "SERVICES_JSON": '["openbank-fixture"]'}
        result = subprocess.run(["bash", "-c", script or roster_script()], cwd=root,
                                env=env, capture_output=True, text=True, timeout=10, check=False)
        calls = int((root / "calls").read_text())
        sleeps = (root / "sleeps").read_text().splitlines() if (root / "sleeps").exists() else []
        return result.returncode, job.read_text() if job.exists() else None, calls, sleeps


class RosterRetryTest(unittest.TestCase):
    def test_successful_exact_roster(self):
        code, roster, calls, sleeps = run_fixture("success")
        self.assertEqual((code, calls, sleeps), (0, 1, []))
        self.assertEqual(len(roster.splitlines()), 1)

    def test_rate_limit_retries_once_with_bounded_wait(self):
        code, roster, calls, sleeps = run_fixture("rate")
        self.assertEqual((code, calls, sleeps), (0, 2, ['900']))
        self.assertEqual(len(roster.splitlines()), 1)

    def test_partial_page_is_not_published_or_appended(self):
        code, roster, calls, sleeps = run_fixture("partial")
        self.assertEqual((code, calls, sleeps), (0, 2, ['900']))
        self.assertEqual(len(roster.splitlines()), 1)
        self.assertNotIn('stale', roster)

    def test_permanent_errors_do_not_retry(self):
        for scenario in ['auth403', 'notfound404']:
            with self.subTest(scenario=scenario):
                code, roster, calls, sleeps = run_fixture(scenario)
                self.assertNotEqual(code, 0)
                self.assertEqual((roster, calls, sleeps), (None, 1, []))

    def test_exhausted_quota_has_no_authorized_roster(self):
        code, roster, calls, sleeps = run_fixture('exhausted')
        self.assertNotEqual(code, 0)
        self.assertEqual((roster, calls, sleeps), (None, 2, ['900']))

    def test_missing_retries_once_but_bad_or_extra_module_stays_red(self):
        for scenario in ['missing', 'skipped', 'failure', 'extra']:
            with self.subTest(scenario=scenario):
                code, _, calls, sleeps = run_fixture(scenario)
                self.assertNotEqual(code, 0)
                self.assertEqual((calls, sleeps), (2, ['15']) if scenario == 'missing' else (1, []))

    def test_delayed_carry_forward_gets_one_strict_reread(self):
        code, roster, calls, sleeps = run_fixture('delayed')
        self.assertEqual((code, calls, sleeps), (0, 2, ['15']))
        self.assertEqual(len(roster.splitlines()), 1)

    def test_second_read_rate_limit_has_no_second_quota_wait(self):
        code, _, calls, sleeps = run_fixture('delayed_rate')
        self.assertNotEqual(code, 0)
        self.assertEqual((calls, sleeps), (2, ['15']))

    def test_negative_control_catches_weakened_selected_module_checks(self):
        script = roster_script()
        mutant = script.replace('if set(observed) != expected:', 'if False:')
        mutant = mutant.replace('if bad:', 'if False:')
        self.assertNotEqual(mutant, script)
        code, _, _, _ = run_fixture('skipped', mutant)
        self.assertEqual(code, 0, 'removed validation must expose the unsafe green')

    def test_negative_control_catches_partial_pagination_contamination(self):
        script = roster_script()
        mutant = script.replace('> "${JOB_FILE}.candidate"', '>> "${JOB_FILE}.candidate"')
        mutant = mutant.replace('rm -f -- "${JOB_FILE}.candidate"\n    return', ':\n    return')
        self.assertNotEqual(mutant, script)
        code, roster, _, _ = run_fixture('partial', mutant)
        self.assertNotEqual(code, 0)
        self.assertIn('stale', roster)


if __name__ == '__main__':
    unittest.main()
