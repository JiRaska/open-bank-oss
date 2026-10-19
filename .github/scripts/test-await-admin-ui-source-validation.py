#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Regression checks for source admission before OIDC and image building."""

import importlib.util
import os
import subprocess
import tempfile
import unittest
import urllib.parse
from pathlib import Path

SCRIPT = Path(__file__).with_name("await-admin-ui-source-validation.py")
spec = importlib.util.spec_from_file_location("source_validation", SCRIPT)
validation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validation)

REPO = "owner/repository"
SHA = "a" * 40
OTHER_SHA = "b" * 40
FIXED_SHA = "c" * 40
UI_EDIT_SHA = "d" * 40


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
        self.calls = []
        self.ui_sha = SHA
        # Latest openbank-admin-ui/ change per commit; SHA defaults to self.ui_sha.
        self.ui_changes = {}
        self.runs = {
            "ci.yml": [run("ci.yml")],
            "services-ci.yml": [run("services-ci.yml", run_id=3)],
            "pact-drift-check.yml": [run("pact-drift-check.yml", run_id=2)],
        }
        self.jobs = {
            1: [job("Admin UI build")],
            2: [job(name) for name in validation.WORKFLOWS["pact-drift-check.yml"]],
            3: [job(name) for name in validation.WORKFLOWS["services-ci.yml"]],
        }
        self.changed_files = []

    def __call__(self, path):
        self.calls.append(path)
        if "/commits?" in path:
            head = path.split("sha=", 1)[1].split("&", 1)[0]
            ui_sha = self.ui_changes.get(head, self.ui_sha if head == SHA else head)
            return [{"sha": ui_sha}] if ui_sha else []
        if "/commits/" in path:
            return {"parents": [{"sha": OTHER_SHA}]}
        if "/compare/" in path:
            return {"files": [name if isinstance(name, dict) else {"filename": name}
                               for name in self.changed_files]}
        if "/workflows/" in path:
            workflow = path.split("/workflows/", 1)[1].split("/runs?", 1)[0]
            params = urllib.parse.parse_qs(urllib.parse.urlsplit(path).query)
            page = int(params.get("page", ["1"])[0])
            start = (page - 1) * 100
            runs = self.runs[workflow]
            return {"workflow_runs": runs[start:start + 100], "total_count": len(runs)}
        run_id = int(path.split("/runs/", 1)[1].split("/jobs?", 1)[0])
        params = urllib.parse.parse_qs(urllib.parse.urlsplit(path).query)
        page = int(params.get("page", ["1"])[0])
        start = (page - 1) * 100
        jobs = self.jobs[run_id]
        return {"jobs": jobs[start:start + 100], "total_count": len(jobs)}


