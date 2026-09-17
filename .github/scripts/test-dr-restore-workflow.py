#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Execute the real DR shell step against isolated fake Kubernetes/HTTP boundaries.

This verifies orchestration and cleanup only; it is not evidence of a database restore.
"""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / '.github/workflows/dr-restore-verify.yml'
KUBECTL = r'''
import json, os, pathlib, signal, sys, time
import yaml
root = pathlib.Path(os.environ['DR_TEST_STATE'])
args = sys.argv[1:]
with (root / 'calls').open('a') as log:
    log.write(json.dumps(args) + '\n')
if args[:2] == ['create', 'namespace']:
    sys.exit(1 if os.environ['DR_TEST_CASE'] == 'collision' else 0)
if args[:2] == ['delete', 'namespace'] and os.environ['DR_TEST_CASE'] == 'delete-failure':
    sys.exit(1)
if args[:2] == ['get', 'rollout']:
    print('registry.invalid/ledger:fixture')
elif args[:2] in (['apply', '-f'], ['create', '-f']):
    path = root / 'resources'
    docs = json.loads(path.read_text()) if path.exists() else []
    incoming = [d for d in yaml.safe_load_all(sys.stdin) if d]
    if any(d['kind'] == 'NetworkPolicy' for d in incoming) and os.environ['DR_TEST_CASE'] == 'policy-failure':
        sys.exit(1)
    if any(d['kind'] == 'Deployment' for d in incoming):
        if ['networkpolicy', 'ledger-dr-check-isolation'] not in docs:
            sys.exit(1)
    docs += [[d['kind'].lower(), d['metadata']['name']] for d in incoming]
    path.write_text(json.dumps(docs))
elif 'port-forward' in args:
    target = args[args.index('port-forward') + 1]
    kind, name = target.split('/', 1)
    kind = {'svc': 'service', 'deploy': 'deployment'}.get(kind, kind)
    if [kind, name] not in json.loads((root / 'resources').read_text()):
        sys.exit(1)
    (root / 'forward-ready').touch()
    def stop(*_):
        (root / 'forward-stopped').touch()
        sys.exit(0)
    signal.signal(signal.SIGTERM, stop)
    while True:
        time.sleep(0.01)
'''
CURL = r'''
import os, pathlib, sys, time
root = pathlib.Path(os.environ['DR_TEST_STATE'])
# Model connection retry only when the actual invocation enables it.
tries = 100 if '--retry-connrefused' in sys.argv else 1
for _ in range(tries):
    if (root / 'forward-ready').exists():
        break
    time.sleep(0.01)
else:
    sys.exit(7)
case = os.environ['DR_TEST_CASE']
if case == 'http-failure':
    sys.exit(22)
print({'string-boolean': '{"balanced":"True"}',
       'unbalanced': '{"balanced":false}'}.get(case, '{"balanced":true}'))
'''


class DrRestoreWorkflowTest(unittest.TestCase):
    def run_step(self, case):
        doc = yaml.safe_load(WORKFLOW.read_text())
        step = next(s for s in doc['jobs']['restore-verify']['steps']
                    if s.get('name') == 'Restore, verify, teardown')
        with tempfile.TemporaryDirectory(prefix='dr-workflow-test-') as tmp:
            root = Path(tmp)
            for command, source in [('kubectl', KUBECTL), ('curl', CURL),
                                    ('sleep', 'import time; time.sleep(0.05)')]:
                path = root / command
                path.write_text(f'#!{sys.executable}\n' + source)
                path.chmod(0o755)
            env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH'],
                       DR_TEST_STATE=tmp, DR_TEST_CASE=case, GITHUB_RUN_ID='987654',
                       GITHUB_RUN_ATTEMPT='2', GITHUB_STEP_SUMMARY=str(root / 'summary'),
                       FISCAL_YEAR='2026')
            result = subprocess.run(['bash', '-c', step['run']], cwd=ROOT, env=env,
                                    capture_output=True, text=True, timeout=15)
            calls = [json.loads(s) for s in (root / 'calls').read_text().splitlines()]
            return result, calls, (root / 'forward-stopped').exists()

    def test_restore_check_reaches_the_workload_created_by_templates(self):
        result, calls, stopped = self.run_step('success')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(any(c[:2] == ['delete', 'namespace'] for c in calls))
        self.assertTrue(stopped, 'port-forward must be reaped during cleanup')

    def test_policy_failure_aborts_before_restoring_or_starting_workloads(self):
        result, calls, stopped = self.run_step('policy-failure')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any(c[:2] == ['apply', '-f'] for c in calls))
        self.assertFalse(any('port-forward' in c for c in calls))
        self.assertTrue(any(c[:2] == ['delete', 'namespace'] for c in calls))
        self.assertFalse(stopped)

    def test_isolation_selects_check_pod_and_only_restored_database_and_dns(self):
        templates = ROOT / 'openbank-infra/gitops/dr-restore-templates'
        policy = yaml.safe_load((templates / 'ledger-check-network-policy.yaml.tmpl').read_text())['spec']
        pod = yaml.safe_load((templates / 'ledger-service-dr-check.yaml.tmpl').read_text())['spec']['template']
        self.assertEqual(policy['podSelector']['matchLabels'], pod['metadata']['labels'])
        self.assertEqual(set(policy['policyTypes']), {'Ingress', 'Egress'})
        self.assertEqual(policy['ingress'], [])
        self.assertEqual(policy['egress'], [
            {'to': [{'podSelector': {'matchLabels': {'cnpg.io/cluster': 'ledger-db-restored'}}}],
             'ports': [{'protocol': 'TCP', 'port': 5432}]},
            {'to': [{'namespaceSelector': {'matchLabels': {'kubernetes.io/metadata.name': 'kube-system'}},
                     'podSelector': {'matchLabels': {'k8s-app': 'kube-dns'}}}],
             'ports': [{'protocol': 'UDP', 'port': 53}, {'protocol': 'TCP', 'port': 53}]}])
        self.assertIs(pod['spec']['automountServiceAccountToken'], False)
        roles = list(yaml.safe_load_all((ROOT / 'openbank-infra/gitops/components/platform/dr-runner-rbac.yaml').read_text()))
        grants = [r for r in roles[0]['rules'] if 'networkpolicies' in r['resources']]
        self.assertEqual(grants, [{'apiGroups': ['networking.k8s.io'], 'resources': ['networkpolicies'], 'verbs': ['create']}])

    def test_cleanup_failure_is_not_a_successful_drill(self):
        result, _, stopped = self.run_step('delete-failure')
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue(stopped)

    def test_existing_namespace_is_never_deleted(self):
        result, calls, _ = self.run_step('collision')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any(c[:2] == ['delete', 'namespace'] for c in calls))

    def test_failed_verification_cleans_only_its_resources(self):
        for case in ['http-failure', 'unbalanced', 'string-boolean']:
            with self.subTest(case=case):
                result, calls, stopped = self.run_step(case)
                self.assertNotEqual(result.returncode, 0)
                self.assertTrue(any(c[:2] == ['delete', 'namespace'] for c in calls))
                self.assertTrue(stopped, 'failed checks must reap port-forward too')
                self.assertNotIn('restore verified:', result.stdout)


if __name__ == '__main__':
    suite = unittest.main(exit=False)
    print(f'SUBJECTS={int(WORKFLOW.is_file())}  # executed DR workflow')
    sys.exit(0 if suite.result.wasSuccessful() else 1)
