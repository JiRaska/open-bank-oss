"""Exercise the ordinary build shell with fake clients; never invoke Gradle."""
# SPDX-License-Identifier: Apache-2.0
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = yaml.safe_load((ROOT / ".github/workflows/_service-ci.yml").read_text())
STEPS = WORKFLOW["jobs"]["build"]["steps"]
BUILD = next(step for step in STEPS if step.get("name", "").startswith("Build + test"))


class ServiceGradleProfileTest(unittest.TestCase):
    def run_build(self, service, exit_code=0):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            scripts = root / ".github/scripts"
            scripts.mkdir(parents=True)
            for name in ("retry-testcontainers-ebusy.sh", "retry-gradle-verification-cache.sh"):
                (scripts / name).write_text('#!/bin/bash\nshift\nexec "$@"\n')
            (root / "docker").write_text("#!/bin/sh\nexit 0\n")
            (root / "docker").chmod(0o755)
            (root / "gradlew").write_text(
                "#!/usr/bin/env python3\nimport json, os, sys\n"
                "from pathlib import Path\n"
                "Path(os.environ['PROFILE_TEST_ARGS']).write_text(json.dumps(sys.argv[1:]))\n"
                "print('fixture Gradle output')\n"
                "sys.exit(int(os.environ['PROFILE_TEST_EXIT']))\n"
            )
            (root / "gradlew").chmod(0o755)
            arguments = root / "args.json"
            result = subprocess.run(
                ["bash", "-e", "-c", BUILD["run"].replace("${{ inputs.service }}", service)],
                cwd=root,
                env={**os.environ, "PATH": str(root) + os.pathsep + os.environ["PATH"],
                     "PROFILE_TEST_ARGS": str(arguments), "PROFILE_TEST_EXIT": str(exit_code)},
                capture_output=True, text=True, timeout=10,
            )
            self.assertEqual(exit_code, result.returncode, result.stderr)
            self.assertIn("fixture Gradle output", result.stdout)
            return json.loads(arguments.read_text())

    def test_only_selected_services_add_profile_without_changing_build_recipe(self):
        for service in ("openbank-lending-service", "openbank-pension-service", "openbank-ledger-service"):
            with self.subTest(service=service):
                arguments = self.run_build(service)
                profiled = service != "openbank-ledger-service"
                self.assertEqual(int(profiled), arguments.count("--profile"))
                self.assertEqual(
                    ["--configure-on-demand", f":{service}:build", "--build-cache",
                     "--console=plain", "--stacktrace",
                     "-Dquarkus.smallrye-openapi.store-schema-directory=build/openapi-generated"],
                    [argument for argument in arguments if argument != "--profile"],
                )

    def test_profiled_build_failure_keeps_original_exit_status(self):
        self.assertIn("--profile", self.run_build("openbank-lending-service", exit_code=23))

    def test_profile_artifact_is_scoped_advisory_and_separate_from_test_evidence(self):
        step = next((step for step in STEPS if step.get("name") == "Retain Gradle task profile"), None)
        self.assertIsNotNone(step)
        self.assertEqual(
            "${{ always() && (inputs.service == 'openbank-lending-service' || inputs.service == 'openbank-pension-service') && steps.gradle-task-profile.outcome == 'success' }}",
            step["if"],
        )
        self.assertTrue(step["continue-on-error"])
        self.assertEqual("${{ inputs.service }}/build/test-intelligence/gradle-task-profile.json", step["with"]["path"])
        self.assertEqual("warn", step["with"]["if-no-files-found"])
        self.assertEqual(3, step["with"]["retention-days"])
        self.assertEqual(
            "gradle-task-profile-${{ inputs.service }}-${{ github.run_id }}-a${{ github.run_attempt }}",
            step["with"]["name"],
        )
        self.assertEqual(
            next(step["uses"] for step in STEPS if step.get("name") == "Upload quality evidence (every run)"),
            step["uses"],
        )
        sanitizer = next(step for step in STEPS if step.get("name") == "Sanitize Gradle task profile")
        self.assertEqual("gradle-task-profile", sanitizer["id"])
        self.assertEqual(
            "${{ always() && (inputs.service == 'openbank-lending-service' || inputs.service == 'openbank-pension-service') }}",
            sanitizer["if"],
        )
        self.assertTrue(sanitizer["continue-on-error"])
        self.assertLess(STEPS.index(BUILD), STEPS.index(sanitizer))
        self.assertLess(STEPS.index(sanitizer), next(index for index, step in enumerate(STEPS)
                                                    if step.get("name") == "Generate Kover XML report"))
        self.assertIn("--profiles build/reports/profile", sanitizer["run"])


if __name__ == "__main__":
    unittest.main()