class SourceValidationTest(unittest.TestCase):
    def setUp(self):
        self.api = FakeAPI()

    def verify(self):
        validation.wait_for_validation(self.api, REPO, SHA, max_wait=0)

    def test_pact_readiness_uses_workflow_path_source_and_github_glob_semantics(self):
        patterns = validation.pact_push_path_patterns(
            SCRIPT.parents[1] / "workflows" / "pact-drift-check.yml")
        self.assertIn("!openbank-libs/governance/**", patterns)
        self.assertTrue(validation.path_matches_pact_push(
            ["openbank-admin-ui/src/page.tsx"], patterns))
        self.assertFalse(validation.path_matches_pact_push(
            ["openbank-libs/governance/rules.yaml"], patterns))
        self.assertTrue(validation.path_matches_pact_push(
            ["openbank-libs/src/test/kotlin/foo/contract/ExamplePactConsumerTest.kt"], patterns))
        self.assertFalse(validation.github_path_glob_matches(
            "nested/module.gradle.kts", "*.gradle.kts"))
        self.assertTrue(validation.github_path_glob_matches(
            "module/src/test/kotlin/foo/contract/ExamplePactConsumerTest.kt",
            "**/src/test/kotlin/**/contract/*PactConsumerTest.kt"))

    def test_pact_path_parser_fails_closed_on_unsupported_workflow_syntax(self):
        with tempfile.TemporaryDirectory() as directory:
            workflow = Path(directory) / "pact-drift-check.yml"
            workflow.write_text("on:\n  push:\n    paths:\n      - 'openbank-admin-ui/**'\n")
            with self.assertRaises(RuntimeError):
                validation.pact_push_path_patterns(workflow)
            workflow.write_text('on:\n  push:\n    paths:\n      - "openbank-admin-ui/[ab]/**"\n')
            with self.assertRaises(RuntimeError):
                validation.pact_push_path_patterns(workflow)

    def test_exact_source_ui_and_pact_are_admitted(self):
        self.verify()

    def test_one_shot_readiness_waits_for_every_required_completion(self):
        self.api.runs["services-ci.yml"] = [
            run("services-ci.yml", status="in_progress", conclusion=None)]
        self.assertFalse(validation.validation_ready(self.api, REPO, SHA))
        self.api.runs["services-ci.yml"] = [run("services-ci.yml", run_id=3)]
        self.assertTrue(validation.validation_ready(self.api, REPO, SHA))

    def test_services_ci_requires_successful_all_green_not_every_matrix_job(self):
        # The aggregate proves the selected scope passed. A skipped individual
        # matrix job is valid when the detector did not select that module.
        self.api.jobs[3] = [job("Detect changed services"), job("all-green")]
        self.api.jobs[3].append(job("build (unused-module)", conclusion="skipped"))
        self.assertTrue(validation.validation_ready(self.api, REPO, SHA))
        self.api.jobs[3] = [job("Detect changed services"), job("all-green", conclusion="failure")]
        with self.assertRaises(RuntimeError):
            validation.validation_ready(self.api, REPO, SHA)

    def test_services_ci_aggregate_is_found_after_first_jobs_api_page(self):
        self.api.jobs[3] = [job("Detect changed services")]
        self.api.jobs[3].extend(job(f"build (module-{index})") for index in range(100))
        self.api.jobs[3].append(job("all-green"))
        self.assertTrue(validation.validation_ready(self.api, REPO, SHA))

    def test_pact_history_uses_newest_bounded_page_without_scanning_old_runs(self):
        # Workflow run lists are deliberately bounded to GitHub's newest 100
        # records. The latest relevant successful publication remains usable
        # even when the workflow has a much longer history.
        latest = run("pact-drift-check.yml", sha=OTHER_SHA, run_id=1000)
        self.api.runs["pact-drift-check.yml"] = [latest] + [
            run("pact-drift-check.yml", sha=f"{index:040x}", run_id=999 - index)
            for index in range(150)
        ]
        self.api.ui_sha = OTHER_SHA
        self.api.ui_changes[OTHER_SHA] = OTHER_SHA
        self.api.jobs[1000] = [
            job(name) for name in validation.WORKFLOWS["pact-drift-check.yml"]
        ]

        self.assertTrue(validation.validation_ready(self.api, REPO, SHA))
        run_list_calls = [path for path in self.api.calls if "/workflows/" in path]
        self.assertTrue(run_list_calls)
        self.assertTrue(all("page" not in urllib.parse.parse_qs(
            urllib.parse.urlsplit(path).query) for path in run_list_calls))

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

    def test_workflow_run_readiness_does_not_fallback_while_expected_pact_is_not_visible(self):
        self.api.changed_files = ["openbank-admin-ui/src/page.tsx"]
        self.api.runs["pact-drift-check.yml"] = [
            run("pact-drift-check.yml", sha=OTHER_SHA, run_id=2)]
        self.assertFalse(validation.validation_ready(self.api, REPO, SHA))
        # The later Pact completion becomes the retry event and exact-source
        # evidence now admits the same source.
        self.api.runs["pact-drift-check.yml"].append(
            run("pact-drift-check.yml", sha=SHA, run_id=4))
        self.api.jobs[4] = [job(name) for name in validation.WORKFLOWS["pact-drift-check.yml"]]
        self.assertTrue(validation.validation_ready(self.api, REPO, SHA))

    def test_renamed_away_pact_trigger_path_still_requires_exact_run(self):
        self.api.changed_files = [{
            "filename": "docs/renamed-page.md",
            "previous_filename": "openbank-admin-ui/src/page.tsx",
        }]
        self.api.runs["pact-drift-check.yml"] = [
            run("pact-drift-check.yml", sha=OTHER_SHA, run_id=2)]
        self.assertFalse(validation.validation_ready(self.api, REPO, SHA))

    def test_workflow_run_readiness_uses_same_tree_fallback_for_non_pact_change(self):
        self.api.changed_files = ["docs/adr/ADR-0001.md"]
        self.api.ui_sha = OTHER_SHA
        self.api.runs["pact-drift-check.yml"] = [
            run("pact-drift-check.yml", sha=OTHER_SHA, run_id=2)]
        self.assertTrue(validation.validation_ready(self.api, REPO, SHA))

    def test_workflow_run_readiness_blocks_failed_latest_exact_pact(self):
        self.api.runs["pact-drift-check.yml"] = [
            run("pact-drift-check.yml", sha=SHA, run_id=3, conclusion="failure"),
            run("pact-drift-check.yml", sha=OTHER_SHA, run_id=2),
        ]
        with self.assertRaises(RuntimeError):
            validation.validation_ready(self.api, REPO, SHA)

    def test_workflow_run_readiness_rejects_wrong_sha_and_untrusted_workflow_runs(self):
        self.api.runs["ci.yml"] = [run("ci.yml", sha=OTHER_SHA)]
        self.assertFalse(validation.validation_ready(self.api, REPO, SHA))
        self.api.runs["ci.yml"] = [run("ci.yml", branch="feature")]
        self.assertFalse(validation.validation_ready(self.api, REPO, SHA))

    def test_readiness_requires_admin_ui_job_even_if_aggregate_passes(self):
        self.api.jobs[1] = [job("Admin UI", conclusion="success"),
                            job("Admin UI build", conclusion="skipped")]
        with self.assertRaises(RuntimeError):
            validation.validation_ready(self.api, REPO, SHA)

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

    def failed_ancestor_then(self, head, **later):
        """Ancestor OTHER_SHA failed; a later main Pact run happened on `head`."""
        self.api.ui_sha = OTHER_SHA
        self.api.runs["pact-drift-check.yml"] = [
            run("pact-drift-check.yml", sha=OTHER_SHA, run_id=2, conclusion="failure"),
            run("pact-drift-check.yml", sha=head, run_id=4, **later),
        ]
        self.api.jobs[4] = [job(name) for name in validation.WORKFLOWS["pact-drift-check.yml"]]

    def test_later_pact_run_on_same_ui_tree_supersedes_failed_ancestor(self):
        # 2026-10-18: ee19ade4b's Pact run failed on a CI defect fixed by a later main
        # commit that did not touch openbank-admin-ui/; its green run proves the same tree.
        self.failed_ancestor_then(FIXED_SHA)
        self.api.ui_changes[FIXED_SHA] = OTHER_SHA
        self.verify()

    def test_later_pact_run_after_an_admin_ui_change_does_not_count(self):
        # The descendant changed openbank-admin-ui/, so its green run is about another tree.
        self.failed_ancestor_then(UI_EDIT_SHA)
        with self.assertRaises(RuntimeError):
            self.verify()

    def test_pending_later_same_tree_run_waits_instead_of_using_older_success(self):
        self.api.ui_sha = OTHER_SHA
        self.api.ui_changes[FIXED_SHA] = OTHER_SHA
        self.api.runs["pact-drift-check.yml"] = [
            run("pact-drift-check.yml", sha=OTHER_SHA, run_id=2),
            run("pact-drift-check.yml", sha=FIXED_SHA, run_id=4, status="in_progress", conclusion=None),
        ]
        with self.assertRaises(TimeoutError):
            self.verify()

    def test_failed_later_same_tree_run_is_not_bypassed_by_older_success(self):
        self.api.ui_sha = OTHER_SHA
        self.api.ui_changes[FIXED_SHA] = OTHER_SHA
        self.api.runs["pact-drift-check.yml"] = [
            run("pact-drift-check.yml", sha=OTHER_SHA, run_id=2),
            run("pact-drift-check.yml", sha=FIXED_SHA, run_id=4, conclusion="failure"),
        ]
        with self.assertRaises(RuntimeError):
            self.verify()

    def test_same_tree_run_on_another_branch_does_not_count(self):
        self.failed_ancestor_then(FIXED_SHA, branch="feature")
        self.api.ui_changes[FIXED_SHA] = OTHER_SHA
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
        trigger = workflow.split("on:", 1)[1].split("permissions:", 1)[0]
        self.assertIn('workflows: ["Services CI", "CI", "Pact drift check"]', trigger)
        self.assertNotIn("\n  push:", trigger)
        source = workflow.split("  deploy-source:", 1)[1].split("  build-push:", 1)[0]
        self.assertIn("actions: read", source)
        self.assertIn("steps.source.outputs.proceed == 'true'", source)
        self.assertIn("await-admin-ui-source-validation.py", source)
        self.assertIn("ref: refs/heads/main", source)
        self.assertNotIn("ref: ${{ github.event.workflow_run.head_sha }}", source)
        self.assertIn("--check", source)
        self.assertIn("EVENT_NAME", source)
        self.assertLess(source.index("Require exact-source Admin UI validation"),
                        source.index("Recheck deploy source after validation"))
        self.assertIn("steps.post_validation.outputs.proceed", source)
        self.assertIn("steps.validation.outputs.proceed == 'true'", source)
        self.assertNotIn("sleep 600", source)

        build = workflow.split("  build-push:", 1)[1]
        self.assertIn("needs: deploy-source", build)
        self.assertIn("ref: ${{ needs.deploy-source.outputs.source_sha }}", build)
        admission = build.index("Recheck source at serialized build entry")
        self.assertLess(admission, build.index("Configure AWS credentials (GitHub OIDC)"))
        self.assertLess(admission, build.index("Build, collect, push"))
        self.assertLess(admission, build.index("Open GitOps PR (auto-merge)"))
        steps = build.split("    steps:", 1)[1]
        step_blocks = steps.split("\n      - ")[1:]
        gated = False
        for block in step_blocks:
            if block.startswith("id: build_admission"):
                gated = True
                continue
            if gated:
                self.assertIn("steps.build_admission.outputs.proceed == 'true'", block.split("\n\n", 1)[0])

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

            def admit(source, duplicate, event="workflow_run", current_main=main_sha):
                result = subprocess.run(
                    ("bash", str(guard), source, current_main, event, duplicate),
                    cwd=root, env=git_env, capture_output=True, text=True, check=True,
                )
                return result.stdout.strip()

            self.assertEqual(admit(source_sha, "false"), "true")
            self.assertEqual(admit(source_sha, "true"), "false")
            self.assertEqual(admit(source_sha, "true", event="workflow_dispatch"), "true")
            self.assertEqual(admit(main_sha, "false"), "false")

            # This models main receiving new build-relevant work while exact-source
            # validation or the serialized build slot is pending. The same admission
            # predicate must reject it at both later boundaries.
            governance = root / "openbank-libs/governance/rules.yaml"
            governance.parent.mkdir(parents=True)
            governance.write_text("version: 2\n")
            git("add", "openbank-libs/governance/rules.yaml")
            git("commit", "-qm", "docs(governance): update deploy inputs")
            newer_main = git("rev-parse", "HEAD")
            self.assertEqual(admit(source_sha, "false", current_main=newer_main), "false")

    def test_admission_rechecks_remote_main_and_open_pr_at_each_boundary(self):
        checker = SCRIPT.with_name("check-admin-ui-deploy-admission.sh")
        authorizer = SCRIPT.with_name("authorize-admin-ui-deploy-source.sh")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "repo"
            remote = Path(directory) / "origin.git"
            root.mkdir()
            subprocess.run(("git", "init", "--bare", "--initial-branch=main", str(remote)),
                           check=True, capture_output=True, text=True)
            git_env = {key: value for key, value in os.environ.items()
                       if not key.startswith("GIT_")}
            git_env.update({"GIT_AUTHOR_NAME": "Test", "GIT_AUTHOR_EMAIL": "test@example.invalid",
                            "GIT_COMMITTER_NAME": "Test", "GIT_COMMITTER_EMAIL": "test@example.invalid"})

            def git(*args):
                return subprocess.check_output(("git", *args), cwd=root, env=git_env,
                                               text=True).strip()

            git("init", "-b", "main", "-q")
            git("config", "user.name", "Test")
            git("config", "user.email", "test@example.invalid")
            git("config", "commit.gpgsign", "false")
            git("remote", "add", "origin", str(remote))
            ui = root / "openbank-admin-ui/src/example.ts"
            ui.parent.mkdir(parents=True)
            ui.write_text("export const ready = true;\n")
            git("add", "openbank-admin-ui/src/example.ts")
            git("commit", "-qm", "fix(admin-ui): validated source")
            source_sha = git("rev-parse", "HEAD")
            git("push", "-q", "origin", "main")

            for relative in (".github/scripts",):
                (root / relative).mkdir(parents=True, exist_ok=True)
            (root / ".github/scripts/authorize-admin-ui-deploy-source.sh").write_text(authorizer.read_text())
            (root / ".github/scripts/check-admin-ui-deploy-admission.sh").write_text(checker.read_text())
            manifest = root / "openbank-infra/gitops/components/admin-ui/admin-ui.yaml"
            manifest.parent.mkdir(parents=True)
            manifest.write_text("image: verified\n")
            git("add", "openbank-infra/gitops/components/admin-ui/admin-ui.yaml")
            git("commit", "-qm", "chore(admin-ui): deploy verified image")
            git("push", "-q", "origin", "main")

            gh_bin = Path(directory) / "bin"
            gh_bin.mkdir()
            gh = gh_bin / "gh"
            gh.write_text("#!/bin/sh\necho true\n")
            gh.chmod(0o755)
            output = Path(directory) / "github-output"
            env = {**git_env, "GITHUB_OUTPUT": str(output), "GITHUB_REPOSITORY": REPO,
                   "GH_TOKEN": "test", "PATH": f"{gh_bin}:{os.environ['PATH']}"}

            def check(event="workflow_run"):
                output.write_text("")
                subprocess.run(("bash", str(checker), source_sha, event), cwd=root, env=env,
                               check=True, capture_output=True, text=True)
                return output.read_text().strip()

            # A self-generated image pin leaves the validated source eligible.
            self.assertEqual(check(), "proceed=true")

            # A transient lookup error for the deploy branch is not equivalent to
            # the documented exit-2 "no matching ref" response.
            git_wrapper = Path(directory) / "git-wrapper"
            git_wrapper.mkdir()
            wrapped_git = git_wrapper / "git"
            real_git = subprocess.check_output(("which", "git"), text=True).strip()
            wrapped_git.write_text(
                "#!/bin/sh\n"
                f"for arg do [ \"$arg\" = \"refs/heads/chore/admin-ui-deploy-{source_sha}\" ] && exit 128; done\n"
                f"exec {real_git} \"$@\"\n"
            )
            wrapped_git.chmod(0o755)
            failed_lookup_env = {**env, "PATH": f"{git_wrapper}:{gh_bin}:{os.environ['PATH']}"}
            output.write_text("")
            failed_lookup = subprocess.run(
                ("bash", str(checker), source_sha, "workflow_run"), cwd=root,
                env=failed_lookup_env, capture_output=True, text=True,
            )
            self.assertNotEqual(failed_lookup.returncode, 0)
            self.assertEqual(output.read_text(), "")

            # A deploy branch with an open PR suppresses automatic duplicates, while
            # explicit manual dispatch keeps its existing override behavior.
            git("checkout", "-qb", f"chore/admin-ui-deploy-{source_sha}")
            git("push", "-q", "origin", f"chore/admin-ui-deploy-{source_sha}")
            git("checkout", "-q", "main")
            self.assertEqual(check(), "proceed=false")
            self.assertEqual(check("workflow_dispatch"), "proceed=true")

            # A build-relevant commit that arrives during validation or while this
            # run waits for the serialized image slot makes the same source stale.
            governance = root / "openbank-libs/governance/rules.yaml"
            governance.parent.mkdir(parents=True)
            governance.write_text("version: 2\n")
            git("add", "openbank-libs/governance/rules.yaml")
            git("commit", "-qm", "docs(governance): update deploy inputs")
            git("push", "-q", "origin", "main")
            self.assertEqual(check(), "proceed=false")


if __name__ == "__main__":
    unittest.main()
