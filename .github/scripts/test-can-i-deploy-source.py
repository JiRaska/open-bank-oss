#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Execute the actual Pact driver's source/selector boundaries with an offline broker probe.

The full driver requires Bash 4 associative arrays; these extracted native Bash
boundaries also run on macOS Bash 3.2. The production selector itself is unmodified.
No broker, download, deployment or repository mutation occurs.
"""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

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
                                    cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10)
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
                                        capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual([line for line in result.stdout.splitlines()
                                  if line == "selector-required" or line == SERVICE], expected)

    def test_actual_driver_without_broker_fails_before_any_external_call(self):
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            output, trace = temporary / "output", temporary / "unexpected-call"
            bash_env = temporary / "offline-stubs.sh"
            bash_env.write_text('''curl() { echo curl >> "$UNEXPECTED_CALL"; return 97; }
sleep() { return 97; }
function /tmp/pact/pact/bin/pact-broker { echo pact >> "$UNEXPECTED_CALL"; return 97; }
''')
            environment = dict(os.environ, SOURCE_COMMIT=SOURCE, GITHUB_SHA=WORKFLOW,
                               EVENT_NAME="workflow_run", GITHUB_OUTPUT=str(output),
                               PACT_BROKER_URL="", SERVICES=json.dumps([SERVICE]),
                               BASH_ENV=str(bash_env), UNEXPECTED_CALL=str(trace),
                               PACT_STANDALONE_VERSION="unused", PACT_BROKER_USERNAME="unused",
                               PACT_BROKER_PASSWORD="unused")
            result = subprocess.run(["bash", str(DRIVER)], cwd=ROOT, env=environment,
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
            self.assertIn("requires a configured Pact broker", result.stdout)
            self.assertEqual(output.read_text().splitlines(), ["gate_ran=true", "deployable=[]"])
            self.assertFalse(trace.exists(), "missing broker must refuse before download or Pact invocation")


if __name__ == "__main__":
    unittest.main()
