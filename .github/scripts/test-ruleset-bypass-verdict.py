#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Run the real bypass checker and gate runner against synthetic GitHub responses."""
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import textwrap
import unittest
from unittest.mock import patch

import yaml

ROOT = Path(__file__).resolve().parents[2]
GATE_ID = "ruleset-bypass-actors"
SUMMARY = {"id": 1, "name": "fixture", "target": "branch", "enforcement": "active"}
CONDITIONS = {"ref_name": {"include": ["refs/heads/main"]}}
EMPTY = {"conditions": CONDITIONS, "bypass_actors": []}


class BypassVerdictTest(unittest.TestCase):
    def test_ci_app_identity_without_secret_access_preserves_live_pin(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/ci.yml").read_text())
        expression = workflow["jobs"]["gates"]["env"]["GITOPS_APP_ID"]
        # Deliberately only string-valued vars/secrets projections and their OR;
        # this is not a general Actions evaluator. Unknown syntax fails the test.
        self.assertRegex(expression, r"^\$\{\{\s*(?:vars|secrets)\.GITOPS_APP_ID"
                                    r"(?:\s*\|\|\s*(?:vars|secrets)\.GITOPS_APP_ID)*\s*\}\}$")
        sources = re.findall(r"(vars|secrets)\.GITOPS_APP_ID", expression)
        spec = importlib.util.spec_from_file_location(
            "deploy_hold_guard", ROOT / ".github/scripts/check-deploy-holds.py")
        guard = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(guard)
        cases = [
            ("dependabot-public-id", "4242", "", 4242, True),
            ("legacy-secret-id", "", "4242", 4242, True),
            ("both-present", "4242", "4242", 4242, True),
            ("both-missing", "", "", 4242, False),
            ("wrong-public-no-secret-fallback", "99", "4242", 4242, False),
            ("invalid-public-no-secret-fallback", "invalid", "4242", 4242, False),
            ("wrong-secret", "", "99", 4242, False),
            ("unpinned-context", "4242", "", None, False),
            ("another-app-pin", "4242", "", 99, False),
        ]
        with tempfile.TemporaryDirectory(prefix="ci-app-id-") as tmp:
            root = Path(tmp)
            rules = root / guard.RULES
            rules.parent.mkdir(parents=True)
            rules.write_text("deploy_holds:\n  services:\n    openbank-ledger-service:\n"
                             "      issue: '#1'\n      reason: fixture\n"
                             "      release_condition: reviewed image\n")
            for name, public_id, secret_id, integration, allowed in cases:
                context = {"vars": public_id, "secrets": secret_id}
                resolved = next((context[source] for source in sources if context[source]), "")
                checks = {"context": "merge-train"}
                if integration is not None:
                    checks["integration_id"] = integration
                response = [{"type": "required_status_checks", "parameters": {
                    "strict_required_status_checks_policy": False,
                    "required_status_checks": [checks],
                }}]
                result = subprocess.CompletedProcess([], 0, stdout=json.dumps(response), stderr="")
                with self.subTest(case=name), patch.dict(os.environ, {"GITOPS_APP_ID": resolved}), \
                        patch.object(guard.subprocess, "run", return_value=result) as read:
                    if allowed:
                        guard.require_live_strict(root, None, "fixture/bank")
                    else:
                        with self.assertRaises(guard.InvalidHold):
                            guard.require_live_strict(root, None, "fixture/bank")
                    read.assert_called_once_with(
                        ["gh", "api", "repos/fixture/bank/rules/branches/main"],
                        capture_output=True, text=True, timeout=30, check=False)

    def test_checker_runner_and_json_agree(self):
        gate = next(g for g in yaml.safe_load((ROOT / ".github/gates/gates.yaml").read_text())["gates"]
                    if g["id"] == GATE_ID)
        # This test is part of the gate's self-test; do not recursively invoke itself.
        gate.pop("selftest", None)
        gate.pop("selftest_expect", None)
        cases = [
            ("verified-empty", [SUMMARY], EMPTY, "ok", None, 1),
            ("missing-permission-field", [SUMMARY], {"conditions": CONDITIONS},
             "failed", "not_evaluated", "UNRESOLVED"),
            ("permission-denied", [SUMMARY], {"error": "Resource not accessible by integration (HTTP 403)"},
             "failed", "not_evaluated", "UNRESOLVED"),
            ("rate-limit", [SUMMARY], {"error": "API rate limit exceeded (HTTP 403)"},
             "failed", "not_evaluated", "UNRESOLVED"),
            ("bad-credentials", [SUMMARY], {"error": "Bad credentials (HTTP 401)"},
             "failed", "not_evaluated", "UNRESOLVED"),
            ("null-list", [SUMMARY], {"conditions": CONDITIONS, "bypass_actors": None},
             "failed", "not_evaluated", "UNRESOLVED"),
            ("object-instead-of-list", [SUMMARY], {"conditions": CONDITIONS, "bypass_actors": {}},
             "failed", "not_evaluated", "UNRESOLVED"),
            ("undeclared-actor", [SUMMARY], {"conditions": CONDITIONS, "bypass_actors": [
                {"actor_id": 999, "actor_type": "Team", "bypass_mode": "always"}]},
             "failed", "content", 1),
            ("no-rulesets", [], EMPTY, "failed", "content", 0),
            ("inactive-ruleset", [{**SUMMARY, "enforcement": "disabled"}], EMPTY,
             "failed", "content", 0),
            ("unrelated-ref", [SUMMARY], {**EMPTY, "conditions": {"ref_name": {"include": ["refs/heads/other"]}}},
             "failed", "content", 0),
            ("second-ruleset-unreadable", [SUMMARY, {**SUMMARY, "id": 2}], EMPTY,
             "failed", "not_evaluated", "UNRESOLVED"),
        ]
        snapshot = {"version": 1, "repository": "fixture/bank", "run_id": "123", "run_attempt": "2",
                    "summaries": [SUMMARY], "details": {"1": EMPTY}}
        snapshot_cases = [
            ("snapshot-empty", snapshot, "ok", None, 1),
            ("snapshot-missing", None, "failed", "not_evaluated", "UNRESOLVED"),
            ("snapshot-malformed", "{", "failed", "not_evaluated", "UNRESOLVED"),
            ("snapshot-other-repo", {**snapshot, "repository": "other/bank"}, "failed", "not_evaluated", "UNRESOLVED"),
            ("snapshot-old-run", {**snapshot, "run_id": "122"}, "failed", "not_evaluated", "UNRESOLVED"),
            ("snapshot-old-attempt", {**snapshot, "run_attempt": "1"}, "failed", "not_evaluated", "UNRESOLVED"),
            ("snapshot-no-detail", {**snapshot, "details": {}}, "failed", "not_evaluated", "UNRESOLVED"),
            ("snapshot-missing-field", {**snapshot, "details": {"1": {"conditions": CONDITIONS}}},
             "failed", "not_evaluated", "UNRESOLVED"),
            ("snapshot-no-rulesets", {**snapshot, "summaries": []}, "failed", "content", 0),
            ("snapshot-undeclared", {**snapshot, "details": {"1": {**EMPTY, "bypass_actors": [
                {"actor_id": 999, "actor_type": "Team", "bypass_mode": "always"}]}}}, "failed", "content", 1),
        ]
        cases += [(name, None, payload, status, kind, subjects)
                  for name, payload, status, kind, subjects in snapshot_cases]
        with tempfile.TemporaryDirectory(prefix="bypass-verdict-test-") as tmp:
            directory = Path(tmp)
            manifest = directory / "gates.yaml"
            manifest.write_text("gates:\n" + textwrap.indent(yaml.safe_dump([gate], sort_keys=False), "  "))
            stub = directory / "gh"
            stub.write_text(f"#!{sys.executable}\n" + '''import json, os, sys
from pathlib import Path
responses = json.loads(Path(os.environ["BYPASS_TEST_RESPONSES"]).read_text())
key = sys.argv[-1].split("/rulesets", 1)[1]
value = responses[key]
if isinstance(value, dict) and "error" in value:
    print(value["error"], file=sys.stderr)
    sys.exit(1)
print(json.dumps(value))
''')
            stub.chmod(0o755)
            fixture = directory / "responses.json"
            evidence = directory / "evidence.json"
            for name, summaries, detail, status, kind, subjects in cases:
                with self.subTest(case=name):
                    fixture.write_text(json.dumps({"": summaries, "/1": detail,
                                                   "/2": {"conditions": CONDITIONS}}))
                    env = {**os.environ, "PATH": f"{tmp}:{os.environ['PATH']}", "CI": "false",
                           "BYPASS_TEST_RESPONSES": str(fixture)}
                    env.pop("RULESET_BYPASS_SNAPSHOT", None)
                    env.pop("GITHUB_ACTIONS", None)
                    if name.startswith("snapshot-"):
                        env.update({"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": "fixture/bank",
                                    "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "2"})
                        if detail is not None:
                            env["RULESET_BYPASS_SNAPSHOT"] = detail if isinstance(detail, str) else json.dumps(detail)
                        # Even a successful live fallback would be wrong for missing CI evidence.
                        fixture.write_text(json.dumps({"": [SUMMARY], "/1": EMPTY}))
                    result = subprocess.run(
                        [sys.executable, str(ROOT / ".github/scripts/run-gates.py"),
                         "--root", str(ROOT), "--manifest", str(manifest), "--all",
                         "--jobs", "1", "--json", str(evidence)],
                        env=env, capture_output=True, text=True, timeout=30,
                    )
                    output = result.stdout + result.stderr
                    self.assertEqual(result.returncode, 0 if status == "ok" else 1, output)
                    record, = json.loads(evidence.read_text())
                    self.assertEqual((record["status"], record["failure_kind"], record["subjects"]),
                                     (status, kind, subjects), output)
                    verdicts = output.split("--- verdicts (1 gates) ---", 1)[1]
                    self.assertIn("PASS" if status == "ok" else "FAIL", verdicts)
                    if kind == "not_evaluated":
                        self.assertNotIn("PASS", verdicts)
                        self.assertIn("(not evaluated)", verdicts)
                    if name == "verified-empty":
                        self.assertIn("0 live bypass actor(s), 0 undeclared, 0 stale", output)

    def test_consumer_provenance_and_binding(self):
        workflow = yaml.safe_load((ROOT / '.github/workflows/ci.yml').read_text())
        job = workflow['jobs']['ruleset-evidence']
        self.assertEqual(job['permissions'], {'actions': 'read'})
        self.assertNotIn('secrets.', json.dumps(job))
        self.assertNotIn('continue-on-error', json.dumps(job))
        self.assertIn('ruleset-evidence', workflow['jobs']['gates']['needs'])
        self.assertIn('always()', workflow['jobs']['gates']['if'])
        self.assertIn('ruleset-evidence', workflow['jobs']['validate']['needs'])
        locate, download, read = job['steps']
        self.assertEqual(download['with']['artifact-ids'], '${{ steps.locate.outputs.artifact-id }}')
        self.assertEqual(download['with']['run-id'], '${{ steps.locate.outputs.run-id }}')
        harness = r'''
const input = JSON.parse(require('fs').readFileSync(0, 'utf8'));
const outputs = {};
let clock = 0;
const calls = [];
const sleeps = [];
const read = (key, value, args) => {
  calls.push({key, clock, timeout: args.request?.timeout});
  clock += (input.responseDelay || {})[key] || 0;
  const failures = (input.failures || {})[key] || [];
  if (failures.length) {
    const failure = failures.shift();
    const error = new Error('Fixture API failure');
    error.status = failure.status;
    error.response = {headers: failure.headers || {}};
    throw error;
  }
  return {data: value};
};
const github = {rest: {actions: {
  getWorkflow: async args => read('workflow', {id: 10}, args),
  listArtifactsForRepo: async args => read('artifacts', {
    artifacts: clock < (input.artifactAfter || 0) ? [] : [input.artifact]}, args),
  getWorkflowRun: async args => read('producer', input.run, args)
}}};
const context = {repo: {owner: 'fixture', repo: 'bank'}};
const mockRequire = name => {
  if (name !== 'fs') throw new Error('Unexpected import');
  return {statSync: () => ({size: input.size || 100}), readFileSync: () => JSON.stringify(input.snapshot)};
};
const AsyncFunction = Object.getPrototypeOf(async function(){}).constructor;
(async () => {
  let error = null;
  try {
    await new AsyncFunction('github', 'context', 'core', 'require', 'Date', 'setTimeout', input.script)(
      github, context, {setOutput: (key, value) => {outputs[key] = value;}}, mockRequire,
      {now: () => clock}, (cb, ms) => {sleeps.push(ms); clock += ms; cb();});
  } catch (e) {error = e.message;}
  console.log(JSON.stringify({error, outputs, calls, sleeps, clock}));
})();
'''
        artifact = {'id': 20, 'name': 'ruleset-bypass-123-a2', 'expired': False, 'workflow_run': {'id': 900}}
        run = {'id': 900, 'run_attempt': 1, 'workflow_id': 10,
               'path': '.github/workflows/ruleset-bypass-evidence.yml',
               'event': 'workflow_run', 'status': 'completed', 'conclusion': 'success'}
        snapshot = {'version': 1, 'repository': 'fixture/bank', 'run_id': '123', 'run_attempt': '2',
                    'source_run_id': '900', 'source_run_attempt': '1', 'summaries': [], 'details': {}}
        cases = [('trusted', locate, {}, False)]
        for key, value in [('workflow_id', 99), ('path', '.github/workflows/ci.yml'),
                           ('event', 'pull_request'), ('status', 'in_progress'), ('conclusion', 'failure')]:
            cases.append((f'wrong-producer-{key}', locate, {'run': {**run, key: value}}, True))
        for key, value in [('expired', True), ('name', 'ruleset-bypass-122-a2'), ('workflow_run', None)]:
            cases.append((f'wrong-artifact-{key}', locate, {'artifact': {**artifact, key: value}}, True))
        quota = {'status': 403, 'headers': {'x-ratelimit-remaining': '0', 'x-ratelimit-reset': '120'}}
        for endpoint in ('workflow', 'artifacts', 'producer'):
            cases.append((f'quota-{endpoint}', locate, {'failures': {endpoint: [quota]},
                          'expect': {'clock': 121000, 'retry': endpoint}}, False))
        cases += [
            ('late-collector-22-minutes', locate, {'artifactAfter': 22 * 60 * 1000,
             'expect': {'min_clock': 22 * 60 * 1000, 'max_reads': 40}}, False),
            ('collector-after-deadline', locate, {'artifactAfter': 30 * 60 * 1000 + 1,
             'expect': {'clock': 30 * 60 * 1000, 'max_reads': 40}}, True),
            # A proven quota exhaustion waits for the hourly reset past the 30-minute
            # evidence window, up to the 62-minute hard cap (40851745901 failed 14 min short).
            ('quota-reset-after-deadline', locate, {'failures': {'workflow': [
             {**quota, 'headers': {**quota['headers'], 'x-ratelimit-reset': '1900'}}]},
             'expect': {'clock': 1901000, 'workflow_reads': 2}}, False),
            ('quota-reset-beyond-hard-cap', locate, {'failures': {'workflow': [
             {**quota, 'headers': {**quota['headers'], 'x-ratelimit-reset': '3720'}}]},
             'expect': {'clock': 0, 'workflow_reads': 1}}, True),
            ('response-at-hard-cap', locate, {'failures': {'workflow': [
             {**quota, 'headers': {**quota['headers'], 'x-ratelimit-reset': '3704'}}]},
             'responseDelay': {'producer': 15000},
             'expect': {'clock': 62 * 60 * 1000, 'workflow_reads': 2}}, True),
            ('permission-denied', locate, {'failures': {'workflow': [{'status': 403}]},
             'expect': {'clock': 0, 'workflow_reads': 1}}, True),
            ('403-with-budget', locate, {'failures': {'workflow': [
             {**quota, 'headers': {**quota['headers'], 'x-ratelimit-remaining': '1'}}]},
             'expect': {'clock': 0, 'workflow_reads': 1}}, True),
            ('quota-invalid-reset', locate, {'failures': {'workflow': [
             {**quota, 'headers': {**quota['headers'], 'x-ratelimit-reset': 'invalid'}}]},
             'expect': {'clock': 0, 'workflow_reads': 1}}, True),
            ('quota-retries-bounded', locate, {'failures': {'workflow': [quota] * 4},
             'expect': {'clock': 123000, 'workflow_reads': 4}}, True),
        ]
        cases.append(('bound', read, {}, False))
        for key in ('repository', 'run_id', 'run_attempt', 'source_run_id', 'source_run_attempt', 'version'):
            cases.append((f'wrong-binding-{key}', read, {'snapshot': {**snapshot, key: 'wrong'}}, True))
        cases.append(('oversized', read, {'size': 256 * 1024 + 1}, True))
        for name, step, override, fails in cases:
            with self.subTest(case=name):
                expected = override.get('expect', {})
                result = subprocess.run(['node', '-e', harness], input=json.dumps({
                    'script': step['with']['script'], 'artifact': artifact, 'run': run,
                    'snapshot': snapshot, **override}), text=True, capture_output=True, timeout=10,
                    env={**os.environ, 'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '2',
                         'SOURCE_RUN_ID': '900', 'SOURCE_RUN_ATTEMPT': '1', 'RUNNER_TEMP': '/tmp'})
                self.assertEqual(result.returncode, 0, result.stderr)
                output = json.loads(result.stdout)
                self.assertEqual(output['error'] is not None, fails, output)
                if 'clock' in expected:
                    self.assertEqual(output['clock'], expected['clock'], output)
                if 'min_clock' in expected:
                    self.assertGreaterEqual(output['clock'], expected['min_clock'], output)
                artifact_reads = [c for c in output['calls'] if c['key'] == 'artifacts']
                workflow_reads = [c for c in output['calls'] if c['key'] == 'workflow']
                if 'max_reads' in expected:
                    self.assertLessEqual(len(artifact_reads), expected['max_reads'], output)
                if 'workflow_reads' in expected:
                    self.assertEqual(len(workflow_reads), expected['workflow_reads'], output)
                if 'retry' in expected:
                    reads = [c for c in output['calls'] if c['key'] == expected['retry']]
                    self.assertEqual(len(reads), 2, output)
                    self.assertEqual(reads[1]['clock'], 121000, output)
                self.assertTrue(all(c['clock'] < 62 * 60 * 1000 for c in output['calls']), output)
                if fails:
                    self.assertEqual(output['outputs'], {})
                elif step is locate:
                    self.assertEqual(output['outputs'], {'artifact-id': '20', 'run-id': '900', 'run-attempt': '1'})
                else:
                    self.assertEqual(json.loads(output['outputs']['snapshot']), snapshot)
        self.assertEqual(job['timeout-minutes'], 65)
        guard = next(s['run'] for s in workflow['jobs']['validate']['steps']
                     if s['name'] == 'Verify ruleset evidence was collected')
        for status in ('success', 'failure', 'skipped', 'cancelled', ''):
            result = subprocess.run(['bash', '-c', guard], env={**os.environ, 'RESULT': status},
                                    text=True, capture_output=True, timeout=5)
            self.assertEqual(result.returncode, 0 if status == 'success' else 1)


if __name__ == "__main__":
    unittest.main()
