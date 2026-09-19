# SPDX-License-Identifier: Apache-2.0
import fnmatch
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
import textwrap

WORKFLOW = Path(__file__).resolve().parents[2] / "workflows" / "dependency-review.yml"
SUBMISSION = WORKFLOW.with_name("dependency-submission.yml")
GH = """#!/usr/bin/env python3
import base64, json, os, sys
args=sys.argv[1:]
with open(os.environ['CALLS'], 'a') as out: out.write(json.dumps(args)+'\\n')
case=os.environ['CASE']
if '--arg' in args or ('--jq' in args and args[args.index('--jq')+1]=='--arg'):
 sys.exit(2)
if any('/dependency-graph/compare/' in a for a in args):
 if case=='snapshot-api-failure': sys.exit(1)
 print('HTTP/2.0 503 Service Unavailable' if case=='snapshot-non200' else 'HTTP/2.0 200 OK')
 warnings={
  'snapshot-base-zero': (0,1),
  'snapshot-head-zero': (1,0),
  'snapshot-both-positive': (1,2),
 }
 if case in warnings:
  base,head=warnings[case]
  message=f'The number of snapshots compared for the base SHA ({base}) and the head SHA ({head}) do not match. You may see unexpected additions in the diff.'
  print('X-GitHub-Dependency-Graph-Snapshot-Warnings: '+base64.b64encode(message.encode()).decode())
 if case=='snapshot-malformed-warning':
  print('X-GitHub-Dependency-Graph-Snapshot-Warnings: not-base64')
 if case=='snapshot-unknown-warning':
  print('X-GitHub-Dependency-Graph-Snapshot-Warnings: '+base64.b64encode(b'Unknown snapshot state').decode())
 print()
 print('[]')
 sys.exit(0)
if any('/compare/' in a for a in args):
 if case=='compare-failure': sys.exit(1)
 print('mergebase'); sys.exit(0)
if case=='api-failure': sys.exit(1)
if case=='partial-api-failure':
 print(json.dumps(dict(check_runs=[dict(name='Submit fleet dependency graph',conclusion='success')]))); sys.exit(1)
if case=='malformed': print('{"check_runs":null}'); sys.exit(0)
name='Submit fleet dependency graph'
rows=[dict(name=name,conclusion='success')]
if case=='missing' or (case=='missing-head' and any('/head/' in a for a in args)):
 rows=[]
if case=='failed-submission': rows=[dict(name=name,conclusion='failure',status='completed')]
if case=='pending-base': rows=[dict(name=name,conclusion=None,status='in_progress')]
if case=='wrong-job': rows=[dict(name='Other job',conclusion='success')]
if case=='paginated':
 print(json.dumps(dict(check_runs=[dict(name='Other job',conclusion='success')]*100)))
 if '--paginate' not in args: sys.exit(0)
print(json.dumps(dict(check_runs=rows)))
"""


