# SPDX-License-Identifier: Apache-2.0
import fnmatch
import json
import os
import re
import subprocess
import tempfile
import textwrap
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

WORKFLOW = Path(__file__).resolve().parents[2] / "workflows" / "dependency-review.yml"
SUBMISSION = WORKFLOW.with_name("dependency-submission.yml")
# Negative cases launch many fake CLI processes. Keep their wall-clock harness
# bounded without confusing contention on a contributor's host with a verdict.
# This does not alter the workflow's retry count, delays or fail-closed policy.
HARNESS_TIMEOUT = 60
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
 if any('/compare/1e009deab5085b19d3977d53f5b634a8c7f5a548...' in a for a in args):
  if case=='legacy-era-api-failure': sys.exit(1)
  print('behind' if case=='legacy-base' else 'ahead'); sys.exit(0)
 print('mergebase' if '--jq' in args else json.dumps({'merge_base_commit': {'sha': 'a'*40}})); sys.exit(0)
if case=='api-failure': sys.exit(1)
if case=='partial-api-failure':
 print(json.dumps(dict(check_runs=[dict(name='Submit fleet dependency graph',conclusion='success')]))); sys.exit(1)
if case=='malformed': print('{"check_runs":null}'); sys.exit(0)
name='Submit fleet dependency graph'
rows=[dict(name=name,conclusion='success')]
if case=='missing' or (case=='missing-head' and any('/head/' in a for a in args)):
 rows=[]
if case=='failed-submission': rows=[dict(name=name,conclusion='failure',status='completed')]
if case=='cancelled-submission': rows=[dict(name=name,conclusion='cancelled',status='completed')]
if case=='timed-out-submission': rows=[dict(name=name,conclusion='timed_out',status='completed')]
if case=='skipped-submission': rows=[dict(name=name,conclusion='skipped',status='completed')]
if case=='mixed-terminal-pending': rows=[dict(name=name,conclusion='cancelled',status='completed'),dict(name=name,conclusion=None,status='in_progress')]
if case=='pending-base': rows=[dict(name=name,conclusion=None,status='in_progress')]
if case=='wrong-job': rows=[dict(name='Other job',conclusion='success')]
if case=='paginated':
 print(json.dumps(dict(check_runs=[dict(name='Other job',conclusion='success')]*100)))
 if '--paginate' not in args: sys.exit(0)
