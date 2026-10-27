#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Fail-closed handoff provenance and disjoint deployment selection tests."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("context_handoff", Path(__file__).with_name("context-deploy-handoff.py"))
assert SPEC and SPEC.loader
HANDOFF = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HANDOFF)
REPO = "org/repo"
SHA = "a" * 40


def event():
    return {"action": "completed", "repository": {"full_name": REPO},
            "workflow_run": {"path": HANDOFF.WORKFLOW, "event": "push", "head_branch": "main",
                             "status": "completed", "conclusion": "success", "head_sha": SHA,
                             "repository": {"full_name": REPO}, "head_repository": {"full_name": REPO}}}


class ProvenanceTests(unittest.TestCase):
    def verify(self, payload):
        return HANDOFF.verified_source(payload, REPO, "workflow_run")

    def test_verified_main_push_source(self):
        self.assertEqual(self.verify(event()), SHA)

    def test_rejects_wrong_trigger_or_action(self):
        with self.assertRaises(HANDOFF.HandoffError):
            HANDOFF.verified_source(event(), REPO, "push")
        for action in (None, "requested", "in_progress", "completed\n"):
            with self.subTest(action=action), self.assertRaises(HANDOFF.HandoffError):
                self.verify(event() | {"action": action})

    def test_rejects_pr_nonmain_other_workflow_unsuccessful_or_incomplete(self):
        for field, values in {"path": [None, ".github/workflows/other.yml", HANDOFF.WORKFLOW + "@main"],
                              "event": [None, "pull_request", "workflow_dispatch", "schedule"],
                              "head_branch": [None, "feature", "Main"],
                              "status": [None, "queued", "in_progress"],
                              "conclusion": [None, "failure", "cancelled", "skipped", "neutral", "timed_out"]}.items():
            for value in values:
                payload = event()
                payload["workflow_run"][field] = value
                with self.subTest(field=field, value=value), self.assertRaises(HANDOFF.HandoffError):
                    self.verify(payload)

    def test_all_repository_identities_required_and_equal(self):
        for location, field in ((None, "repository"), ("workflow_run", "repository"), ("workflow_run", "head_repository")):
            for value in (None, {}, {"full_name": "fork/repo"}, {"full_name": "org/other"}, REPO):
                payload = event()
                parent = payload if location is None else payload[location]
                parent[field] = value
                with self.subTest(location=location, field=field, value=value), self.assertRaises(HANDOFF.HandoffError):
                    self.verify(payload)

    def test_invalid_sha_rejected_including_uppercase_and_newline(self):
        for value in (None, 123, [], "a" * 39, "a" * 41, "A" * 40, "g" * 40, SHA + "\n", " main "):
            payload = event()
            payload["workflow_run"]["head_sha"] = value
            with self.subTest(value=value), self.assertRaises(HANDOFF.HandoffError):
                self.verify(payload)

    def test_missing_malformed_payload_and_provenance_fail_closed(self):
        for payload in (None, [], {}, {"workflow_run": []}, event() | {"workflow_run": "bad"}):
            with self.subTest(payload=payload), self.assertRaises(HANDOFF.HandoffError):
                self.verify(payload)
        for repo in (None, "", "org", "org/repo/extra", "org/repo\n"):
            with self.subTest(repo=repo), self.assertRaises(HANDOFF.HandoffError):
                HANDOFF.verified_source(event(), repo, "workflow_run")

    def test_source_cli_emits_only_sha_and_rejections_emit_no_stdout(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "event.json"
            environment = {"GITHUB_EVENT_PATH": str(path), "GITHUB_REPOSITORY": REPO, "GITHUB_EVENT_NAME": "workflow_run"}
            for payload, expected in ((event(), 0), ({"private": "MUST_NOT_LEAK"}, 1)):
                path.write_text(json.dumps(payload))
                stdout, stderr = io.StringIO(), io.StringIO()
                with patch.dict(HANDOFF.os.environ, environment, clear=True), contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                    self.assertEqual(HANDOFF.main(["source"]), expected)
                self.assertEqual(stdout.getvalue(), SHA + "\n" if expected == 0 else "")
                self.assertNotIn("MUST_NOT_LEAK", stderr.getvalue())


class SelectionTests(unittest.TestCase):
    def test_push_and_followon_are_disjoint_and_cover_original_selection(self):
        services = ["openbank-ledger-service", HANDOFF.COMPONENT, "openbank-libs", "openbank-account-service"]
        push = HANDOFF.select_services(services, "push")
        followon = HANDOFF.select_services(services, "workflow_run")
        self.assertEqual(followon, [HANDOFF.COMPONENT])
        self.assertEqual(push, ["openbank-ledger-service", "openbank-libs", "openbank-account-service"])
        self.assertEqual(set(push) & set(followon), set())
        self.assertEqual(set(push) | set(followon), set(services))

    def test_no_context_added_when_detector_did_not_select_it(self):
        for selected in ([], ["openbank-ledger-service"]):
            self.assertEqual(HANDOFF.select_services(selected, "workflow_run"), [])
            self.assertEqual(HANDOFF.select_services(selected, "push"), selected)

    def test_other_events_untouched_preserving_order_and_duplicates(self):
        services = [HANDOFF.COMPONENT, "openbank-ledger-service", HANDOFF.COMPONENT]
        for name in ("workflow_dispatch", "schedule", "release", "pull_request"):
            with self.subTest(name=name):
                self.assertEqual(HANDOFF.select_services(services, name), services)
                self.assertIsNot(HANDOFF.select_services(services, name), services)

    def test_malformed_selection_or_event_rejected(self):
        for value in (None, {}, "openbank-context-service", [None], [1], ["context-service"], ["openbank-X"], ["openbank-a\n"]):
            with self.subTest(value=value), self.assertRaises(HANDOFF.HandoffError):
                HANDOFF.select_services(value, "push")
        for name in (None, "", "push\n", "workflow-run", []):
            with self.subTest(name=name), self.assertRaises(HANDOFF.HandoffError):
                HANDOFF.select_services([], name)

    def test_select_cli_compact_json_and_malformed_input(self):
        for content, event_name, expected in ((json.dumps([HANDOFF.COMPONENT, "openbank-libs"]), "push", '["openbank-libs"]\n'),
                                               (json.dumps([HANDOFF.COMPONENT]), "workflow_run", f'["{HANDOFF.COMPONENT}"]\n'),
                                               ("invalid PRIVATE", "push", "")):
            stdout, stderr = io.StringIO(), io.StringIO()
            with patch.object(HANDOFF.sys, "stdin", io.StringIO(content)), contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                status = HANDOFF.main(["select", "--event", event_name])
            self.assertEqual(stdout.getvalue(), expected)
            self.assertEqual(status, 0 if expected else 1)
            self.assertNotIn("PRIVATE", stderr.getvalue())

    def test_payload_size_limit(self):
        with patch.object(HANDOFF, "MAX_INPUT_BYTES", 2), self.assertRaises(HANDOFF.HandoffError):
            HANDOFF.read_json(io.StringIO("[1]"))


class WorkflowPartitionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root = Path(__file__).resolve().parents[2]
        cls.workflow = (cls.root / ".github/workflows/auto-deploy.yml").read_text()
        changes = cls.workflow.split("  changes:\n", 1)[1]
        cls.admission = re.search(r"^    if: >-\n((?:      .*\n)+)", changes, re.MULTILINE).group(1)
        marker = 'case "$GITHUB_EVENT_NAME:$CONTEXT_BUILD_HANDOFF_ENABLED"'
        if changes.count(marker) != 1:
            raise AssertionError("expected one actual workflow handoff partition")
        # Run the workflow's real final selection block, including its flag check and outputs.
        case_start = changes.index(marker)
        start = changes.rindex('          SERVICES_JSON="$(printf', 0, case_start)
        lines = []
        for line in changes[start:].splitlines():
            if line and not line.startswith("          "):
                break
            lines.append(line[10:])
        cls.partition_script = "\n".join(lines)

    def admitted(self, event_name, enabled):
        run = SimpleNamespace(**event()["workflow_run"])
        run.repository = SimpleNamespace(full_name=REPO)
        run.head_repository = SimpleNamespace(full_name=REPO)
        github = SimpleNamespace(event_name=event_name, repository=REPO,
                                 event=SimpleNamespace(workflow_run=run))
        expression = self.admission.replace("&&", " and ").replace("||", " or ")
        return eval("(" + expression + ")", {"__builtins__": {}},
                    {"github": github, "vars": SimpleNamespace(CONTEXT_BUILD_HANDOFF_ENABLED=enabled)})

    def partition(self, selected, event_name, enabled):
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            output = temporary / "output"
            shutil.copyfile(Path(__file__).with_name("context-deploy-handoff.py"), temporary / "context-deploy-handoff.py")
            environment = dict(os.environ, GITHUB_EVENT_NAME=event_name, CONTEXT_BUILD_HANDOFF_ENABLED=enabled,
                               RUNNER_TEMP=directory, GITHUB_OUTPUT=str(output))
            prefix = "set -euo pipefail\nDEPLOYABLE=(" + " ".join(shlex.quote(item) for item in selected) + ")\n"
            result = subprocess.run(["bash", "-c", prefix + self.partition_script], cwd=self.root,
                                    env=environment, capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            values = dict(line.split("=", 1) for line in output.read_text().splitlines())
            self.assertEqual(values["any"], "true" if json.loads(values["services"]) else "false")
            return json.loads(values["services"])

    def test_disabled_flag_preserves_push_and_rejects_followon_job(self):
        selected = [HANDOFF.COMPONENT, "openbank-ledger-service"]
        for disabled in ("", "false", "TRUE"):
            with self.subTest(flag=disabled):
                self.assertTrue(self.admitted("push", disabled))
                self.assertFalse(self.admitted("workflow_run", disabled))
                self.assertEqual(self.partition(selected, "push", disabled), selected)

    def test_enabled_actual_workflow_partitions_without_adding_services(self):
        for selected in ([HANDOFF.COMPONENT, "openbank-ledger-service"],
                         ["openbank-ledger-service"], [HANDOFF.COMPONENT]):
            with self.subTest(selected=selected):
                self.assertTrue(self.admitted("workflow_run", "true"))
                push = self.partition(selected, "push", "true")
                followon = self.partition(selected, "workflow_run", "true")
                self.assertFalse(set(push) & set(followon))
                self.assertEqual(set(push) | set(followon), set(selected))
                self.assertEqual(followon, [item for item in selected if item == HANDOFF.COMPONENT])

    def test_manual_and_schedule_selection_unchanged_for_both_flag_states(self):
        selected = ["openbank-ledger-service", HANDOFF.COMPONENT]
        for name in ("workflow_dispatch", "schedule"):
            for enabled in ("", "true"):
                with self.subTest(event=name, flag=enabled):
                    self.assertTrue(self.admitted(name, enabled))
                    self.assertEqual(self.partition(selected, name, enabled), selected)

    def test_handoff_contract_step_runs_even_when_broker_configuration_is_missing(self):
        gate = self.workflow.split("  can-i-deploy:\n", 1)[1].split("  gitops-pr:\n", 1)[0]
        condition = re.search(r"        id: check\n        if: \$\{\{ (.+) }}", gate).group(1)
        condition = condition.replace("||", " or ")
        for event_name, broker, expected in (("workflow_run", "", True), ("push", "", False),
                                              ("push", "configured", True)):
            with self.subTest(event=event_name, broker=broker):
                actual = eval(condition, {"__builtins__": {}},
                              {"github": SimpleNamespace(event_name=event_name),
                               "vars": SimpleNamespace(PACT_BROKER_URL=broker)})
                self.assertEqual(actual, expected)

    def test_handoff_gitops_requires_a_successful_nonempty_explicit_contract_verdict(self):
        job = self.workflow.split("  gitops-pr:\n", 1)[1]
        condition = re.search(r"^    if: \|\n((?:      .*\n)+)", job, re.MULTILINE).group(1)
        condition = condition.replace("&&", " and ").replace("||", " or ")
        condition = condition.replace("needs.build-push", "build").replace("needs.can-i-deploy", "gate")
        cases = [("success", "true", f'["{HANDOFF.COMPONENT}"]', True),
                 ("success", "", f'["{HANDOFF.COMPONENT}"]', False),
                 ("success", "true", "", False), ("success", "true", "[]", False)]
        cases += [(status, "true", f'["{HANDOFF.COMPONENT}"]', False)
                  for status in ("failure", "cancelled", "skipped")]
        for status, ran, deployable, expected in cases:
            with self.subTest(status=status, ran=ran, deployable=deployable):
                context = {
                    "always": lambda: True,
                    "github": SimpleNamespace(event_name="workflow_run",
                                              event=SimpleNamespace(inputs=SimpleNamespace(bootstrap_image_only=""))),
                    "needs": SimpleNamespace(changes=SimpleNamespace(outputs=SimpleNamespace(any="true"))),
                    "build": SimpleNamespace(result="success", outputs=SimpleNamespace(deployable_services='["service"]')),
                    "gate": SimpleNamespace(result=status, outputs=SimpleNamespace(gate_ran=ran, deployable=deployable)),
                }
                self.assertEqual(eval("(" + condition + ")", {"__builtins__": {}}, context), expected)


if __name__ == "__main__":
    unittest.main()
