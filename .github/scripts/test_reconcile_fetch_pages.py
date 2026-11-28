#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Offline regression checks for the real collector under its real retry wrapper."""
import json
import os
import shutil
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
WORKFLOW = HERE / "auto-retry-cancelled.yml"
if not WORKFLOW.exists():
    WORKFLOW = HERE.parent / "workflows/auto-retry-cancelled.yml"
SOURCE = WORKFLOW.read_text()


def function_from(source):
    start = source.index("          fetch_pages() {")
    end = source.index("          for workflow in services-ci.yml", start)
    return textwrap.dedent(source[start:end])


def page(total, ids):
    return {"total_count": total, "workflow_runs": [{"id": identifier} for identifier in ids]}


class FetchPagesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.responses = self.root / "responses"
        self.calls = self.root / "calls.log"
        self.output = self.root / "snapshot.json"
        scripts = self.root / ".github/scripts"
        scripts.mkdir(parents=True)
        shutil.copyfile(HERE / "select-ci-reconcile-candidates.py", scripts / "select-ci-reconcile-candidates.py")
        stub = self.root / "gh"
        stub.write_text('''#!/usr/bin/env bash
set -euo pipefail
printf '%s\\n' "$*" >> "$TEST_CALLS"
[[ "$1 $2 $3" == "api --method GET" ]]
[[ "$4" == "repos/Example/Repo/actions/workflows/services-ci.yml/runs" ]]
index=0
if [[ -f "$TEST_RESPONSES/counter" ]]; then index=$(<"$TEST_RESPONSES/counter"); fi
printf '%s\\n' "$((index + 1))" > "$TEST_RESPONSES/counter"
[[ -f "$TEST_RESPONSES/$index.rc" ]] || { echo "unscripted API call" >&2; exit 99; }
cat "$TEST_RESPONSES/$index.stdout"
cat "$TEST_RESPONSES/$index.stderr" >&2
exit "$(<"$TEST_RESPONSES/$index.rc")"
''')
        stub.chmod(0o755)
        self.env = {key: value for key, value in os.environ.items()
                    if not key.startswith(("GH_", "GITHUB_"))}
        self.env.update(PATH=str(self.root) + os.pathsep + os.environ["PATH"],
                        GITHUB_REPOSITORY="Example/Repo", GITHUB_STEP_SUMMARY=str(self.root / "summary"),
                        GH_RETRY_MAX_WAIT_SECONDS="0", GH_RETRY_DEAD_LETTER="0",
                        TEST_CALLS=str(self.calls), TEST_RESPONSES=str(self.responses),
                        RETRY_LIBRARY=str(HERE / "gh-retry.sh"), RUNNER_TEMP=str(self.root),
                        SNAPSHOT_OUTPUT=str(self.output))

    def run_fixture(self, responses, *, source=SOURCE, loop=False):
        self.responses.mkdir()
        for index, response in enumerate(responses):
            body = response.get("stdout", "")
            (self.responses / f"{index}.stdout").write_text(body if isinstance(body, str) else json.dumps(body))
            (self.responses / f"{index}.stderr").write_text(response.get("stderr", ""))
            (self.responses / f"{index}.rc").write_text(str(response.get("rc", 0)))
        body = 'gh_retry 3 -- fetch_pages services-ci.yml "$created_query" "$SNAPSHOT_OUTPUT"'
        if loop:
            start = source.index("          for workflow in services-ci.yml")
            end = source.index("          jq -s 'add'", start)
            body = textwrap.dedent(source[start:end])
        command = ('set -euo pipefail\nsource "$RETRY_LIBRARY"\nsleep() { :; }\n'
                   + function_from(source) + '\ncreated_query="2026-11-28T00:00:00Z..2026-11-28T04:00:00Z"\n' + body)
        return subprocess.run(["bash", "-c", command], cwd=self.root, env=self.env,
                              capture_output=True, text=True, check=False)

    def read_calls(self):
        return [line.split() for line in self.calls.read_text().splitlines()]

    def quota(self):
        return {"rc": 17, "stdout": {"message": "PRIVATE_PARTIAL_RESPONSE"},
                "stderr": "gh: API rate limit exceeded for installation (HTTP 403)"}

    def assert_no_publication(self, result, calls):
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")
        self.assertFalse(self.output.exists())
        self.assertEqual(len(self.read_calls()), calls)

    def test_first_page_quota_keeps_exact_failure_and_bounded_retries(self):
        result = self.run_fixture([self.quota()] * 3)
        self.assert_no_publication(result, 3)
        self.assertEqual(result.returncode, 17)
        self.assertIn("API rate limit exceeded", result.stderr)
        self.assertNotIn("PRIVATE_PARTIAL_RESPONSE", result.stderr)

    def test_outer_loop_preserves_quota_instead_of_misreporting_incomplete_snapshot(self):
        result = self.run_fixture([self.quota()] * 3, loop=True)
        self.assertEqual(result.returncode, 17)
        self.assertEqual(len(self.read_calls()), 3)
        self.assertEqual(result.stdout, "")
        self.assertIn("API rate limit exceeded", result.stderr)
        self.assertNotIn("API snapshot remained incomplete", result.stderr)
        self.assertFalse((self.root / "services-ci.yml.pages.json").exists())
        self.assertFalse((self.root / "services-ci.yml.json").exists())

    def test_permission_denial_is_not_retried(self):
        result = self.run_fixture([{"rc": 19, "stderr": "gh: Resource not accessible by integration (HTTP 403)"}])
        self.assert_no_publication(result, 1)
        self.assertEqual(result.returncode, 19)

    def test_later_page_quota_restarts_whole_fetch_and_stays_bounded(self):
        first = {"stdout": page(101, range(1, 101))}
        result = self.run_fixture([first, self.quota()] * 3)
        self.assert_no_publication(result, 6)
        self.assertEqual(result.returncode, 17)
        self.assertEqual([next(arg for arg in call if arg.startswith("page=")) for call in self.read_calls()],
                         ["page=1", "page=2"] * 3)

    def test_recovery_publishes_complete_snapshot_once(self):
        first, last = {"stdout": page(101, range(1, 101))}, {"stdout": page(101, [101])}
        result = self.run_fixture([first, self.quota(), first, last])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "")
        self.assertEqual(len(self.read_calls()), 4)
        pages = json.loads(self.output.read_text())
        self.assertEqual(sum(len(p["workflow_runs"]) for p in pages), 101)

    def test_failed_retry_does_not_overwrite_previous_output(self):
        self.output.write_text("previous validated snapshot")
        result = self.run_fixture([self.quota()] * 3)
        self.assertEqual(result.returncode, 17)
        self.assertEqual(self.output.read_text(), "previous validated snapshot")

    def test_malformed_first_page_never_reports_success(self):
        result = self.run_fixture([{"stdout": "not json"}])
        self.assert_no_publication(result, 1)

    def test_malformed_later_page_never_reports_success(self):
        result = self.run_fixture([{"stdout": page(101, range(1, 101))}, {"stdout": "{"}] * 3)
        self.assert_no_publication(result, 6)
        parse_failure = subprocess.run(["jq", "-s", "."], input="{", text=True,
                                       capture_output=True, check=False)
        self.assertEqual(result.returncode, parse_failure.returncode)

    def test_multiple_first_page_json_roots_refused(self):
        raw = json.dumps(page(0, []))
        result = self.run_fixture([{"stdout": raw + "\n" + raw}])
        self.assert_no_publication(result, 1)

    def test_second_page_multiple_roots_cannot_fake_complete_inventory(self):
        extra_roots = json.dumps(page(101, [])) + "\n" + json.dumps(page(101, [101]))
        result = self.run_fixture([{"stdout": page(101, range(1, 101))}, {"stdout": extra_roots}])
        self.assert_no_publication(result, 2)
        self.assertIn("expected one workflow-run response", result.stderr)

    def test_second_page_must_be_an_object(self):
        result = self.run_fixture([{"stdout": page(101, range(1, 101))}, {"stdout": "[]"}])
        self.assert_no_publication(result, 2)
        self.assertIn("expected one workflow-run response", result.stderr)

    def test_ceiling_refuses_before_second_read(self):
        result = self.run_fixture([{"stdout": page(1000, range(1, 101))}])
        self.assert_no_publication(result, 1)
        self.assertEqual(result.returncode, 2)
        self.assertIn("1,000-result ceiling", result.stderr)

    def test_empty_complete_response_is_valid(self):
        result = self.run_fixture([{"stdout": page(0, [])}])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(self.output.read_text()), [page(0, [])])

    def test_integer_valued_scientific_count_is_safe_for_page_arithmetic(self):
        raw = json.dumps(page(100, range(1, 101))).replace('"total_count": 100', '"total_count": 1e2')
        result = self.run_fixture([{"stdout": raw}])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(self.read_calls()), 1)

    def test_total_drift_keeps_three_whole_snapshot_attempts(self):
        result = self.run_fixture([{"stdout": page(101, range(1, 101))}, {"stdout": page(102, [101])}] * 3, loop=True)
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertEqual(len(self.read_calls()), 6)
        self.assertEqual(result.stderr.count("changed total_count during pagination"), 3)
        self.assertIn("API snapshot remained incomplete after 3 reads", result.stderr)

    def test_truncation_never_becomes_an_empty_candidate_set(self):
        result = self.run_fixture([{"stdout": page(101, range(1, 101))}, {"stdout": page(101, [])}] * 3, loop=True)
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertEqual(len(self.read_calls()), 6)
        self.assertEqual(result.stderr.count("returned 100 of 101 runs"), 3)

    def test_duplicate_ids_still_fail_complete_snapshot_validation(self):
        result = self.run_fixture([{"stdout": page(101, range(1, 101))}, {"stdout": page(101, [1])}] * 3, loop=True)
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertEqual(len(self.read_calls()), 6)
        self.assertEqual(result.stderr.count("duplicate or malformed run IDs"), 3)


if __name__ == "__main__":
    unittest.main()