print(json.dumps(dict(check_runs=rows)))
"""


# The workflow's six current request shapes. Unknown arguments still execute the
# original fake CLI; this is a test-only startup optimization, not an API allowlist.
_GH_VECTORS = (
    ("api", "repos/example/repo/compare/base...head", "--jq", ".merge_base_commit.sha"),
    ("api", "repos/example/repo/compare/base...head"),
    ("api", "repos/example/repo/compare/1e009deab5085b19d3977d53f5b634a8c7f5a548...mergebase", "--jq", ".status"),
    ("api", "--paginate", "repos/example/repo/commits/mergebase/check-runs?per_page=100"),
    ("api", "--paginate", "repos/example/repo/commits/head/check-runs?per_page=100"),
    ("api", "-i", "repos/example/repo/dependency-graph/compare/" + "a" * 40 + "...head"),
)
_GH_REPLAY_SETUP = r'''import contextlib, io, json, os, pathlib, shlex, sys
config = json.load(sys.stdin)
program = compile(config["source"], config["original"], "exec")
call_file = pathlib.Path(config["call_file"])
os.environ["CASE"] = config["case"]
os.environ["CALLS"] = str(call_file)
lines = ["#!/bin/sh", "set -eu"]
for argv in config["vectors"]:
    call_file.write_bytes(b"")
    sys.argv = [config["original"], *argv]
    stdout, stderr = io.StringIO(), io.StringIO()
    status = 0
    with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
        try:
            exec(program, {"__name__": "__main__", "__file__": config["original"]})
        except SystemExit as result:
            status = result.code if result.code is not None else 0
    if not isinstance(status, int) or not 0 <= status <= 255:
        raise ValueError("fake CLI must return an integer exit status")
    outputs = (call_file.read_bytes().decode("utf-8"), stdout.getvalue(), stderr.getvalue())
    if any("\0" in value for value in outputs):
        raise ValueError("binary fake CLI output cannot be replayed by the shell")
    condition = [f'[ "${{CASE-}}" = {shlex.quote(config["case"])} ]', f'[ "$#" -eq {len(argv)} ]']
    condition += [f'[ "${{{index}}}" = {shlex.quote(argument)} ]' for index, argument in enumerate(argv, 1)]
    lines.append("if " + " && ".join(condition) + "; then")
    lines.append("  printf '%s' " + shlex.quote(outputs[0]) + ' >> "$CALLS"')
    lines.append("  printf '%s' " + shlex.quote(outputs[1]))
    lines.append("  printf '%s' " + shlex.quote(outputs[2]) + " >&2")
    lines.append(f"  exit {status}")
    lines.append("fi")
lines.append("exec " + shlex.quote(config["original"]) + ' "$@"')
sys.stdout.buffer.write(("\n".join(lines) + "\n").encode("utf-8"))
'''


def _install_gh(root, case, *, vectors=_GH_VECTORS, source=GH):
    # Only GH is pure in CASE+argv. DIFF_GH reads its call count and must never
    # use this replay. Each test owns its setup process, responses and call log.
    original = root / "gh"
    original.write_text(source)
    original.chmod(0o755)
    directory = root / "gh-replay"
    directory.mkdir()
    config = dict(source=source, original=str(original), case=case,
                  vectors=vectors, call_file=str(root / "gh-setup-calls"))
    # Keep the generator binary: text-mode newline conversion would corrupt a
    # response containing CRLF. No caller environment, argv or stream is mutated.
    result = subprocess.run(
        ["python3", "-c", _GH_REPLAY_SETUP], input=json.dumps(config).encode("utf-8"),
        capture_output=True, check=True, timeout=HARNESS_TIMEOUT,
    )
    shim = directory / "gh"
    shim.write_bytes(result.stdout)
    shim.chmod(0o755)
    return directory


class FixtureReplayTests(unittest.TestCase):
    def test_exact_arguments_and_partial_error_output_are_replayed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            executions = root / "executions"
            source = """#!/usr/bin/env python3
