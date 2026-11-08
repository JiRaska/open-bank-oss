# SPDX-License-Identifier: Apache-2.0
"""Exercise the actual workflow commands with harmless synthetic provider output."""
import json
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CANARY = "SYNTHETIC_PRIVATE_PROVIDER_OUTPUT_7c5a"


class WorkflowOutputTest(unittest.TestCase):
    def step_run(self, job, step_name):
        # Extract only the explicitly indented workflow run blocks used here.
        # Fail closed on missing/ambiguous structure; no YAML dependency is needed
        # before the workflow's credential setup.
        source = (ROOT / ".github/workflows/platform-tofu.yml").read_text()
        jobs = re.findall(rf"^  {re.escape(job)}:\n(.*?)(?=^  [\w-]+:|\Z)",
                          source, re.MULTILINE | re.DOTALL)
        self.assertEqual(len(jobs), 1)
        steps = re.findall(rf"^      - name: {re.escape(step_name)}\n(.*?)(?=^      - |\Z)",
                           jobs[0], re.MULTILINE | re.DOTALL)
        self.assertEqual(len(steps), 1)
        run = re.search(r"^        run: (.*)$", steps[0], re.MULTILINE)
        self.assertIsNotNone(run)
        if run.group(1) != "|":
            return run.group(1)
        lines = steps[0][run.end():].splitlines()
        return "\n".join(line[10:] for line in lines if line.startswith("          "))

    def invoke(self, job, step_name, code, scope="platform", invalid_plan=False):
        run = self.step_run(job, step_name)
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            tofu = base / "tofu"
            tofu.write_text('#!/bin/sh\nprintf "%s\\n" "$FAKE_STDOUT"\nprintf "%s\\n" "$CANARY" >&2\n'
                            'case "$*" in *-out=tfplan*) printf synthetic > tfplan;; esac\nexit "$FAKE_EXIT"\n')
            tofu.chmod(0o700)
            summary = base / "summary"
            events = [{"type": "version", "ui": "1.2"},
                      {"type": "change_summary", "changes":
                       {"operation": "plan", "add": 0, "change": 0, "remove": 0}}]
            output = CANARY if scope == "platform" or invalid_plan else "\n".join(map(json.dumps, events))
            env = dict(os.environ, PATH=f"{base}:{os.environ['PATH']}", FAKE_STDOUT=output,
                       CANARY=CANARY, FAKE_EXIT=str(code), RUNNER_TEMP=tmp,
                       GITHUB_WORKSPACE=str(ROOT), GITHUB_STEP_SUMMARY=str(summary),
                       ENV_DIR="synthetic", PLAN_SCOPE=scope)
            if scope == "admin-ui-evidence" and step_name == "tofu apply (saved plan)":
                (base / "tfplan").write_text("synthetic")
                import hashlib
                digest = hashlib.sha256(b"synthetic").hexdigest()
                (base / "tfplan.sha256").write_text(digest + "  tfplan\n")
            result = subprocess.run(["bash", "-eo", "pipefail", "-c", run],
                                    cwd=base, env=env, capture_output=True, text=True, timeout=30, check=False)
            public = result.stdout + result.stderr + (summary.read_text() if summary.exists() else "")
            self.assertNotIn(CANARY, public)
            for log in base.glob("private-platform-*.log"):
                self.assertEqual(log.stat().st_mode & 0o777, 0o600)
            return result.returncode

    def test_init_output_is_private_even_on_failure(self):
        for job in ("plan", "apply"):
            for code in (0, 1):
                with self.subTest(job=job, code=code):
                    self.assertEqual(self.invoke(job, "tofu init", code), code)

    def test_preview_preserves_detailed_exit_semantics(self):
        for code in (0, 1, 2, 127):
            with self.subTest(code=code):
                expected = 0 if code in (0, 2) else code
                self.assertEqual(self.invoke("plan", "tofu plan (read-only, no lock)", code), expected)

    def test_saved_plan_output_is_private(self):
        for code in (0, 1):
            with self.subTest(code=code):
                self.assertEqual(self.invoke("apply", "tofu plan -out", code), code)

    def test_apply_output_is_private(self):
        for code in (0, 1):
            with self.subTest(code=code):
                self.assertEqual(self.invoke("apply", "tofu apply (saved plan)", code), code)

    def test_targeted_evidence_plan_remains_guarded_and_private(self):
        self.assertEqual(self.invoke("apply", "tofu plan -out", 0, "admin-ui-evidence"), 0)
        self.assertNotEqual(self.invoke("apply", "tofu plan -out", 0,
                                       "admin-ui-evidence", invalid_plan=True), 0)
        self.assertEqual(self.invoke("apply", "tofu plan -out", 1, "admin-ui-evidence"), 1)

    def test_targeted_apply_keeps_digest_check(self):
        self.assertEqual(self.invoke("apply", "tofu apply (saved plan)", 0,
                                     "admin-ui-evidence"), 0)

    def test_missing_masked_configuration_fails_closed(self):
        for job in ("plan", "apply"):
            for role, region in (("", ""), ("", "synthetic-region"), ("synthetic-role", ""),
                                 ("synthetic-role", "synthetic-region")):
                with self.subTest(job=job, role=bool(role), region=bool(region)):
                    result = subprocess.run(
                        ["bash", "-e", "-c", self.step_run(job, "Require masked platform configuration")],
                        env=dict(os.environ, PLATFORM_ROLE=role, AWS_REGION=region),
                        capture_output=True, text=True, timeout=30, check=False)
                    self.assertEqual(result.returncode, 1)
                    self.assertNotIn("synthetic-role", result.stdout + result.stderr)

    def test_workflow_has_no_literal_cloud_roles_and_masks_account(self):
        source = (ROOT / ".github/workflows/platform-tofu.yml").read_text()
        # A format validator contains an ARN prefix, but must contain no concrete
        # account/role identity. Distinguish syntax from an embedded role value.
        self.assertNotRegex(source, r"arn:aws:iam::[0-9]{12}:role/")
        self.assertEqual(source.count("mask-aws-account-id: true"), 2)
        self.assertIn("PLATFORM_ROLE: ${{ secrets.PLATFORM_TOFU_PLAN_ROLE }}", source)
        self.assertIn("PLATFORM_ROLE: ${{ secrets.PLATFORM_TOFU_APPLY_ROLE }}", source)

    def test_account_is_registered_for_masking_before_credentials(self):
        for job in ("plan", "apply"):
            result = subprocess.run(
                ["bash", "-e", "-c", self.step_run(job, "Require masked platform configuration")],
                env=dict(os.environ, PLATFORM_ROLE="arn:aws:iam::000000000000:role/synthetic-plan",
                         AWS_REGION="synthetic-region"),
                capture_output=True, text=True, timeout=30, check=False)
            self.assertEqual(result.returncode, 0)
            self.assertEqual(result.stdout, "::add-mask::000000000000\n")
            self.assertEqual(result.stderr, "")


if __name__ == "__main__":
    unittest.main()
