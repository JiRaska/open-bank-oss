"""Run the provider discovery shell regressions in the enforced unittest lane."""
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[3]
SERVICE = "openbank-ci-harness"


class ProviderTaskDiscoveryTests(unittest.TestCase):
    def run_harness(self, name):
        result = subprocess.run(
            ["bash", str(ROOT / ".github/scripts" / name)],
            cwd=ROOT, capture_output=True, text=True, timeout=20,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_complete_task_proof_and_fallback_boundaries(self):
        self.run_harness("test-service-ci-provider-task-list.sh")

    def test_discovery_preserves_targeted_and_consumer_semantics(self):
        self.run_harness("test-service-ci-provider-task-discovery.sh")

    @classmethod
    def setUpClass(cls):
        workflow = yaml.safe_load((ROOT / ".github/workflows/_service-ci.yml").read_text())
        steps = workflow["jobs"]["contract"]["steps"]
        cls.stage_run = next(s["run"] for s in steps if s.get("name") == "Stage trusted Pact Broker retry helper")
        cls.warmup_run = next(s["run"] for s in steps if s.get("name") == "Warm Gradle dependency cache (self-heal corrupted entries)")
        cls.discovery_run = next(s["run"] for s in steps if s.get("name") == "Discover provider Pact task")
        provider_step = next(s for s in steps if s.get("name", "").startswith("Provider verification ("))
        cls.provider_run = provider_step["run"]
        cls.provider_if = provider_step["if"]

    def make_workspaces(self, temp):
        root = Path(temp)
        trusted = root / "trusted"
        runner_temp = root / "runner-temp"
        historical = root / "historical-P"
        for path in (trusted / ".github/scripts", runner_temp, historical / ".github/scripts", historical / f"{SERVICE}/src/test"):
            path.mkdir(parents=True, exist_ok=True)

        (trusted / ".github/scripts/pact-broker-post.sh").write_text(
            "#!/usr/bin/env bash\n[ \"${1:-}\" = --self-test ]\n"
        )
        for name in ("service-ci-provider-task-list.sh", "service-ci-discover-provider-pact-task.sh"):
            source = ROOT / ".github/scripts" / name
            (trusted / ".github/scripts" / name).write_bytes(source.read_bytes())

        # P deliberately has no copies of the newly introduced trusted helpers.
        self.assertFalse((historical / ".github/scripts/service-ci-provider-task-list.sh").exists())
        self.assertFalse((historical / ".github/scripts/service-ci-discover-provider-pact-task.sh").exists())
        (historical / f"{SERVICE}/src/test/SampleProviderVerificationTest.kt").touch()

        calls = root / "gradle-calls"
        (historical / "gradlew").write_text(FAKE_GRADLE)
        (historical / "gradlew").chmod(0o755)
        timeout_bin = root / "bin"
        timeout_bin.mkdir()
        (timeout_bin / "timeout").write_text(FAKE_TIMEOUT)
        (timeout_bin / "timeout").chmod(0o755)
        (historical / ".github/scripts/heal-gradle-verification-failure.sh").write_text(FAKE_HEAL)
        (historical / ".github/scripts/heal-gradle-verification-failure.sh").chmod(0o755)
        env = {
            "PATH": f"{timeout_bin}:{os.environ['PATH']}",
            "RUNNER_TEMP": str(runner_temp),
            "GITHUB_RUN_ID": "12345",
            "GITHUB_RUN_ATTEMPT": "1",
            "GITHUB_OUTPUT": str(root / "github-output"),
            "PACT_PROVIDER_VERSION": "historical-sha",
            "CALLS": str(calls),
            "WARMUP_MODE": "success",
            "DISCOVERY_MODE": "task",
            "HEAL_STATUS": "1",
            "PACT_BROKER_URL": "",
            "PACT_BROKER_USERNAME": "",
            "PACT_BROKER_PASSWORD": "",
            "BRANCH": "harness-branch",
        }
        return trusted, runner_temp, historical, calls, env

    def run_shell(self, body, cwd, env, *, expect=0):
        result = subprocess.run(
            ["bash", "-e", "-o", "pipefail", "-c", body],
            cwd=cwd, env={**os.environ, **{key: str(value) for key, value in env.items()}},
            capture_output=True, text=True, timeout=20,
        )
        self.assertEqual(result.returncode, expect, result.stdout + result.stderr)
        return result

    def run_workflow_helper_sequence(self, root, runner_temp, historical, env):
        stage_body = self.stage_run
        warmup_body = self.warmup_run.replace("${{ inputs.service }}", SERVICE).replace(
            "/tmp/gradle-warmup.log", "$RUNNER_TEMP/gradle-warmup.log"
        )
        discovery_body = self.discovery_run.replace("${{ inputs.service }}", SERVICE).replace(
            "${{ inputs.require_provider_pact_test }}", "false"
        )
        self.run_shell(stage_body, root / "trusted", env)
        self.run_shell(warmup_body, historical, env)
        discovery = self.run_shell(discovery_body, historical, env, expect=env.get("DISCOVERY_EXPECT", 0))
        return discovery

    def test_workflow_bodies_reuse_only_proven_successful_warmup(self):
        cases = (
            ("success", "task", 1, 0),
            ("failure", "task", 2, 0),
            ("timeout", "task", 2, 0),
            ("incomplete", "task", 2, 0),
            ("absent", "absent", 2, 1),
        )
        for warmup_mode, discovery_mode, expected_calls, expected_status in cases:
            with self.subTest(warmup=warmup_mode):
                with tempfile.TemporaryDirectory() as tmp:
                    root = Path(tmp)
                    trusted, runner_temp, historical, calls, env = self.make_workspaces(root)
                    env.update(WARMUP_MODE=warmup_mode, DISCOVERY_MODE=discovery_mode, DISCOVERY_EXPECT=expected_status)
                    proof = runner_temp / f"provider-pact-task-{SERVICE}-12345-1.proof"
                    # A valid stale proof must be removed before a failed/incomplete warm-up.
                    proof.write_text(SERVICE)
                    output = root / "github-output"
                    output.write_text("")
                    self.run_workflow_helper_sequence(root, runner_temp, historical, env)
                    invocations = calls.read_text().splitlines()
                    self.assertEqual(len(invocations), expected_calls)
                    self.assertNotIn("test", " ".join(invocations))
                    self.assertNotIn(":providerPactTest", " ".join(invocations))
                    self.assertFalse((historical / ".github/scripts/service-ci-provider-task-list.sh").exists())
                    if warmup_mode == "success":
                        self.assertIn(f":{SERVICE}:dependencies", invocations[0])
                        self.assertIn(f":{SERVICE}:tasks", invocations[0])
                        self.assertEqual(output.read_text(), "available=true\n")
                    elif warmup_mode == "absent":
                        self.assertFalse(proof.exists())
                    else:
                        self.assertFalse(proof.exists())

    def test_workflow_retry_proof_requires_successful_healed_retry(self):
        for retry_mode, expected_calls in (("retry-success", 2), ("retry-failure", 3)):
            with self.subTest(retry=retry_mode):
                with tempfile.TemporaryDirectory() as tmp:
                    root = Path(tmp)
                    _, runner_temp, historical, calls, env = self.make_workspaces(root)
                    env.update(WARMUP_MODE=retry_mode, HEAL_STATUS="0", DISCOVERY_MODE="task")
                    output = root / "github-output"
                    output.write_text("")
                    self.run_workflow_helper_sequence(root, runner_temp, historical, env)
                    invocations = calls.read_text().splitlines()
                    self.assertEqual(len(invocations), expected_calls)
                    proof = runner_temp / f"provider-pact-task-{SERVICE}-12345-1.proof"
                    if retry_mode == "retry-success":
                        self.assertTrue(proof.exists())
                        self.assertEqual(output.read_text(), "available=true\n")
                    else:
                        self.assertFalse(proof.exists())

    def test_provider_verification_invokes_only_after_discovery(self):
        self.assertEqual(self.provider_if, "${{ steps.provider_pact_task.outputs.available == 'true' }}")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            trusted, _, historical, calls, env = self.make_workspaces(root)
            (root / "github-output").write_text("")
            self.run_workflow_helper_sequence(root, root / "runner-temp", historical, env)
            self.assertEqual((root / "github-output").read_text(), "available=true\n")
            self.assertEqual(len(calls.read_text().splitlines()), 1)

            provider_run = self.provider_run.replace("${{ inputs.service }}", SERVICE)
            env["PACT_PROVIDER_VERSION"] = "fixture-provider-version"
            self.run_shell(provider_run, historical, env)
            invocations = calls.read_text().splitlines()
            self.assertEqual(len(invocations), 2)
            provider_call = invocations[-1]
            self.assertIn(f":{SERVICE}:providerPactTest", provider_call)
            self.assertIn("-Dpactbroker.url=", provider_call)
            self.assertIn("-Dpactbroker.auth.username=", provider_call)
            self.assertIn("-Dpactbroker.auth.password=", provider_call)
            self.assertIn("-Dpact.verifier.publishResults=true", provider_call)
            self.assertIn("-Dpact.provider.version=fixture-provider-version", provider_call)
            self.assertNotIn(":test", provider_call)

    def test_consumer_only_discovery_never_invokes_provider_task(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            trusted, _, historical, calls, env = self.make_workspaces(root)
            (historical / f"{SERVICE}/src/test/SampleProviderVerificationTest.kt").unlink()
            (root / "github-output").write_text("")
            self.run_workflow_helper_sequence(root, root / "runner-temp", historical, env)
            self.assertEqual((root / "github-output").read_text(), "available=false\n")
            self.assertEqual(len(calls.read_text().splitlines()), 1)


FAKE_TIMEOUT = r'''#!/usr/bin/env bash
while [ "$#" -gt 0 ] && [ "$1" != "./gradlew" ]; do shift; done
[ "$#" -gt 0 ] || exit 127
exec "$@"
'''

FAKE_HEAL = r'''#!/usr/bin/env bash
exit "${HEAL_STATUS:-1}"
'''

FAKE_GRADLE = r'''#!/usr/bin/env bash
printf '%s\n' "$*" >> "$CALLS"
emit_tasks() {
  printf '> Task :openbank-ci-harness:tasks\n\n'
  printf "Tasks runnable from project ':openbank-ci-harness'\n"
  if [ "$1" = task ]; then printf 'providerPactTest\n'; else printf 'help\n'; fi
  printf 'BUILD SUCCESSFUL in 1s\n'
}
is_warmup=false
for arg in "$@"; do [[ "$arg" == *:dependencies ]] && is_warmup=true; done
if [ "$is_warmup" = true ]; then
  attempt=$(grep -c ':dependencies' "$CALLS")
  case "$WARMUP_MODE" in
    failure) echo 'advisory failure'; exit 1 ;;
    timeout) exit 124 ;;
    incomplete) printf '> Task :%s:dependencies\nproviderPactTest\nBUILD SUCCESSFUL in 1s\n' "$SERVICE_NAME"; exit 0 ;;
    absent) emit_tasks absent; exit 0 ;;
    retry-success|retry-failure)
      if [ "$attempt" -eq 1 ]; then echo 'first failure'; exit 1; fi
      if [ "$WARMUP_MODE" = retry-failure ]; then exit 1; fi
      emit_tasks task; exit 0 ;;
    *) emit_tasks task; exit 0 ;;
  esac
else
  emit_tasks "$DISCOVERY_MODE"
fi
'''

FAKE_GRADLE = FAKE_GRADLE.replace("SERVICE_NAME", "openbank-ci-harness")
