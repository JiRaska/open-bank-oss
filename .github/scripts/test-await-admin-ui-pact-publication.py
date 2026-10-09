#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Negative and positive proofs for exact-main Pact publication ordering."""

import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
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
        self.assertIn("github.ref == 'refs/heads/main' && secrets.PACT_BROKER_PASSWORD || ''", build)
        self.assertIn("github.event_name == 'push' || github.event_name == 'workflow_dispatch'", build)
        self.assertIn("needs: [changes, publication-ready, build, verification-metadata]", all_green)
        self.assertIn('needs.publication-ready.result }}" != "success"', all_green)
        self.assertNotIn("verify-admin-ui-providers:", pact)
        self.assertIn("needs: drift-check", job(pact, "publish-admin-ui"))

    def test_required_service_aggregate_rejects_missing_publication(self):
        services = (Path(__file__).parents[1] / "workflows" / "services-ci.yml").read_text()
        match = re.search(r"(?ms)^      - name: Verify no service failed\n        run: \|\n(.*?)(?=^      - name:|^  [a-z][a-z-]*:|\Z)", services)
        self.assertIsNotNone(match)
        template = textwrap.dedent(match.group(1))
        common = {"needs.changes.result": "success", "needs.build.result": "success"}
        # The service aggregate also requires verification metadata for changed build files.
        # Exercise both independent prerequisites so a merge cannot make either gate vacuous.
        for changed, publication, modules, metadata, admission, expected_success in [
            ("true", "success", "", "skipped", "true", True),
            ("true", "failure", "", "skipped", "true", False),
            ("true", "skipped", "", "skipped", "true", False),
            ("", "skipped", "", "skipped", "true", True),
            ("", "skipped", "openbank-treasury-service", "skipped", "true", False),
            ("", "skipped", "openbank-treasury-service", "success", "true", True),
            ("", "skipped", "", "skipped", "false", False),
        ]:
            with self.subTest(changed=changed, publication=publication, modules=modules, metadata=metadata, admission=admission):
                values = dict(common, **{"needs.changes.outputs.admission": admission,
                                         "needs.changes.outputs.admin-ui-pact-changed": changed,
                                         "needs.changes.outputs.verification-modules": modules,
                                         "needs.verification-metadata.result": metadata,
                                         "needs.publication-ready.result": publication})
                script = template
                for key, value in values.items():
                    script = script.replace("${{ " + key + " }}", value)
                self.assertNotIn("${{", script)
                result = subprocess.run(["bash", "-euo", "pipefail", "-c", script],
                                        capture_output=True, text=True, check=False)
                self.assertEqual(result.returncode == 0, expected_success)

    def test_required_service_aggregate_audits_each_matrix_build(self):
        services = (Path(__file__).parents[1] / "workflows" / "services-ci.yml").read_text()
        match = re.search(
            r"(?ms)^      - name: Require every selected service build to pass\n.*?^        run: \|\n(.*?)(?=^  #|^  [a-z][a-z-]*:|\Z)",
            services,
        )
        self.assertIsNotNone(match)
        script = textwrap.dedent(match.group(1))
        python = re.search(r"(?s)python3 - <<'PY'\n(.*?)\nPY", script)
        self.assertIsNotNone(python)
        selected = ["openbank-account-service", "openbank-treasury-service"]
        account = "build (openbank-account-service) / openbank-account-service (build)"
        treasury = "build (openbank-treasury-service) / openbank-treasury-service (build)"
        for verdicts, expected_success in [
            ([(account, "success"), (treasury, "success")], True),
            ([(account, "success"), (treasury, "cancelled")], False),
            ([(account, "success")], False),
            ([(account, "success"), (treasury, "success"), (treasury, "success")], False),
            ([(account, "success"), (treasury, "success"), ("build (openbank-extra) / openbank-extra (build)", "success")], False),
        ]:
            with self.subTest(verdicts=verdicts), tempfile.TemporaryDirectory() as directory:
                jobs = Path(directory) / "jobs.ndjson"
                jobs.write_text("".join(json.dumps({"name": name, "conclusion": conclusion}) + "\n"
                                        for name, conclusion in verdicts))
                env = dict(os.environ, SERVICES_JSON=json.dumps(selected), JOB_FILE=str(jobs))
                result = subprocess.run(["python3", "-c", python.group(1)], env=env,
                                        capture_output=True, text=True, check=False)
                self.assertEqual(result.returncode == 0, expected_success, result.stderr)

    def test_required_service_aggregate_retries_partial_jobs_api_502(self):
        services = (Path(__file__).parents[1] / "workflows" / "services-ci.yml").read_text()
        match = re.search(
            r"(?ms)^      - name: Require every selected service build to pass\n.*?^        run: \|\n(.*?)(?=^  #|^  [a-z][a-z-]*:|\Z)",
            services,
        )
        self.assertIsNotNone(match)
        script = textwrap.dedent(match.group(1))
        for mode, expected_success, expected_calls in [
            ("recover", True, 2),
            ("persistent", False, 3),
            ("final", False, 1),
        ]:
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / "gh").write_text(textwrap.dedent("""\
                    #!/usr/bin/env python3
                    import os
                    from pathlib import Path
                    import sys

                    counter = Path(os.environ["COUNT_FILE"])
                    n = int(counter.read_text()) + 1 if counter.exists() else 1
                    counter.write_text(str(n))
                    account = '{"name":"build (openbank-account-service) / openbank-account-service (build)","conclusion":"success"}'
                    treasury = '{"name":"build (openbank-treasury-service) / openbank-treasury-service (build)","conclusion":"success"}'
                    if os.environ["GH_STUB_MODE"] == "final":
                        print("gh: Validation Failed (HTTP 422)", file=sys.stderr)
                        sys.exit(1)
                    if os.environ["GH_STUB_MODE"] == "persistent" or n == 1:
                        print(account)  # A failed page may already have emitted data.
                        print("gh: Server Error (HTTP 502)", file=sys.stderr)
                        sys.exit(1)
                    print(account)
                    print(treasury)
                    """))
                (root / "gh").chmod(0o755)
                (root / "sleep").write_text("#!/bin/sh\nexit 0\n")
                (root / "sleep").chmod(0o755)
                jobs = root / "jobs.ndjson"
                env = dict(os.environ, PATH=f"{root}:{os.environ['PATH']}", COUNT_FILE=str(root / "calls"),
                           GH_STUB_MODE=mode, JOB_FILE=str(jobs), GITHUB_REPOSITORY="example/repo",
                           GITHUB_RUN_ID="123", GITHUB_RUN_ATTEMPT="1",
                           SERVICES_JSON=json.dumps(["openbank-account-service", "openbank-treasury-service"]))
                result = subprocess.run(["bash", "-c", script], env=env, capture_output=True, text=True, check=False)
                self.assertEqual(result.returncode == 0, expected_success, result.stderr)
                self.assertEqual((root / "calls").read_text(), str(expected_calls))
                if expected_success:
                    self.assertEqual(len(jobs.read_text().splitlines()), 2)
                else:
                    self.assertFalse(jobs.exists())


if __name__ == "__main__":
    unittest.main()
