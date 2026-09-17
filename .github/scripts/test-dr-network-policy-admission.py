#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Evaluate the native DR admission boundary with a supplied Kyverno CLI, offline.

Run: python3 .github/scripts/test-dr-network-policy-admission.py --kyverno /path/to/kyverno
This is policy evaluation, not proof that a cluster installed or enforced the policy.
"""
import argparse
from pathlib import Path
import subprocess
import tempfile

import yaml

ROOT = Path(__file__).resolve().parents[2]
DR_USER = 'system:serviceaccount:arc-runners:openbank-dr'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kyverno', required=True)
    args = parser.parse_args()
    source = ROOT / 'openbank-infra/gitops/components/platform/dr-runner-rbac.yaml'
    documents = list(yaml.safe_load_all(source.read_text()))
    policy = next(d for d in documents if d['kind'] == 'ValidatingAdmissionPolicy')
    binding = next(d for d in documents if d['kind'] == 'ValidatingAdmissionPolicyBinding')
    assert policy['spec']['failurePolicy'] == 'Fail'
    assert binding['spec'] == {'policyName': policy['metadata']['name'], 'validationActions': ['Deny']}
    assert int(policy['metadata']['annotations']['argocd.argoproj.io/sync-wave']) < -1
    assert int(binding['metadata']['annotations']['argocd.argoproj.io/sync-wave']) == -1
    cases = [(DR_USER, ns, False) for ns in ('ledger', 'kube-system', 'kyverno', 'dr-verify-', 'dr-verify')]
    cases += [(DR_USER, 'dr-verify-123-1', True), ('system:serviceaccount:platform:operator', 'ledger', True)]
    with tempfile.TemporaryDirectory(prefix='dr-native-admission-') as directory:
        root = Path(directory)
        (root / 'policy.yaml').write_text(yaml.safe_dump_all([policy, binding]))
        for user, namespace, allowed in cases:
            resource = dict(apiVersion='networking.k8s.io/v1', kind='NetworkPolicy',
                            metadata=dict(name='allow-all-fixture', namespace=namespace),
                            spec=dict(podSelector={}, policyTypes=['Ingress', 'Egress'], ingress=[{}], egress=[{}]))
            (root / 'resource.yaml').write_text(yaml.safe_dump(resource))
            (root / 'user.yaml').write_text(yaml.safe_dump(dict(userInfo=dict(username=user))))
            result = subprocess.run([str(Path(args.kyverno).resolve()), 'apply', str(root / 'policy.yaml'),
                                     '--resource', str(root / 'resource.yaml'), '--userinfo', str(root / 'user.yaml')],
                                    capture_output=True, text=True, timeout=30)
            expected = 'pass: 1, fail: 0, warn: 0, error: 0, skip: 0' if allowed else 'pass: 0, fail: 1, warn: 0, error: 0, skip: 0'
            assert result.returncode == (0 if allowed else 1) and expected in result.stdout, result.stdout + result.stderr
            print(f'PASS {namespace}: {"allow" if allowed else "deny"}')
    print(f'{len(cases)} native admission cases passed; no cluster requests made')


if __name__ == '__main__':
    main()
