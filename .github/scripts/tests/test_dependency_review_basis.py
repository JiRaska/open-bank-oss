# SPDX-License-Identifier: Apache-2.0
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import textwrap

WORKFLOW = Path(__file__).resolve().parents[2] / "workflows" / "dependency-review.yml"
GH = """#!/usr/bin/env python3
import json, os, sys
args=sys.argv[1:]
with open(os.environ['CALLS'], 'a') as out: out.write(json.dumps(args)+'\\n')
case=os.environ['CASE']
if '--arg' in args or ('--jq' in args and args[args.index('--jq')+1]=='--arg'):
 sys.exit(2)
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
if case=='failed-submission': rows=[dict(name=name,conclusion='failure')]
if case=='wrong-job': rows=[dict(name='Other job',conclusion='success')]
if case=='paginated':
 print(json.dumps(dict(check_runs=[dict(name='Other job',conclusion='success')]*100)))
 if '--paginate' not in args: sys.exit(0)
print(json.dumps(dict(check_runs=rows)))
"""


class BasisTests(unittest.TestCase):
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
            "Both sides of the dependency diff had a submitted graph", r.stdout
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

    def test_submission_on_later_page_is_found(self):
        r, calls = self.run_case("paginated")
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        self.assertIn(
            "Both sides of the dependency diff had a submitted graph", r.stdout
        )
        self.assertIn('"--paginate"', calls)


if __name__ == "__main__":
    unittest.main()
