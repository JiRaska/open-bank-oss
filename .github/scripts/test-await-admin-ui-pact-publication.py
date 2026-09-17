#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Negative and positive proofs for exact-main Pact publication ordering."""

import importlib.util
from pathlib import Path
import re
import subprocess
import textwrap
import unittest

spec = importlib.util.spec_from_file_location("waiter", Path(__file__).with_name("await-admin-ui-pact-publication.py"))
waiter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(waiter)

REPO = "example/open-bank-oss"
SHA = "a" * 40


def run(*, sha=SHA, status="completed", conclusion="success"):
    return {"id": 123, "head_sha": sha, "event": "push", "status": status,
            "conclusion": conclusion}


class PublicationWaitTest(unittest.TestCase):
    def fetch(self, runs, jobs=None):
        def get(path):
            if "/workflows/pact-drift-check.yml/runs?" in path:
                self.assertIn("head_sha=" + SHA, path)
                self.assertIn("event=push", path)
                return {"workflow_runs": runs}
            self.assertEqual(path, f"repos/{REPO}/actions/runs/123/jobs?filter=latest&per_page=100")
            return {"jobs": jobs or []}
        return get

    def test_exact_success_requires_both_jobs(self):
        jobs = [{"name": waiter.DRIFT_JOB, "conclusion": "success"},
                {"name": waiter.PUBLISH_JOB, "conclusion": "success"}]
        self.assertTrue(waiter.poll_once(self.fetch([run(), run(sha="b" * 40)], jobs), REPO, SHA))

    def test_missing_or_in_progress_run_never_succeeds(self):
        for runs in ([], [run(sha="b" * 40)], [run(status="in_progress", conclusion=None)]):
            with self.subTest(runs=runs):
                self.assertFalse(waiter.poll_once(self.fetch(runs), REPO, SHA))

    def test_failed_workflow_or_skipped_publication_fails_closed(self):
        with self.assertRaisesRegex(RuntimeError, "did not succeed"):
            waiter.poll_once(self.fetch([run(conclusion="failure")]), REPO, SHA)
        jobs = [{"name": waiter.DRIFT_JOB, "conclusion": "success"},
                {"name": waiter.PUBLISH_JOB, "conclusion": "skipped"}]
        with self.assertRaisesRegex(RuntimeError, waiter.PUBLISH_JOB):
            waiter.poll_once(self.fetch([run()], jobs), REPO, SHA)

    def test_missing_job_is_pending_then_bounded_timeout(self):
        now = [0]
        def clock(): return now[0]
        def sleep(seconds): now[0] += seconds
        with self.assertRaisesRegex(TimeoutError, "not proven"):
            waiter.wait_for_publication(self.fetch([run()], [{"name": waiter.DRIFT_JOB,
                                                               "conclusion": "success"}]),
                                        REPO, SHA, clock=clock, sleep=sleep, max_wait=120)
        self.assertEqual(now[0], 120)

    def test_api_error_does_not_turn_green(self):
        def unavailable(_): raise OSError("API unavailable")
        with self.assertRaises(OSError):
            waiter.wait_for_publication(unavailable, REPO, SHA, max_wait=120)

    def test_workflow_waits_off_arc_and_aggregates_failure(self):
        workflows = Path(__file__).parents[1] / "workflows"
        services = (workflows / "services-ci.yml").read_text()
        pact = (workflows / "pact-drift-check.yml").read_text()
        def job(text, name):
            match = re.search(rf"(?ms)^  {name}:\n(.*?)(?=^  [a-z][a-z-]*:\n|\Z)", text)
            self.assertIsNotNone(match, name)
            return match.group(1)
        wait = job(services, "publication-ready")
        build = job(services, "build")
        all_green = job(services, "all-green")
        self.assertIn("runs-on: ubuntu-latest", wait)
        self.assertIn("admin-ui-pact-changed == 'true'", wait)
        self.assertIn("python3 .github/scripts/await-admin-ui-pact-publication.py", wait)
        self.assertIn("needs: [changes, publication-ready]", build)
        self.assertIn("needs.publication-ready.result == 'success'", build)
        self.assertIn("needs.publication-ready.result == 'skipped'", build)
        self.assertIn("needs: [changes, publication-ready, build, verification-metadata]", all_green)
        self.assertIn('needs.publication-ready.result }}" != "success"', all_green)
        self.assertNotIn("verify-admin-ui-providers:", pact)
        self.assertIn("needs: drift-check", job(pact, "publish-admin-ui"))

    def test_required_service_aggregate_rejects_missing_publication(self):
        services = (Path(__file__).parents[1] / "workflows" / "services-ci.yml").read_text()
        match = re.search(r"(?ms)^      - name: Verify no service failed\n        run: \|\n(.*?)(?=^  [a-z][a-z-]*:|\Z)", services)
        self.assertIsNotNone(match)
        template = textwrap.dedent(match.group(1))
        common = {"needs.changes.result": "success",
                  "needs.build.result": "success",
                  "needs.verification-metadata.result": "skipped"}
        for changed, publication, expected_success in [
            ("true", "success", True), ("true", "failure", False),
            ("true", "skipped", False), ("", "skipped", True),
        ]:
            with self.subTest(changed=changed, publication=publication):
                values = dict(common, **{"needs.changes.outputs.admin-ui-pact-changed": changed,
                                         "needs.publication-ready.result": publication})
                script = template
                for key, value in values.items():
                    script = script.replace("${{ " + key + " }}", value)
                self.assertNotIn("${{", script)
                result = subprocess.run(["bash", "-euo", "pipefail", "-c", script],
                                        capture_output=True, text=True, check=False)
                self.assertEqual(result.returncode == 0, expected_success)


if __name__ == "__main__":
    unittest.main()
