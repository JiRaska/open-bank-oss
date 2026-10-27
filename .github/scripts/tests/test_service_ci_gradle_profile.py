"""Exercise the ordinary build shell with fake clients; never invoke Gradle."""
# SPDX-License-Identifier: Apache-2.0
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

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
                     "SERVICE": service, "SERVICE_CI_HELPERS": str(ROOT / ".github/scripts/service-ci-steps"),
                     "PROFILE_TEST_ARGS": str(arguments), "PROFILE_TEST_EXIT": str(exit_code)},
                capture_output=True, text=True, timeout=10, check=False,
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
        call = next(step for step in STEPS if step.get("name") == "Retain Gradle task profile")
        self.assertEqual(call["uses"], "./.github/actions/service-ci-gradle-profile")
        self.assertEqual(call["env"], {"SERVICE": "${{ inputs.service }}"})
        self.assertEqual(call["if"],
                         "always() && (inputs.service == 'openbank-lending-service' || inputs.service == 'openbank-pension-service')")
        self.assertTrue(call["continue-on-error"])
        action = yaml.safe_load((ROOT / ".github/actions/service-ci-gradle-profile/action.yml").read_text())
        self.assertEqual(action["runs"]["using"], "composite")
        sanitizer, upload = action["runs"]["steps"]
        self.assertEqual(sanitizer["name"], "Sanitize Gradle task profile")
        self.assertEqual(sanitizer["id"], "gradle-task-profile")
        self.assertEqual(sanitizer["if"], "always()")
        self.assertTrue(sanitizer["continue-on-error"])
        self.assertEqual(sanitizer["shell"], "bash")
        self.assertIn("--profiles build/reports/profile", sanitizer["run"])
        self.assertIn('"$SERVICE/build/test-intelligence/gradle-task-profile.json"', sanitizer["run"])
        self.assertNotIn("${{", sanitizer["run"])
        self.assertEqual(upload["name"], "Retain Gradle task profile")
        self.assertEqual(upload["if"], "always() && steps.gradle-task-profile.outcome == 'success'")
        self.assertTrue(upload["continue-on-error"])
        self.assertEqual(upload["with"], {
            "name": "gradle-task-profile-${{ env.SERVICE }}-${{ github.run_id }}-a${{ github.run_attempt }}",
            "path": "${{ env.SERVICE }}/build/test-intelligence/gradle-task-profile.json",
            "if-no-files-found": "warn", "retention-days": 3,
        })
        self.assertEqual(next(step["uses"] for step in STEPS
                              if step.get("name") == "Upload quality evidence (every run)"), upload["uses"])
        self.assertLess(STEPS.index(BUILD), STEPS.index(call))
        self.assertLess(STEPS.index(call), next(index for index, step in enumerate(STEPS)
                                               if step.get("id") == "coverage_report"))

    def test_diagnostic_actions_survive_historical_checkout_from_trusted_snapshot(self):
        import shutil
        stage = next(step for step in STEPS if step.get("name") == "Stage trusted service CI step helpers")
        historical = next(step for step in STEPS
                          if step.get("name") == "Check out historical service source with trusted helpers")
        action_names = ("service-ci-gradle-profile", "service-ci-coverage-evidence")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            scripts = root / ".github/scripts"
            shutil.copytree(ROOT / ".github/scripts/service-ci-steps", scripts / "service-ci-steps")
            shutil.copyfile(ROOT / ".github/scripts/heal-gradle-verification-failure.sh",
                            scripts / "heal-gradle-verification-failure.sh")
            expected = {}
            for name in action_names:
                source = ROOT / ".github/actions" / name / "action.yml"
                expected[name] = source.read_bytes()
                destination = root / ".github/actions" / name / "action.yml"
                destination.parent.mkdir(parents=True)
                destination.write_bytes(expected[name])
            runtime = root / "runtime"
            runtime.mkdir()
            environment = root / "env"
            environment.touch()
            env = dict(os.environ, RUNNER_TEMP=str(runtime), GITHUB_ENV=str(environment))
            result = subprocess.run(["bash", "-e", "-c", stage["run"]], cwd=root, env=env,
                                    capture_output=True, text=True, check=False)
            self.assertEqual(result.returncode, 0, result.stderr)
            helpers = dict(line.split("=", 1) for line in environment.read_text().splitlines())["SERVICE_CI_HELPERS"]
            # Execute the actual historical step; only Git/ref resolution transport is faked.
            (scripts / "tests").mkdir()
            (scripts / "tests/test-resolve-historical-service-ref.sh").write_text("exit 0\n")
            (scripts / "resolve-historical-service-ref.sh").write_text("echo fixture-ref\n")
            for name in ("retry-testcontainers-ebusy.sh", "retry-gradle-verification-cache.sh",
                         "heal-gradle-verification-failure.sh"):
                (runtime / name).write_text("trusted retry fixture\n")
            commands = root / "commands"
            commands.mkdir()
            git = commands / "git"
            git.write_text("#!/bin/bash\nif [ \"$1\" = checkout ]; then\n"
                           "  rm -rf .github/actions/service-ci-gradle-profile\n"
                           "  printf 'historical metadata' > .github/actions/service-ci-coverage-evidence/action.yml\n"
                           "fi\n")
            git.chmod(0o700)
            result = subprocess.run(["bash", "-e", "-c", historical["run"]], cwd=root,
                                    env=dict(env, SERVICE_CI_HELPERS=helpers, REF="fixture-ref",
                                             PATH=str(commands) + os.pathsep + os.environ["PATH"]),
                                    capture_output=True, text=True, check=False)
            self.assertEqual(result.returncode, 0, result.stderr)
            for name in action_names:
                self.assertEqual((root / ".github/actions" / name / "action.yml").read_bytes(), expected[name])

    def test_snapshot_refuses_missing_or_symlinked_action_metadata(self):
        helper = ROOT / ".github/scripts/service-ci-steps/diagnostic-actions.sh"
        for mode in ("stage", "restore"):
            for kind in ("missing", "file-link", "directory-link", "manifest-directory", "manifest-fifo"):
                with self.subTest(mode=mode, kind=kind), tempfile.TemporaryDirectory() as temporary:
                    root = Path(temporary)
                    helpers = root / "trusted"
                    helpers.mkdir()
                    outside = root / "sentinel"
                    outside.write_text("unchanged")
                    for name in ("service-ci-gradle-profile", "service-ci-coverage-evidence"):
                        manifest = root / ".github/actions" / name / "action.yml"
                        manifest.parent.mkdir(parents=True)
                        manifest.write_text("current metadata")
                        (helpers / (name + ".yml")).write_text("trusted metadata")
                    manifest = root / ".github/actions/service-ci-gradle-profile/action.yml"
                    if kind == "missing":
                        (manifest if mode == "stage" else helpers / "service-ci-gradle-profile.yml").unlink()
                    elif kind == "file-link":
                        manifest.unlink()
                        manifest.symlink_to(outside)
                    elif kind.startswith("manifest-"):
                        manifest.unlink()
                        if kind == "manifest-directory":
                            manifest.mkdir()
                        else:
                            os.mkfifo(manifest)
                    else:
                        manifest.unlink()
                        manifest.parent.rmdir()
                        manifest.parent.symlink_to(helpers, target_is_directory=True)
                    result = subprocess.run(["bash", "-e", str(helper), mode, str(helpers)], cwd=root,
                                            capture_output=True, text=True, check=False, timeout=3)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertEqual(outside.read_text(), "unchanged")



if __name__ == "__main__":
    unittest.main()