import json, os, sys
args = sys.argv[1:]
with open(os.environ['CALLS'], 'a') as out: out.write(json.dumps(args)+'\\n')
with open(EXECUTIONS, 'a') as out: out.write('run\\n')
sys.stdout.write(json.dumps([os.environ['CASE'], args])+'\\r\\n\\n')
sys.stderr.write('partial error\\r\\n\\n')
sys.exit(7)
""".replace("EXECUTIONS", repr(str(executions)))
            vectors = (("a b", "c"), ("a", "b c"), ("",), ("quote'\"*?[]\n", "žluťoučký"))
            before = {key: os.environ.get(key) for key in ("CASE", "CALLS")}
            shim = _install_gh(root, "known", vectors=vectors, source=source) / "gh"
            calls = root / "calls"
            self.assertFalse(calls.exists(), "precomputation must not write the caller's log")
            self.assertEqual(before, {key: os.environ.get(key) for key in before})
            env = dict(os.environ, CASE="known", CALLS=str(calls))
            for argv in vectors:
                result = subprocess.run([str(shim), *argv], env=env, capture_output=True, timeout=HARNESS_TIMEOUT)
                self.assertEqual(result.returncode, 7)
                self.assertEqual(result.stdout, (json.dumps(["known", list(argv)]) + "\r\n\n").encode())
                self.assertEqual(result.stderr, b"partial error\r\n\n")
            self.assertEqual(len(executions.read_text().splitlines()), len(vectors))
            # Neither a new argument boundary nor a new CASE may hit an old response.
            for case, argv in (("known", ("unseen", "")), ("other", vectors[0])):
                result = subprocess.run([str(shim), *argv], env=dict(env, CASE=case),
                                        capture_output=True, timeout=HARNESS_TIMEOUT)
                self.assertEqual(result.returncode, 7)
                self.assertEqual(result.stdout, (json.dumps([case, list(argv)]) + "\r\n\n").encode())
                self.assertEqual(result.stderr, b"partial error\r\n\n")
            self.assertEqual(len(executions.read_text().splitlines()), len(vectors) + 2)
            self.assertEqual([json.loads(line) for line in calls.read_text().splitlines()],
                             [list(argv) for argv in vectors] + [["unseen", ""], list(vectors[0])])

    def test_real_fixture_preserves_nonzero_queries_and_unknown_case_fallback(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shim = _install_gh(root, "partial-api-failure") / "gh"
            calls = root / "calls"
            env = dict(os.environ, CASE="partial-api-failure", CALLS=str(calls))
            known = ("api", "--paginate", "repos/example/repo/commits/head/check-runs?per_page=100")
            unknown = ("api", "repos/example/repo/commits/head/check-runs?per_page=100")
            results = [subprocess.run([str(shim), *argv], env=env, capture_output=True,
                                      timeout=HARNESS_TIMEOUT) for argv in (known, unknown)]
            self.assertEqual([(result.returncode, result.stdout, result.stderr) for result in results],
                             [(1, results[0].stdout, b"")] * 2)
            self.assertIn(b'Submit fleet dependency graph', results[0].stdout)
            result = subprocess.run([str(shim), *known], env=dict(env, CASE="valid"),
                                    capture_output=True, timeout=HARNESS_TIMEOUT)
            self.assertEqual((result.returncode, result.stdout, result.stderr), (0, results[0].stdout, b""))
            self.assertEqual([json.loads(line) for line in calls.read_text().splitlines()],
                             [list(known), list(unknown), list(known)])


class BasisTests(unittest.TestCase):
    def assert_cases_fail(self, cases):
        # Each case owns its temporary gh shim and call log. Run independent
        # bounded-retry failures together so the suite does not sum their waits.
        with ThreadPoolExecutor(max_workers=3) as executor:
            results = list(executor.map(self.run_case, cases))
        for case, (result, _) in zip(cases, results, strict=True):
            with self.subTest(case=case):
                self.assertNotEqual(result.returncode, 0)

    def test_terminal_unsuccessful_base_is_rejected_before_snapshot_wait(self):
        for case in ["failed-submission", "cancelled-submission", "timed-out-submission", "skipped-submission"]:
            with self.subTest(case=case):
                failed, _ = self.run_base_case(case)
                self.assertNotEqual(failed.returncode, 0)
                self.assertIn("Base dependency graph failed", failed.stdout)

    def test_legacy_partial_graph_base_is_rejected_before_snapshot_wait(self):
        failed, _ = self.run_base_case("legacy-base")
        self.assertNotEqual(failed.returncode, 0)
        self.assertIn("Legacy dependency basis", failed.stdout)

    def test_base_preflight_defers_uncertain_state_to_final_guard(self):
        for case in ["valid", "pending-base", "mixed-terminal-pending", "missing", "api-failure", "malformed", "legacy-era-api-failure"]:
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
            gh_directory = _install_gh(root, case)
            calls = root / "calls"
            env = dict(
                os.environ,
                PATH=str(gh_directory) + os.pathsep + directory + os.pathsep + os.environ["PATH"],
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
                timeout=HARNESS_TIMEOUT,
            )
            return result, calls.read_text()

    def test_snapshot_wait_matches_submission_trigger(self):
        source = WORKFLOW.read_text()
        pattern = re.search(r"if grep -qE '([^']+)' <<<", source)
        self.assertIsNotNone(pattern)
        submitted_paths = [
            "openbank-ledger-service/build.gradle.kts",
            "build-logic/src/main/kotlin/openbank.dependency-vulnerability-pins.gradle.kts",
            "build-logic/settings.gradle.kts",
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
        self.assertEqual(len(trigger_patterns), 10)
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
            # The final basis recheck sleeps in Python, not in the shell. Keep
            # the bounded production delays while making negative tests fast.
            (root / "sitecustomize.py").write_text("import time\ntime.sleep = lambda _: None\n")
            gh_directory = _install_gh(root, case)
            for name, body in [("sleep", "#!/bin/sh\nexit 0\n")]:
                p = root / name
                p.write_text(body)
                p.chmod(0o755)
            calls = root / "calls"
            env = dict(
                os.environ,
                PATH=str(gh_directory) + os.pathsep + directory + os.pathsep + os.environ["PATH"],
                PYTHONPATH=directory + os.pathsep + os.environ.get("PYTHONPATH", ""),
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
                timeout=HARNESS_TIMEOUT,
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
        self.assert_cases_fail([
            "api-failure",
            "partial-api-failure",
            "compare-failure",
            "malformed",
            "legacy-era-api-failure",
        ])

    def test_missing_or_unsuccessful_graphs_fail(self):
        self.assert_cases_fail(["missing", "missing-head", "failed-submission", "wrong-job", "legacy-base"])

    def test_successful_producer_does_not_hide_missing_snapshot(self):
        self.assert_cases_fail([
            "snapshot-base-zero",
            "snapshot-head-zero",
            "snapshot-malformed-warning",
            "snapshot-unknown-warning",
            "snapshot-non200",
            "snapshot-api-failure",
        ])

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


DIFF_GH = """#!/usr/bin/env python3
import os, sys
calls = os.environ['CALLS']
with open(calls, 'a') as out: out.write('gh\\n')
n = sum(1 for _ in open(calls))
case = os.environ['CASE']
if case == 'transient' and n >= 2:
    print('{}'); sys.exit(0)
