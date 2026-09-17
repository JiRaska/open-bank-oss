#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Evaluate the DR namespace bootstrap with a supplied Kyverno CLI, offline.

Run: python3 .github/scripts/test-dr-network-policy-generation.py --kyverno /path/to/kyverno
This is policy evaluation, not proof that a cluster installed or enforced the policy.
"""
import argparse
from pathlib import Path
import subprocess
import tempfile

import yaml

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kyverno', required=True)
    args = parser.parse_args()
    source = ROOT / 'openbank-infra/gitops/components/platform/dr-runner-rbac.yaml'
    documents = list(yaml.safe_load_all(source.read_text()))
    policy = next(d for d in documents if d['metadata']['name'] == 'ledger-dr-check-network-isolation')
    grants = [r for r in documents[0]['rules'] if 'networkpolicies' in r['resources']]
    assert grants == [{'apiGroups': ['networking.k8s.io'], 'resources': ['networkpolicies'],
                       'resourceNames': ['ledger-dr-check-isolation'], 'verbs': ['get']}]
    cases = [(ns, False) for ns in ('ledger', 'kube-system', 'kyverno', 'dr-verify-', 'dr-verify')]
    cases += [('dr-verify-123-1', True), ('dr-verify-456-2', True)]
    with tempfile.TemporaryDirectory(prefix='dr-policy-generation-') as directory:
        root = Path(directory)
        (root / 'policy.yaml').write_text(yaml.safe_dump(policy))
        for namespace, generated in cases:
            resource = dict(apiVersion='v1', kind='Namespace', metadata=dict(name=namespace))
            (root / 'resource.yaml').write_text(yaml.safe_dump(resource))
            if not generated:
                # Older CLI versions omit unmatched generate rules from `test` results.
                # `apply` must instead report exactly zero matched rules, never a pass.
                result = subprocess.run([str(Path(args.kyverno).resolve()), 'apply', str(root / 'policy.yaml'),
                                         '--resource', str(root / 'resource.yaml')],
                                        capture_output=True, text=True, timeout=30)
                assert result.returncode == 0 and 'pass: 0, fail: 0, warn: 0, error: 0, skip: 0' in result.stdout, result.stdout + result.stderr
                print(f'PASS {namespace}: unaffected')
                continue
            expected = dict(apiVersion='networking.k8s.io/v1', kind='NetworkPolicy',
                            metadata=dict(name='ledger-dr-check-isolation', namespace=namespace),
                            spec=policy['spec']['rules'][0]['generate']['data']['spec'])
            (root / 'expected.yaml').write_text(yaml.safe_dump(expected))
            check = dict(policy=policy['metadata']['name'], rule='bootstrap-dr-check-isolation',
                         resources=[namespace], kind='Namespace', result='pass' if generated else 'skip')
            if generated:
                check['generatedResource'] = 'expected.yaml'
            manifest = dict(apiVersion='cli.kyverno.io/v1alpha1', kind='Test', metadata=dict(name='dr-bootstrap'),
                            policies=['policy.yaml'], resources=['resource.yaml'], results=[check])
            (root / 'kyverno-test.yaml').write_text(yaml.safe_dump(manifest))
            result = subprocess.run([str(Path(args.kyverno).resolve()), 'test', str(root)],
                                    capture_output=True, text=True, timeout=30)
            assert result.returncode == 0 and '1 tests passed and 0 tests failed' in result.stdout, result.stdout + result.stderr
            print(f'PASS {namespace}: {"generated" if generated else "unaffected"}')
    print(f'{len(cases)} namespace bootstrap cases passed; no cluster requests made')


if __name__ == '__main__':
    main()