class BasisTests(unittest.TestCase):
    def test_known_failed_base_is_rejected_before_snapshot_wait(self):
        failed, _ = self.run_base_case("failed-submission")
        self.assertNotEqual(failed.returncode, 0)
        self.assertIn("Base dependency graph failed", failed.stdout)

    def test_base_preflight_defers_uncertain_state_to_final_guard(self):
        for case in ["valid", "pending-base", "missing", "api-failure", "malformed"]:
            with self.subTest(case=case):
                result, _ = self.run_base_case(case)
                self.assertEqual(result.returncode, 0, result.stderr + result.stdout)

    def run_base_case(self, case):
        source = WORKFLOW.read_text()
        block = source.split(
            "      - name: Reject known failed base dependency graph\n", 1
        )[1]
        body = block.split("        run: |\n", 1)[1]
        lines = []
        for line in body.splitlines():
            if line.strip() and not line.startswith("          "):
                break
            lines.append(line)
        script = textwrap.dedent("\n".join(lines))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            gh = root / "gh"
            gh.write_text(GH)
            gh.chmod(0o755)
            calls = root / "calls"
            env = dict(
                os.environ,
                PATH=directory + os.pathsep + os.environ["PATH"],
                CASE=case,
                CALLS=str(calls),
                GITHUB_REPOSITORY="example/repo",
                BASE_SHA="base",
                HEAD_SHA="head",
            )
            result = subprocess.run(
                ["bash", "-c", script],
                env=env,
                capture_output=True,
                text=True,
                timeout=10,
            )
            return result, calls.read_text()

    def test_snapshot_wait_matches_submission_trigger(self):
        source = WORKFLOW.read_text()
        pattern = re.search(r"if grep -qE '([^']+)' <<<", source)
        self.assertIsNotNone(pattern)
        submitted_paths = [
            "openbank-ledger-service/build.gradle.kts",
            "openbank-libs/gradle/libs.versions.toml",
            "settings.gradle.kts",
            "gradle/wrapper/gradle-wrapper.properties",
            ".github/workflows/dependency-submission.yml",
            ".github/scripts/dependency_snapshot.py",
            ".github/scripts/dependency-resolution-strict.init.gradle",
            ".github/scripts/tests/test_dependency_snapshot.py",
            ".github/scripts/tests/test_dependency_resolution_guard.py",
        ]
        submission = SUBMISSION.read_text().split("  pull_request:", 1)[1].split(
            "  schedule:", 1
        )[0]
        trigger_patterns = re.findall(r'^\s+- "([^"]+)"$', submission, re.MULTILINE)
        self.assertEqual(len(trigger_patterns), 9)
        for path in submitted_paths:
            with self.subTest(path=path):
                self.assertTrue(
                    any(fnmatch.fnmatch(path, trigger) for trigger in trigger_patterns)
                )
                self.assertRegex(path, pattern.group(1))
        self.assertNotRegex("docs/architecture.md", pattern.group(1))

    def run_case(self, case):
        source = WORKFLOW.read_text()
        block = source.split(
            "      - name: Assert the dependency diff had a valid basis\n", 1
        )[1]
        body = block.split("        run: |\n", 1)[1]
        lines = []
        for line in body.splitlines():
            if line.strip() and not line.startswith("          "):
                break
            lines.append(line)
        script = textwrap.dedent("\n".join(lines))
        self.assertIn("set -euo pipefail", script)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, body in [("gh", GH), ("sleep", "#!/bin/sh\nexit 0\n")]:
                p = root / name
                p.write_text(body)
                p.chmod(0o755)
            calls = root / "calls"
            env = dict(
                os.environ,
                PATH=directory + os.pathsep + os.environ["PATH"],
                CASE=case,
                CALLS=str(calls),
                GITHUB_REPOSITORY="example/repo",
                BASE_SHA="base",
                HEAD_SHA="head",
                SUBMIT_JOB_NAME="Submit fleet dependency graph",
            )
            result = subprocess.run(
                ["bash", "-c", script],
                env=env,
                capture_output=True,
                text=True,
                timeout=10,
            )
            return result, calls.read_text()

    def test_valid_graphs_require_successful_queries(self):
        r, calls = self.run_case("valid")
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        self.assertIn(
            "Both sides had successful producers and the comparison reported no missing snapshot", r.stdout
        )
        self.assertNotIn('"--arg"', calls)

    def test_api_and_compare_errors_fail_closed(self):
        for case in [
            "api-failure",
            "partial-api-failure",
            "compare-failure",
            "malformed",
        ]:
            with self.subTest(case=case):
                self.assertNotEqual(self.run_case(case)[0].returncode, 0)

    def test_missing_or_unsuccessful_graphs_fail(self):
        for case in ["missing", "missing-head", "failed-submission", "wrong-job"]:
            with self.subTest(case=case):
                self.assertNotEqual(self.run_case(case)[0].returncode, 0)

    def test_successful_producer_does_not_hide_missing_snapshot(self):
        for case in [
            "snapshot-base-zero",
            "snapshot-head-zero",
            "snapshot-malformed-warning",
            "snapshot-unknown-warning",
            "snapshot-non200",
            "snapshot-api-failure",
        ]:
            with self.subTest(case=case):
                self.assertNotEqual(self.run_case(case)[0].returncode, 0)

    def test_repeated_successful_snapshot_is_not_missing(self):
        result, _ = self.run_case("snapshot-both-positive")
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)

    def test_submission_on_later_page_is_found(self):
        r, calls = self.run_case("paginated")
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        self.assertIn(
            "Both sides had successful producers and the comparison reported no missing snapshot", r.stdout
        )
        self.assertIn('"--paginate"', calls)


if __name__ == "__main__":
    unittest.main()
