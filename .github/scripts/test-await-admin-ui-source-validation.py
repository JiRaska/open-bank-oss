#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Regression checks for source admission before OIDC and image building."""

import importlib.util
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("await-admin-ui-source-validation.py")
spec = importlib.util.spec_from_file_location("source_validation", SCRIPT)
validation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validation)

REPO = "owner/repository"
SHA = "a" * 40
OTHER_SHA = "b" * 40


def run(workflow, *, sha=SHA, run_id=1, status="completed", conclusion="success",
        event="push", branch="main", path=None, repository=REPO):
    return {"id": run_id, "head_sha": sha, "head_branch": branch, "event": event,
            "path": path or f".github/workflows/{workflow}",
            "repository": {"full_name": repository}, "status": status,
            "conclusion": conclusion}


def job(name, *, status="completed", conclusion="success"):
    return {"name": name, "status": status, "conclusion": conclusion}


class FakeAPI:
    def __init__(self):
        self.ui_sha = SHA
        self.runs = {
            "ci.yml": [run("ci.yml")],
            "pact-drift-check.yml": [run("pact-drift-check.yml", run_id=2)],
        }
        self.jobs = {
            1: [job("Admin UI build")],
            2: [job(name) for name in validation.WORKFLOWS["pact-drift-check.yml"]],
        }

    def __call__(self, path):
        if "/commits?" in path:
            return [{"sha": self.ui_sha}] if self.ui_sha else []
        if "/workflows/" in path:
            workflow = path.split("/workflows/", 1)[1].split("/runs?", 1)[0]
            return {"workflow_runs": self.runs[workflow]}
        run_id = int(path.split("/runs/", 1)[1].split("/jobs?", 1)[0])
        return {"jobs": self.jobs[run_id]}