if case == 'quota':
    print('gh: API rate limit exceeded for installation. (HTTP 403)', file=sys.stderr)
else:
    print('gh: HTTP 502: Bad Gateway', file=sys.stderr)
sys.exit(1)
"""


class DiffApiWaitTests(unittest.TestCase):
    def run_wait(self, case):
        source = WORKFLOW.read_text()
        block = source.split(
            "      - name: Wait for dependency-diff API after a failed review\n", 1
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
            (root / "gh").write_text(DIFF_GH)
            (root / "sleep").write_text('#!/bin/sh\necho "$1" >> "$SLEEPS"\n')
            for stub in ("gh", "sleep"):
                (root / stub).chmod(0o755)
            calls, sleeps = root / "calls", root / "sleeps"
            calls.touch()
            sleeps.touch()
            env = dict(
                os.environ,
                PATH=directory + os.pathsep + os.environ["PATH"],
                CASE=case,
                CALLS=str(calls),
                SLEEPS=str(sleeps),
                GITHUB_REPOSITORY="example/repo",
                BASE_SHA="base",
                HEAD_SHA="head",
            )
            result = subprocess.run(
                ["bash", "-c", script], env=env, capture_output=True, text=True, timeout=HARNESS_TIMEOUT
            )
            return result, len(calls.read_text().splitlines()), len(sleeps.read_text().splitlines())

    def test_exhausted_quota_fails_after_one_call_without_waiting(self):
        result, calls, sleeps = self.run_wait("quota")
        self.assertEqual(result.returncode, 1)
        self.assertEqual((calls, sleeps), (1, 0))
        self.assertIn("installation API quota is exhausted", result.stdout)

    def test_transient_failure_recovers_within_the_bounded_wait(self):
        result, calls, sleeps = self.run_wait("transient")
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        self.assertEqual((calls, sleeps), (2, 1))

    def test_persistent_non_quota_failure_stays_red_after_every_attempt(self):
        result, calls, sleeps = self.run_wait("persistent")
        self.assertEqual(result.returncode, 1)
        self.assertEqual((calls, sleeps), (6, 5))
        self.assertIn("remained", result.stdout)


if __name__ == "__main__":
    unittest.main()
