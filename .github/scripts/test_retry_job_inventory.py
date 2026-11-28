#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise exact retry inventory functions with an offline GH transport."""
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

SOURCE = Path(__file__).with_name("spot-kill-retry.sh").read_text()
FUNCTIONS = SOURCE[SOURCE.index("jobs_snapshot() {"):SOURCE.index("# A cancelled PR run")]


def job(identifier):
    return {"id": identifier, "run_id": 123, "run_attempt": 1, "status": "completed",
            "conclusion": "failure", "name": f"job-{identifier}", "steps": []}


class InventoryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.cache = self.root / "cache"
        self.cache.mkdir()
        self.pages = self.root / "pages.json"
        self.log = self.root / "calls.jsonl"
        stub = self.root / "gh"
        stub.write_text('''#!/usr/bin/env python3
import json, os, re, sys
from pathlib import Path
with open(os.environ["CALLS"], "a") as output:
    output.write(json.dumps(sys.argv[1:]) + "\\n")
assert len(sys.argv) == 3 and sys.argv[1] == "api", sys.argv
match = re.fullmatch(r"repos/Example/Repo/actions/runs/123/attempts/1/jobs\\?per_page=100&page=([1-9][0-9]*)", sys.argv[2])
assert match, sys.argv
response = json.loads(Path(os.environ["PAGES"]).read_text()).get(match[1], {"error": "unexpected page"})
if "error" in response:
    if "failed_stdout" in response:
        print(response["failed_stdout"])
    print(response["error"], file=sys.stderr)
    raise SystemExit(1)
print(response["raw"] if "raw" in response else json.dumps(response))
''')
        stub.chmod(0o755)
        self.env = {**os.environ, "GH_BIN": str(stub), "LOG_CACHE_DIR": str(self.cache),
                    "GITHUB_REPOSITORY": "Example/Repo", "RUN_ID": "123",
                    "PAGES": str(self.pages), "CALLS": str(self.log)}

    def save(self, pages):
        self.pages.write_text(json.dumps({str(index + 1): page for index, page in enumerate(pages)}))

    def run_queries(self, body="jobs_query '.jobs | length'"):
        return subprocess.run(["bash", "-c", 'set -euo pipefail\ngh_() { "$GH_BIN" "$@"; }\n'
                               + FUNCTIONS + "\n" + body],
                              env=self.env, capture_output=True, text=True, check=False)

    def calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def assert_rejected(self, pages):
        self.save(pages)
        result = self.run_queries()
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertFalse((self.cache / "jobs-attempt-1.json").exists())

    def test_four_queries_read_one_complete_page_once(self):
        self.save([{"total_count": 2, "jobs": [job(1), job(2)]}])
        result = self.run_queries("for query in 1 2 3 4; do jobs_query '.jobs | length'; done")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines(), ["2"] * 4)
        self.assertEqual(len(self.calls()), 1)

    def test_complete_two_pages_are_read_once_each(self):
        self.save([{"total_count": 101, "jobs": [job(i) for i in range(1, 101)]},
                   {"total_count": 101, "jobs": [job(101)]}])
        result = self.run_queries("for query in 1 2 3 4; do jobs_query '.jobs | length'; done")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines(), ["101"] * 4)
        self.assertEqual(len(self.calls()), 2)

    def test_truncated_first_page_refused(self):
        self.assert_rejected([{"total_count": 101, "jobs": [job(1)]}])
        self.assertEqual(len(self.calls()), 1)

    def test_changed_total_on_later_page_refused(self):
        self.assert_rejected([{"total_count": 101, "jobs": [job(i) for i in range(1, 101)]},
                              {"total_count": 102, "jobs": [job(101), job(102)]}])

    def test_duplicate_ids_across_pages_refused(self):
        self.assert_rejected([{"total_count": 101, "jobs": [job(i) for i in range(1, 101)]},
                              {"total_count": 101, "jobs": [job(1)]}])

    def test_duplicate_ids_on_first_page_refused(self):
        self.assert_rejected([{"total_count": 2, "jobs": [job(1), job(1)]}])

    def test_missing_final_page_refused(self):
        self.assert_rejected([{"total_count": 101, "jobs": [job(i) for i in range(1, 101)]}])

    def test_empty_final_page_refused(self):
        self.assert_rejected([{"total_count": 101, "jobs": [job(i) for i in range(1, 101)]},
                              {"total_count": 101, "jobs": []}])

    def test_partial_failure_never_warms_cache_and_can_retry(self):
        first = {"total_count": 101, "jobs": [job(i) for i in range(1, 101)]}
        self.assert_rejected([first, {"error": "API rate limit exceeded for installation"}])
        self.save([first, {"total_count": 101, "jobs": [job(101)]}])
        result = self.run_queries()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "101")
        self.assertEqual(len(self.calls()), 4)

    def test_foreign_attempt_or_run_and_nonterminal_jobs_refused(self):
        for changes in ({"run_id": 124}, {"run_attempt": 2}, {"status": "in_progress"},
                        {"conclusion": None}, {"id": 0}):
            with self.subTest(changes=changes):
                self.assert_rejected([{"total_count": 1, "jobs": [job(1) | changes]}])

    def test_invalid_total_shape_and_hard_cap_refused(self):
        for total in (None, "1", -1, 0.5, 1001):
            with self.subTest(total=total):
                self.assert_rejected([{"total_count": total, "jobs": [job(1)]}])

    def test_missing_total_refused(self):
        self.assert_rejected([{"jobs": []}])

    def test_multiple_json_roots_refused(self):
        page = json.dumps({"total_count": 1, "jobs": [job(1)]})
        self.assert_rejected([{"raw": page + "\n" + page}])

    def test_integer_valued_numeric_notation_is_canonicalized(self):
        for number, count in (("1.0", 1), ("1e2", 100)):
            with self.subTest(number=number):
                (self.cache / "jobs-attempt-1.json").unlink(missing_ok=True)
                raw = '{"total_count":' + number + ',"jobs":' + json.dumps([job(i) for i in range(1, count + 1)]) + '}'
                self.save([{"raw": raw}])
                result = self.run_queries()
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout.strip(), str(count))

    def test_names_conclusions_and_steps_must_be_typed_terminal_values(self):
        step = {"name": "Build", "status": "completed", "conclusion": "success", "number": 1}
        invalid = [{"name": None}, {"name": ""}, {"name": "injected\nname"}, {"conclusion": "unknown"},
                   {"steps": None}, {"steps": {}}, {"steps": [None]},
                   *({"steps": [step | change]} for change in (
                       {"name": None}, {"status": "in_progress"}, {"conclusion": "unknown"}, {"number": 0}))]
        for change in invalid:
            with self.subTest(change=change):
                self.assert_rejected([{"total_count": 1, "jobs": [job(1) | change]}])

    def test_valid_skipped_job_can_have_no_steps(self):
        self.save([{"total_count": 1, "jobs": [job(1) | {"conclusion": "skipped", "steps": []}]}])
        result = self.run_queries()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_failed_stdout_is_never_echoed_or_cached(self):
        self.save([{"error": "HTTP 403", "failed_stdout": "PRIVATE_PARTIAL_RESPONSE"}])
        result = self.run_queries()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("HTTP 403", result.stderr)
        self.assertNotIn("PRIVATE_PARTIAL_RESPONSE", result.stderr + result.stdout)
        self.assertFalse((self.cache / "jobs-attempt-1.json").exists())

    def test_legacy_fixture_scheduler_keeps_repeated_noninventory_calls(self):
        stub = self.root / "legacy-gh"
        stub.write_text(SOURCE.split("cat > \"${tmp}/gh\" <<'STUB'\n", 1)[1].split("\nSTUB\n", 1)[0])
        stub.chmod(0o755)
        (self.root / "inventory.json").write_text(json.dumps({"jobs": [job(1)]}))
        (self.root / "metadata.json").write_text(json.dumps({"head_sha": "unchanged"}))
        script = self.root / "script"
        script.write_text("0|@inventory\n0|@inventory\n0|@metadata\n0|@metadata\n")
        env = {**self.env, "CALL_LOG": str(self.root / "legacy-calls"),
               "STUB_SCRIPT": str(script), "FIXTURE_DIR": str(self.root)}
        subprocess.run([str(stub), "api", "repos/Example/Repo/actions/runs/123/attempts/1/jobs?per_page=100&page=1"],
                       env=env, capture_output=True, check=True)
        for _ in range(2):
            result = subprocess.run([str(stub), "api", "repos/Example/Repo/actions/runs/123", "--jq", ".head_sha"],
                                    env=env, capture_output=True, text=True, check=True)
            self.assertEqual(result.stdout.strip(), "unchanged")
        self.assertEqual((self.root / "legacy-calls.n").read_text().strip(), "3")
        self.assertEqual((self.root / "legacy-calls.position").read_text().strip(), "4")

    def test_complete_empty_inventory_is_not_a_missing_page(self):
        self.save([{"total_count": 0, "jobs": []}])
        result = self.run_queries()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "0")

    def test_cached_snapshot_cannot_cross_run_or_repository(self):
        self.save([{"total_count": 1, "jobs": [job(1)]}])
        result = self.run_queries("jobs_query '.jobs | length'; RUN_ID=124; jobs_query '.jobs | length'")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(self.calls()), 1)
        result = self.run_queries("GITHUB_REPOSITORY=Other/Repo; jobs_query '.jobs | length'")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(self.calls()), 1)


if __name__ == "__main__":
    unittest.main()