class SourceValidationTest(unittest.TestCase):
    def setUp(self):
        self.api = FakeAPI()

    def verify(self):
        validation.wait_for_validation(self.api, REPO, SHA, max_wait=0)

    def test_exact_source_ui_and_pact_are_admitted(self):
        self.verify()

    def test_governance_or_image_pin_refresh_uses_latest_ui_ancestor(self):
        self.api.ui_sha = OTHER_SHA
        self.api.runs["pact-drift-check.yml"] = [run("pact-drift-check.yml", sha=OTHER_SHA, run_id=2)]
        self.verify()

    def test_later_main_pact_publication_substitutes_for_missing_ancestor_run(self):
        self.api.ui_sha = OTHER_SHA
        self.verify()

    def test_pending_exact_source_pact_cannot_use_older_ancestor_success(self):
        self.api.ui_sha = OTHER_SHA
        self.api.runs["pact-drift-check.yml"] = [
            run("pact-drift-check.yml", sha=OTHER_SHA, run_id=2),
            run("pact-drift-check.yml", sha=SHA, run_id=3, status="in_progress", conclusion=None),
        ]
        with self.assertRaises(TimeoutError):
            self.verify()

    def test_failed_exact_source_pact_cannot_use_older_ancestor_success(self):
        self.api.ui_sha = OTHER_SHA
        self.api.runs["pact-drift-check.yml"] = [
            run("pact-drift-check.yml", sha=OTHER_SHA, run_id=2),
            run("pact-drift-check.yml", sha=SHA, run_id=3, conclusion="failure"),
        ]
        with self.assertRaises(RuntimeError):
            self.verify()

    def test_governance_refresh_cannot_bypass_failed_ui_pact(self):
        self.api.ui_sha = OTHER_SHA
        self.api.runs["pact-drift-check.yml"] = [run(
            "pact-drift-check.yml", sha=OTHER_SHA, run_id=2, conclusion="failure")]
        with self.assertRaises(RuntimeError):
            self.verify()

    def test_missing_ui_ancestor_fails_closed(self):
        self.api.ui_sha = None
        with self.assertRaises(RuntimeError):
            self.verify()

    def test_wrong_sha_or_repository_or_workflow_is_missing_evidence(self):
        for replacement in (
            run("ci.yml", sha=OTHER_SHA),
            run("ci.yml", repository="other/repository"),
            run("other.yml"),
            run("ci.yml", event="pull_request"),
        ):
            with self.subTest(replacement=replacement):
                self.api.runs["ci.yml"] = [replacement]
                with self.assertRaises(TimeoutError):
                    self.verify()

    def test_pending_evidence_waits_and_is_bounded(self):
        self.api.runs["ci.yml"] = [run("ci.yml", status="in_progress", conclusion=None)]
        with self.assertRaises(TimeoutError):
            self.verify()

    def test_failed_or_cancelled_run_fails_closed(self):
        for outcome in ("failure", "cancelled"):
            with self.subTest(outcome=outcome):
                self.api.runs["ci.yml"] = [run("ci.yml", conclusion=outcome)]
                with self.assertRaises(RuntimeError):
                    self.verify()

    def test_successful_aggregate_cannot_substitute_for_cancelled_ui_job(self):
        self.api.jobs[1] = [job("Admin UI"), job("Admin UI build", conclusion="cancelled")]
        with self.assertRaises(RuntimeError):
            self.verify()

    def test_duplicate_newer_pending_run_blocks_older_success(self):
        self.api.runs["ci.yml"].append(run("ci.yml", run_id=3, status="queued", conclusion=None))
        with self.assertRaises(TimeoutError):
            self.verify()

    def test_duplicate_job_identity_fails_closed(self):
        self.api.jobs[1].append(job("Admin UI build"))
        with self.assertRaises(RuntimeError):
            self.verify()

    def test_pact_publication_must_complete(self):
        self.api.jobs[2][1]["conclusion"] = "skipped"
        with self.assertRaises(RuntimeError):
            self.verify()

    def test_workflow_wiring_precedes_privileged_build(self):
        workflow = (SCRIPT.parents[1] / "workflows" / "admin-ui-deploy.yml").read_text()
        source = workflow.split("  deploy-source:", 1)[1].split("  build-push:", 1)[0]
        self.assertIn("actions: read", source)
        self.assertIn("steps.source.outputs.proceed == 'true'", source)
        self.assertIn("await-admin-ui-source-validation.py", source)
        self.assertLess(source.index("- id: early_source"),
                        source.index("- name: Coalesce evidence-only merge bursts"))
        self.assertLess(source.index("- name: Coalesce evidence-only merge bursts"),
                        source.index("- id: source"))
        self.assertIn("steps.early_source.outputs.proceed == 'true'", source)
        self.assertEqual(source.count("authorize-admin-ui-deploy-source.sh"), 2)
        self.assertIn("needs: deploy-source", workflow.split("  build-push:", 1)[1])

    def test_image_pin_only_main_advance_and_duplicate_source(self):
        guard = SCRIPT.with_name("authorize-admin-ui-deploy-source.sh")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            git_env = {key: value for key, value in os.environ.items()
                       if not key.startswith("GIT_")}

            def git(*args):
                return subprocess.check_output(("git", *args), cwd=root, env=git_env,
                                               text=True).strip()

            git("init", "-q")
            git("config", "user.name", "Test")
            git("config", "user.email", "test@example.invalid")
            git("config", "commit.gpgsign", "false")
            ui = root / "openbank-admin-ui" / "src"
            ui.mkdir(parents=True)
            (ui / "example.ts").write_text("export const ready = true;\n")
            git("add", "openbank-admin-ui/src/example.ts")
            git("commit", "-qm", "fix(admin-ui): validated source")
            source_sha = git("rev-parse", "HEAD")

            manifest = root / "openbank-infra/gitops/components/admin-ui/admin-ui.yaml"
            manifest.parent.mkdir(parents=True)
            manifest.write_text("image: verified\n")
            git("add", "openbank-infra/gitops/components/admin-ui/admin-ui.yaml")
            git("commit", "-qm", "chore(admin-ui): deploy verified image")
            main_sha = git("rev-parse", "HEAD")

            def admit(source, duplicate, current_main=main_sha):
                result = subprocess.run(
                    ("bash", str(guard), source, current_main, "workflow_run", duplicate),
                    cwd=root, env=git_env, capture_output=True, text=True, check=True,
                )
                return result.stdout.strip()

            self.assertEqual(admit(source_sha, "false"), "true")
            self.assertEqual(admit(source_sha, "true"), "false")
            self.assertEqual(admit(main_sha, "false"), "false")

            other = root / "openbank-infra/gitops/components/payments/payments-services.yaml"
            other.parent.mkdir(parents=True)
            other.write_text("image: newer-service-pin\n")
            git("add", "openbank-infra/gitops/components/payments/payments-services.yaml")
            git("commit", "-qm", "chore(gitops): advance a different build input")
            newer_main = git("rev-parse", "HEAD")
            self.assertEqual(admit(source_sha, "false", newer_main), "false")


if __name__ == "__main__":
    unittest.main()
