#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Execute the actual Pact driver's source/selector boundaries with an offline broker probe.

The full driver requires Bash 4 associative arrays; these extracted native Bash
boundaries also run on macOS Bash 3.2. The production selector itself is unmodified.
No broker, download, deployment or repository mutation occurs.
"""
import json
import os
import re
import shlex
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DRIVER = ROOT / ".github/scripts/can-i-deploy-gate.sh"
SOURCE = "a" * 40
WORKFLOW = "b" * 40
EQUIVALENT = "c" * 40
SERVICE = "openbank-context-service"


class SourceSelectorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = DRIVER.read_text()
        cls.prefix = source[source.index("set -uo pipefail"):source.index('arch="$(uname')]
        start = source.index("  # WHICH version to ask")
        end = source.index("  # REFUSE", start)
        cls.selection = source[start:end]
        probe_start = source.rindex('  code="$(curl', 0, start)
        cls.pacticipant_probe = source[probe_start:source.index("  # WHICH", probe_start)]

    def select(self, present, event="workflow_run", source=SOURCE, reconcile_snapshot=None):
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            trace = temporary / "probe"
            environment = dict(os.environ, GITHUB_SHA=WORKFLOW, EVENT_NAME=event,
                               SERVICES=json.dumps([SERVICE]), GITHUB_OUTPUT=str(temporary / "output"),
                               PACT_BROKER_URL="https://broker.invalid", PROBE_RESULT=present,
                               PROBE_TRACE=str(trace), RECONCILE_SOURCE_REQUIRED="false")
            environment.pop("SOURCE_COMMIT", None)
            if source is not None:
                environment["SOURCE_COMMIT"] = source
            stub = r'''
bash() {
  if [ "$1" = .github/scripts/probe-pact-version.sh ]; then
    printf '%s\n' "$2" "$3" > "$PROBE_TRACE"
    printf '%s\n' "$PROBE_RESULT"
  else
    command bash "$@"
  fi
}
svc=openbank-context-service
'''
            if reconcile_snapshot is not None:
                # The separate reconcile suite verifies the source preflight. Here exercise the
                # complete production selection block after that verified snapshot is available.
                environment["RECONCILE_TEST_SNAPSHOT"] = json.dumps({SERVICE: reconcile_snapshot})
                stub += 'RECONCILE_SOURCE_REQUIRED=true\nreconcile_present="$RECONCILE_TEST_SNAPSHOT"\n'
            result = subprocess.run(["bash", "-c", self.prefix + stub + self.selection +
                                     '\nprintf "%s\\n" "${CID_SELECTOR[@]}"\n'],
                                    cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10, check=False)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            expected_source = WORKFLOW if source is None else source
            if reconcile_snapshot is None:
                self.assertEqual(trace.read_text().splitlines(), [SERVICE, expected_source])
            else:
                self.assertFalse(trace.exists(), "verified reconcile snapshot must not be replaced by another probe")
            return result.stdout.splitlines()

    def test_handoff_asks_about_source_not_orchestrating_workflow_commit(self):
        self.assertEqual(self.select("yes"), ["--version", SOURCE])

    def test_reconcile_snapshot_survives_source_handoff_integration(self):
        for snapshot, expected in (("yes", SOURCE), (f"equivalent:{EQUIVALENT}", EQUIVALENT)):
            with self.subTest(snapshot=snapshot):
                self.assertEqual(self.select("no", reconcile_snapshot=snapshot), ["--version", expected])

    def test_handoff_uses_proven_equivalent_source_version(self):
        self.assertEqual(self.select(f"equivalent:{EQUIVALENT}"), ["--version", EQUIVALENT])

    def test_handoff_never_borrows_latest_after_missing_inconclusive_or_malformed_probe(self):
        for present in ("no", "unknown", "absent", "", "equivalent:short", "equivalent:" + "C" * 40):
            with self.subTest(present=present):
                expected = ["REFUSE"] if present in ("no", "absent") else ["GATE_VERDICT=NOT_EVALUATED", "REFUSE"]
                self.assertEqual(self.select(present), expected)

    def test_push_keeps_diagnostic_latest_and_default_github_source(self):
        for present in ("no", "absent"):
            with self.subTest(present=present):
                self.assertEqual(self.select(present, event="push", source=None), ["--latest", "main"])
        self.assertEqual(self.select("yes", event="push", source=None), ["--version", WORKFLOW])

    def test_push_refuses_inconclusive_or_malformed_probe_before_cli(self):
        for present in ("unknown", "", "unrecognized", "equivalent:short", "equivalent:" + "C" * 40):
            with self.subTest(present=present):
                self.assertEqual(self.select(present, event="push"), ["GATE_VERDICT=NOT_EVALUATED", "REFUSE"])

    def test_handoff_does_not_bypass_selector_on_pacticipant_404(self):
        for event, expected in (("workflow_run", ["selector-required"]), ("push", [SERVICE])):
            with self.subTest(event=event), tempfile.TemporaryDirectory() as directory:
                environment = dict(os.environ, EVENT_NAME=event, PACT_BROKER_URL="https://broker.invalid",
                                   PACT_BROKER_USERNAME="unused", PACT_BROKER_PASSWORD="unused")
                script = ('set -uo pipefail\ncurl() { printf 404; }\ndeployable_list=()\n'
                          f'for svc in {SERVICE}; do\n' + self.pacticipant_probe +
                          '\necho selector-required\ndone\nprintf "%s\\n" "${deployable_list[@]:-}"\n')
                result = subprocess.run(["bash", "-c", script], cwd=directory, env=environment,
                                        capture_output=True, text=True, timeout=10, check=False)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual([line for line in result.stdout.splitlines()
                                  if line == "selector-required" or line == SERVICE], expected)

    def test_strict_workflow_routes_unconfigured_broker_to_actual_refusal(self):
        workflow = (ROOT / ".github/workflows/auto-deploy.yml").read_text()
        step = workflow[workflow.index("      - name: can-i-deploy (per changed service)"):]
        expression = re.search(r"(?m)^        if: \$\{\{ (.*?) \}\}$", step).group(1)
        for event, reconcile, broker, expected in (("schedule", "", "", True), ("workflow_dispatch", "true", "", True), ("workflow_run", "", "", True), ("push", "", "", False), ("workflow_dispatch", "false", "", False), ("push", "", "https://broker.invalid", True)):
            with self.subTest(event=event, reconcile=reconcile, broker=broker):
                # This guard contains only string equality, &&/|| and parentheses.
                # Execute that exact restricted expression as a native Bash condition;
                # this is not a general GitHub Actions expression implementation.
                projected = expression
                for key, value in (("vars.PACT_BROKER_URL", broker), ("github.event_name", event), ("github.event.inputs.reconcile", reconcile)):
                    projected = projected.replace(key, shlex.quote(value))
                self.assertNotIn("github.", projected)
                self.assertNotIn("vars.", projected)
                result = subprocess.run(["bash", "-c", "[[ " + projected + " ]]"], capture_output=True, text=True, check=False)
                self.assertEqual(result.returncode, 0 if expected else 1, result.stderr)
                if expected and not broker:
                    self._assert_actual_driver_refuses_missing_broker(event, reconcile)

    def test_actual_driver_without_broker_fails_before_any_external_call(self):
        self._assert_actual_driver_refuses_missing_broker("workflow_run", "")

    def _gitops_job(self):
        workflow = (ROOT / ".github/workflows/auto-deploy.yml").read_text()
        return re.search(r"(?ms)^  gitops-pr:\n.*?(?=^  [a-zA-Z0-9_-]+:\n|\Z)", workflow).group(0)

    def _restricted_condition(self, expression, values):
        # The tested workflow conditions use only string comparisons and boolean
        # operators. Native Bash evaluates these exact projections, not a parallel
        # implementation of the job's decision table or a general Actions engine.
        projected = expression.replace("always()", "'true' == 'true'")
        for key in sorted(values, key=len, reverse=True):
            projected = projected.replace(key, shlex.quote(values[key]))
        self.assertNotRegex(projected, r"(?:github|needs|vars)\.")
        result = subprocess.run(["bash", "-c", "[[ " + projected + " ]]"],
                                capture_output=True, text=True, check=False)
        self.assertIn(result.returncode, (0, 1), result.stderr)
        return result.returncode == 0

    def test_strict_gitops_job_requires_reached_gate_but_preserves_partial_success(self):
        expression = re.search(r"(?ms)^    if: \|\n(.*?)(?=^    [a-zA-Z_-]+:)", self._gitops_job()).group(1).strip()
        built = json.dumps([SERVICE, "openbank-peer-service"])
        subset = json.dumps([SERVICE])
        cases = [
            ("schedule", "", "failure", "", "", False),
            ("workflow_dispatch", "true", "failure", "", "", False),
            ("schedule", "", "skipped", "", "", False),
            ("schedule", "", "cancelled", "true", subset, False),
            ("schedule", "", "failure", "true", "[]", False),
            ("schedule", "", "failure", "true", "", False),
            ("schedule", "", "failure", "true", subset, True),
            ("workflow_dispatch", "true", "failure", "true", subset, True),
            ("workflow_dispatch", "true", "success", "true", built, True),
            ("workflow_run", "", "failure", "true", subset, False),
            ("workflow_run", "", "success", "true", subset, True),
            ("push", "", "success", "", "", True),
            ("workflow_dispatch", "false", "success", "", "", True),
        ]
        for event, reconcile, result, ran, deployable, expected in cases:
            with self.subTest(event=event, reconcile=reconcile, result=result, ran=ran, deployable=deployable):
                values = {"github.event_name": event, "github.event.inputs.reconcile": reconcile,
                          "github.event.inputs.bootstrap_image_only": "false",
                          "needs.changes.outputs.any": "true", "needs.build-push.result": "success",
                          "needs.can-i-deploy.result": result, "needs.can-i-deploy.outputs.gate_ran": ran,
                          "needs.can-i-deploy.outputs.deployable": deployable,
                          "needs.build-push.outputs.deployable_services": built}
                self.assertEqual(self._restricted_condition(expression, values), expected)

    def test_strict_writer_validates_actual_subset_before_gitops_operations(self):
        job = self._gitops_job()
        strict_expression = re.search(r"(?m)^      RECONCILE_SOURCE_REQUIRED: \$\{\{ (.*?) \}\}$", job).group(1)
        self.assertIn("      BUILT_SERVICES: ${{ needs.build-push.outputs.deployable_services }}", job)
        rewrite = job[job.index("      - name: Rewrite image tags in gitops manifests"):]
        prefix = rewrite[rewrite.index("          set -euo pipefail"):rewrite.index("          # Recompute against LIVE main")]
        script = "\n".join(line[10:] for line in prefix.splitlines()) + "\nprintf writer-reached\\n\n"
        built = json.dumps([SERVICE, "openbank-peer-service"])
        subset = json.dumps([SERVICE])
        cases = [("", "", built, False), ("false", subset, built, False),
                 ("true", "", built, False), ("true", "[]", built, False),
                 ("true", "not-json", built, False), ("true", "null", built, False),
                 ("true", '"string"', built, False), ("true", "[1]", built, False),
                 ("true", json.dumps([SERVICE, SERVICE]), built, False),
                 ("true", '["openbank-foreign-service"]', built, False),
                 ("true", subset, "not-json", False), ("true", subset, "null", False),
                 ("true", subset, built, True), ("true", built, built, True)]
        for event, reconcile in (("schedule", ""), ("workflow_dispatch", "true")):
            strict = self._restricted_condition(strict_expression, {"github.event_name": event,
                                                                    "github.event.inputs.reconcile": reconcile})
            self.assertTrue(strict)
            for ran, deployable, built_services, expected in cases:
                with self.subTest(event=event, ran=ran, deployable=deployable, built=built_services):
                    environment = dict(os.environ, RECONCILE_SOURCE_REQUIRED=str(strict).lower(),
                                       GATE_RAN=ran, DEPLOYABLE_SERVICES=deployable, BUILT_SERVICES=built_services)
                    result = subprocess.run(["bash", "-c", script], env=environment,
                                            capture_output=True, text=True, check=False)
                    self.assertEqual(result.returncode, 0 if expected else 1, result.stdout + result.stderr)
                    self.assertEqual("writer-reached" in result.stdout, expected)
        for event, reconcile in (("push", ""), ("workflow_dispatch", "false")):
            strict = self._restricted_condition(strict_expression, {"github.event_name": event,
                                                                    "github.event.inputs.reconcile": reconcile})
            self.assertFalse(strict)
            result = subprocess.run(["bash", "-c", script], capture_output=True, text=True, check=False,
                                    env=dict(os.environ, RECONCILE_SOURCE_REQUIRED="false", GATE_RAN="",
                                             DEPLOYABLE_SERVICES="", BUILT_SERVICES=built))
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("writer-reached", result.stdout)

    def _assert_actual_driver_refuses_missing_broker(self, event, reconcile):
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            output, trace = temporary / "output", temporary / "unexpected-call"
            bash_env = temporary / "offline-stubs.sh"
            bash_env.write_text('''curl() { echo curl >> "$UNEXPECTED_CALL"; return 97; }
sleep() { return 97; }
function /tmp/pact/pact/bin/pact-broker { echo pact >> "$UNEXPECTED_CALL"; return 97; }
''')
            environment = dict(os.environ, SOURCE_COMMIT=SOURCE, GITHUB_SHA=WORKFLOW,
                               EVENT_NAME=event, RECONCILE_SOURCE_REQUIRED="true" if event == "schedule" or reconcile == "true" else "false", GITHUB_OUTPUT=str(output),
                               PACT_BROKER_URL="", SERVICES=json.dumps([SERVICE]),
                               BASH_ENV=str(bash_env), UNEXPECTED_CALL=str(trace),
                               PACT_STANDALONE_VERSION="unused", PACT_BROKER_USERNAME="unused",
                               PACT_BROKER_PASSWORD="unused")
            result = subprocess.run(["bash", str(DRIVER)], cwd=ROOT, env=environment,
                                    capture_output=True, text=True, timeout=10, check=False)
            self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
            self.assertIn("require a configured Pact broker", result.stdout)
            self.assertEqual(output.read_text().splitlines(), ["gate_ran=true", "deployable=[]"])
            self.assertFalse(trace.exists(), "missing broker must refuse before download or Pact invocation")


if __name__ == "__main__":
    unittest.main()
