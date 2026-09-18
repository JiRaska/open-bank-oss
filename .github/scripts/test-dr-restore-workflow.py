#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Execute the real DR shell step against isolated fake Kubernetes/HTTP boundaries.

This verifies orchestration and cleanup only; it is not evidence of a database restore.
"""
import base64
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / '.github/workflows/dr-restore-verify.yml'
KUBECTL = r'''
import json, os, pathlib, signal, sys, time
root = pathlib.Path(os.environ['DR_TEST_STATE'])
args = sys.argv[1:]
with (root / 'calls').open('a') as log:
    log.write(json.dumps(args) + '\n')
if len(args) > 4 and args[2:5] == ['create', 'configmap', 'ledger-dr-check-auth']:
    public_file = pathlib.Path(next(a.split('=', 2)[2] for a in args if a.startswith('--from-file=public-key=')))
    properties = pathlib.Path(next(a.split('=', 2)[2] for a in args if a.startswith('--from-file=dr-check.properties=')))
    assert properties.is_file()
    assert public_file.name == 'public-key'
    assert 'PRIVATE' not in public_file.read_text() and 'Bearer' not in public_file.read_text()
    (root / 'auth-directory').write_text(str(public_file.parent))
    sys.exit(0)
if args[:2] == ['create', 'namespace']:
    if os.environ['DR_TEST_CASE'] == 'collision':
        sys.exit(1)
    if os.environ['DR_TEST_CASE'] != 'policy-failure':
        (root / 'resources').write_text(json.dumps([['networkpolicy', 'ledger-dr-check-isolation']]))
    sys.exit(0)
if len(args) > 3 and args[2:4] == ['get', 'networkpolicy']:
    sys.exit(1 if os.environ['DR_TEST_CASE'] == 'policy-failure' else 0)
if args[:2] == ['delete', 'namespace'] and os.environ['DR_TEST_CASE'] == 'delete-failure':
    sys.exit(1)
if args[:2] == ['get', 'rollout']:
    print('registry.invalid/ledger:fixture')
elif args[:2] == ['apply', '-f']:
    import yaml
    path = root / 'resources'
    docs = json.loads(path.read_text()) if path.exists() else []
    incoming = [d for d in yaml.safe_load_all(sys.stdin) if d]
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
import json, os, pathlib, sys, time
root = pathlib.Path(os.environ['DR_TEST_STATE'])
# Model connection retry only when the actual invocation enables it.
tries = 100 if '--retry-connrefused' in sys.argv else 1
for _ in range(tries):
    if (root / 'forward-ready').exists():
        break
    time.sleep(0.01)
else:
    sys.exit(7)
header_file = pathlib.Path(sys.argv[sys.argv.index('--header') + 1].removeprefix('@'))
assert header_file.read_text().startswith('Authorization: Bearer ')
case = os.environ['DR_TEST_CASE']
if case == 'http-failure':
    sys.exit(22)
body = {'fiscalYear': 2026, 'balanced': True, 'accountCount': 2,
        'totalDebit': 100.01, 'totalCredit': 100.01}
body.update({'string-boolean': {'balanced': 'True'},
             'unbalanced': {'balanced': False},
             'empty-ledger': {'accountCount': 0, 'totalDebit': 0, 'totalCredit': 0},
             'wrong-year': {'fiscalYear': 2025},
             'mismatched-totals': {'totalCredit': 99.99}}.get(case, {}))
print(json.dumps(body))
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
                                    capture_output=True, text=True, timeout=30)
            calls = [json.loads(s) for s in (root / 'calls').read_text().splitlines()]
            if (root / 'auth-directory').exists():
                self.assertFalse(Path((root / 'auth-directory').read_text()).exists(),
                                 'temporary bearer credentials must be removed on success and failure')
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
        roles = list(yaml.safe_load_all((ROOT / 'openbank-infra/gitops/components/platform/dr-runner-rbac.yaml').read_text()))
        generator = next(d for d in roles if d['metadata']['name'] == 'ledger-dr-check-network-isolation')
        policy = generator['spec']['rules'][0]['generate']['data']['spec']
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
        grants = [r for r in roles[0]['rules'] if 'networkpolicies' in r['resources']]
        self.assertEqual(grants, [{'apiGroups': ['networking.k8s.io'], 'resources': ['networkpolicies'], 'resourceNames': ['ledger-dr-check-isolation'], 'verbs': ['get']}])

    def test_ephemeral_credentials_are_viewer_only_signed_and_private(self):
        spec = importlib.util.spec_from_file_location('dr_credentials', ROOT / '.github/scripts/dr-check-credentials.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            before = int(time.time())
            module.generate(directory)
            after = int(time.time())
            self.assertEqual({p.name for p in directory.iterdir()}, {'public-key', 'authorization-header'})
            self.assertEqual(directory.stat().st_mode & 0o777, 0o700)
            self.assertEqual((directory / 'authorization-header').stat().st_mode & 0o777, 0o600)
            token = (directory / 'authorization-header').read_text().strip().removeprefix('Authorization: Bearer ')
            header, body, signature = token.split('.')
            decode = lambda value: base64.urlsafe_b64decode(value + '=' * (-len(value) % 4))
            claims = json.loads(decode(body))
            self.assertEqual(claims['groups'], ['ROLE_VIEWER'])
            self.assertGreaterEqual(claims['iat'], before)
            self.assertLessEqual(claims['iat'], after)
            self.assertEqual(claims['exp'] - claims['iat'], 3600)
            self.assertEqual(claims['iss'], 'urn:openbank:dr-check')
            self.assertEqual(claims['aud'], 'openbank-dr-check')
            key = directory / 'key.der'
            key.write_bytes(base64.b64decode((directory / 'public-key').read_text()))
            sig = directory / 'signature'
            sig.write_bytes(decode(signature))
            command = ['openssl', 'dgst', '-sha256', '-verify', str(key), '-keyform', 'DER', '-signature', str(sig)]
            self.assertEqual(subprocess.run(command, input=f'{header}.{body}'.encode(), capture_output=True).returncode, 0)
            self.assertNotEqual(subprocess.run(command, input=f'{header}.{body}x'.encode(), capture_output=True).returncode, 0)

    def test_check_workload_mounts_its_scoped_runtime_health_configuration(self):
        templates = ROOT / 'openbank-infra/gitops/dr-restore-templates'
        pod = yaml.safe_load((templates / 'ledger-service-dr-check.yaml.tmpl').read_text())['spec']['template']['spec']
        container = pod['containers'][0]
        env = {item['name']: item.get('value') for item in container['env']}
        self.assertEqual(env['QUARKUS_CONFIG_LOCATIONS'], 'file:/etc/openbank-dr/application.properties')
        mount = next(m for m in container['volumeMounts'] if m['mountPath'] == '/etc/openbank-dr')
        self.assertIs(mount['readOnly'], True)
        volume = next(v for v in pod['volumes'] if v['name'] == mount['name'])
        self.assertEqual(volume['configMap'], {
            'name': 'ledger-dr-check-auth',
            'items': [{'key': 'dr-check.properties', 'path': 'application.properties'}]})
        properties = dict(line.split('=', 1) for line in
                          (templates / 'ledger-dr-check.properties').read_text().splitlines()
                          if line and not line.startswith('#'))
        self.assertEqual(properties, {
            'config_ordinal': '500',
            'quarkus.smallrye-health.check."io.quarkus.redis.runtime.client.health.RedisHealthCheck".enabled': 'false',
            'mp.messaging.outgoing.ledger-events-out.health-enabled': 'false',
        })

    def test_check_workload_uses_only_local_viewer_verification(self):
        template = ROOT / 'openbank-infra/gitops/dr-restore-templates/ledger-service-dr-check.yaml.tmpl'
        pod = yaml.safe_load(template.read_text())['spec']['template']['spec']
        env = {item['name']: item for item in pod['containers'][0]['env']}
        for name, expected in {
            'QUARKUS_OIDC_TENANT_ENABLED': 'true',
            'QUARKUS_OIDC_AUTH_SERVER_URL': '',
            'QUARKUS_OIDC_DISCOVERY_ENABLED': 'false',
            'QUARKUS_OIDC_TOKEN_ISSUER': 'urn:openbank:dr-check',
            'QUARKUS_OIDC_TOKEN_AUDIENCE': 'openbank-dr-check',
            'QUARKUS_OIDC_ROLES_ROLE_CLAIM_PATH': 'groups',
            'QUARKUS_OIDC_CLIENT_CLIENT_ENABLED': 'false',
        }.items():
            with self.subTest(setting=name):
                self.assertEqual(env[name]['value'], expected)
        self.assertEqual(env['QUARKUS_OIDC_PUBLIC_KEY']['valueFrom'],
                         {'configMapKeyRef': {'name': 'ledger-dr-check-auth', 'key': 'public-key'}})

    def test_check_workload_disables_background_database_mutations(self):
        template = ROOT / 'openbank-infra/gitops/dr-restore-templates/ledger-service-dr-check.yaml.tmpl'
        pod = yaml.safe_load(template.read_text())['spec']['template']['spec']
        env = {item['name']: item.get('value') for item in pod['containers'][0]['env']}
        for name in ('QUARKUS_SCHEDULER_ENABLED', 'OPENBANK_OUTBOX_DISPATCH_ENABLED',
                     'QUARKUS_FLYWAY_MIGRATE_AT_START'):
            with self.subTest(setting=name):
                self.assertEqual(env.get(name), 'false')

    def test_restored_checker_receives_only_the_managed_reader_credential(self):
        templates = ROOT / 'openbank-infra/gitops/dr-restore-templates'
        cluster = yaml.safe_load((templates / 'cnpg-recovery-cluster.yaml.tmpl').read_text())
        reader = cluster['spec']['managed']['roles']
        self.assertEqual(reader, [{
            'name': 'ledger_dr_check', 'ensure': 'present', 'login': True, 'inherit': True,
            'superuser': False, 'createdb': False, 'createrole': False,
            'replication': False, 'bypassrls': False, 'inRoles': ['pg_read_all_data'],
            'passwordSecret': {'name': 'ledger-dr-check-db'},
        }])
        pod = yaml.safe_load((templates / 'ledger-service-dr-check.yaml.tmpl').read_text())['spec']['template']['spec']
        env = {item['name']: item for item in pod['containers'][0]['env']}
        for name, key in [('QUARKUS_DATASOURCE_USERNAME', 'username'), ('POSTGRES_PASSWORD', 'password')]:
            self.assertEqual(env[name]['valueFrom'],
                             {'secretKeyRef': {'name': 'ledger-dr-check-db', 'key': key}})
        rbac = list(yaml.safe_load_all((ROOT / 'openbank-infra/gitops/components/platform/dr-runner-rbac.yaml').read_text()))
        self.assertFalse(any('secrets' in rule['resources'] for rule in rbac[0]['rules']))

    def test_recovery_uses_source_archive_and_application_identity(self):
        templates = ROOT / 'openbank-infra/gitops/dr-restore-templates'
        source = next(d for d in yaml.safe_load_all(
            (ROOT / 'openbank-infra/gitops/components/ledger/postgres.yaml').read_text())
            if d and d.get('kind') == 'Cluster')
        restored = yaml.safe_load((templates / 'cnpg-recovery-cluster.yaml.tmpl').read_text())
        recovery = restored['spec']['bootstrap']['recovery']
        external = next(c for c in restored['spec']['externalClusters']
                        if c['name'] == recovery['source'])
        source_store = source['spec']['backup']['barmanObjectStore']
        store = external['barmanObjectStore']
        self.assertEqual(store['destinationPath'], source_store['destinationPath'])
        self.assertEqual(store.get('serverName'),
                         source_store.get('serverName', source['metadata']['name']))
        identity = source['spec']['bootstrap']['initdb']
        self.assertEqual(recovery.get('database'), identity['database'])
        self.assertEqual(recovery.get('owner'), identity['owner'])

    def test_trial_balance_numbers_are_checked_without_float_rounding(self):
        script = ROOT / '.github/scripts/dr-check-trial-balance.py'
        prefix = '{"balanced":true,"fiscalYear":2026,"accountCount":2,'
        cases = [
            ('"totalDebit":0.1,"totalCredit":0.10}', True),
            ('"totalDebit":9007199254740992.01,"totalCredit":9007199254740992.02}', False),
            ('"totalDebit":NaN,"totalCredit":NaN}', False),
            ('"totalDebit":Infinity,"totalCredit":Infinity}', False),
            ('"totalDebit":-1,"totalCredit":-1}', False),
            ('"totalDebit":0,"totalCredit":0}', False),
            ('"totalDebit":true,"totalCredit":true}', False),
            ('"totalDebit":"1","totalCredit":"1"}', False),
            ('"totalDebit":1}', False),
        ]
        for suffix, accepted in cases:
            with self.subTest(suffix=suffix):
                result = subprocess.run([sys.executable, str(script), '2026'],
                                        input=prefix + suffix, capture_output=True, text=True, timeout=5)
                self.assertEqual(result.returncode == 0, accepted, result.stderr)
        for body in ('[]', 'null', '{', '{"balanced":true}',
                     '{"balanced":true,"fiscalYear":2026,"accountCount":true}'):
            with self.subTest(body=body):
                result = subprocess.run([sys.executable, str(script), '2026'],
                                        input=body, capture_output=True, text=True, timeout=5)
                self.assertNotEqual(result.returncode, 0)

    def test_cleanup_failure_is_not_a_successful_drill(self):
        result, _, stopped = self.run_step('delete-failure')
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue(stopped)

    def test_existing_namespace_is_never_deleted(self):
        result, calls, _ = self.run_step('collision')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any(c[:2] == ['delete', 'namespace'] for c in calls))

    def test_failed_verification_cleans_only_its_resources(self):
        for case in ['http-failure', 'unbalanced', 'string-boolean', 'empty-ledger',
                     'wrong-year', 'mismatched-totals']:
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
