#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Evaluate the DR namespace bootstrap GeneratingPolicies with the Kyverno CLI, offline.

Run: python3 .github/scripts/test-dr-network-policy-generation.py [--kyverno /path/to/kyverno]
Without --kyverno it runs the CLI image pinned to the cluster's Kyverno (needs docker),
like openbank-infra/tests/kyverno-cel/run.sh. This is policy evaluation, not proof that
a cluster installed or enforced the policy.

Asserts that BOTH policies generate exactly the expected object for each dr-verify-<id>
namespace and NOTHING for any other name (including the bare prefix). An empty result
set fails: a harness that evaluated nothing is not a pass.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile

import yaml

ROOT = Path(__file__).resolve().parents[2]
POLICY = ROOT / 'openbank-infra/gitops/components/kyverno/dr-check-generating-cel.yaml'
RBAC = ROOT / 'openbank-infra/gitops/components/platform/dr-runner-rbac.yaml'
CLI_IMAGE = os.environ.get('KYVERNO_CLI_IMAGE', 'ghcr.io/kyverno/kyverno-cli:v1.19.1')

MATCHING = ['dr-verify-123-1', 'dr-verify-456-2']
NOT_MATCHING = ['ledger', 'kube-system', 'kyverno', 'dr-verify-', 'dr-verify', 'x-dr-verify-1']

EXPECTED_ISOLATION = {
    'podSelector': {'matchLabels': {'app.kubernetes.io/name': 'ledger-service-dr-check'}},
    'policyTypes': ['Ingress', 'Egress'],
    'ingress': [],
    'egress': [
        {'to': [{'podSelector': {'matchLabels': {'cnpg.io/cluster': 'ledger-db-restored'}}}],
         'ports': [{'protocol': 'TCP', 'port': 5432}]},
        {'to': [{'namespaceSelector': {'matchLabels': {'kubernetes.io/metadata.name': 'kube-system'}},
                 'podSelector': {'matchLabels': {'k8s-app': 'kube-dns'}}}],
         'ports': [{'protocol': 'UDP', 'port': 53}, {'protocol': 'TCP', 'port': 53}]},
    ],
}
EXPECTED_CREDENTIAL = {
    'refreshPolicy': 'CreatedOnce',
    'target': {
        'name': 'ledger-dr-check-db',
        'creationPolicy': 'Owner',
        'template': {
            'type': 'kubernetes.io/basic-auth',
            'metadata': {'labels': {'cnpg.io/reload': 'true'}},
            'data': {'username': 'ledger_dr_check', 'password': '{{ .password }}'},
        },
    },
    'dataFrom': [{'sourceRef': {'generatorRef': {
        'apiVersion': 'generators.external-secrets.io/v1alpha1',
        'kind': 'ClusterGenerator',
        'name': 'ledger-dr-check-db-password'}}}],
}


def run_cli(kyverno, directory):
    args = ['apply', 'policy.yaml', '-r', 'resources.yaml']
    if kyverno:
        command, cwd = [str(Path(kyverno).resolve()), *args], directory
    else:
        command = ['docker', 'run', '--rm', '-v', f'{directory}:/w', '-w', '/w', CLI_IMAGE, *args]
        cwd = None
    result = subprocess.run(command, cwd=cwd, capture_output=True, text=True, timeout=300)
    assert result.returncode == 0, result.stdout + result.stderr
    return result.stdout


def parse(stdout):
    """Map (policy, namespace) -> generated object, from `kyverno apply` output."""
    generated = {}
    marks = list(re.finditer(r'^policy (\S+) applied to /Namespace/(\S+):$', stdout, re.M))
    for i, mark in enumerate(marks):
        end = marks[i + 1].start() if i + 1 < len(marks) else len(stdout)
        key = (mark.group(1), mark.group(2))
        assert key not in generated, f'{key} generated twice'
        generated[key] = yaml.safe_load(stdout[mark.end():end].split('\n---')[0])
    summary = re.search(r'pass: (\d+), fail: (\d+), warn: (\d+), error: (\d+), skip: (\d+)', stdout)
    assert summary, stdout
    return generated, tuple(int(n) for n in summary.groups())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kyverno', help='Kyverno CLI binary; default: docker image ' + CLI_IMAGE)
    args = parser.parse_args()

    text = POLICY.read_text()
    documents = list(yaml.safe_load_all(text))
    assert {d['metadata']['name'] for d in documents} == {
        'ledger-dr-check-network-isolation', 'ledger-dr-check-database-identity'}
    assert all(d['apiVersion'] == 'policies.kyverno.io/v1' and d['kind'] == 'GeneratingPolicy'
               for d in documents)
    assert 'sha256' not in text and '"Secret"' not in text, \
        'the reader password must come from the ESO generator, never be derived or written by policy'

    rules = list(yaml.safe_load_all(RBAC.read_text()))[0]['rules']
    assert not any('secrets' in r['resources'] for r in rules), 'the DR runner must not touch Secrets'
    grants = [r for r in rules if 'networkpolicies' in r['resources']]
    assert grants == [{'apiGroups': ['networking.k8s.io'], 'resources': ['networkpolicies'],
                       'resourceNames': ['ledger-dr-check-isolation'], 'verbs': ['get']}], grants

    want = {}
    for ns in MATCHING:
        want[('ledger-dr-check-network-isolation', ns)] = {
            'apiVersion': 'networking.k8s.io/v1', 'kind': 'NetworkPolicy',
            'metadata': {'name': 'ledger-dr-check-isolation', 'namespace': ns},
            'spec': EXPECTED_ISOLATION}
        want[('ledger-dr-check-database-identity', ns)] = {
            'apiVersion': 'external-secrets.io/v1', 'kind': 'ExternalSecret',
            'metadata': {'name': 'ledger-dr-check-db', 'namespace': ns},
            'spec': EXPECTED_CREDENTIAL}

    with tempfile.TemporaryDirectory(prefix='dr-generate-') as directory:
        root = Path(directory)
        root.chmod(0o755)
        (root / 'policy.yaml').write_text(text)
        (root / 'resources.yaml').write_text(yaml.safe_dump_all(
            [{'apiVersion': 'v1', 'kind': 'Namespace', 'metadata': {'name': ns}}
             for ns in MATCHING + NOT_MATCHING]))
        stdout = run_cli(args.kyverno, str(root))

    generated, (passed, failed, _warned, errored, _skipped) = parse(stdout)
    assert generated, 'no generated resources at all: the harness evaluated nothing\n' + stdout
    bad = 0
    for key in sorted(set(want) | set(generated)):
        same = want.get(key) == generated.get(key)
        bad += not same
        print(f"{'OK  ' if same else 'DIFF'} {key[0]:36} {key[1]}"
              + ('' if same else f"\n  want={want.get(key)}\n  got ={generated.get(key)}"))
    assert (failed, errored) == (0, 0), stdout
    assert passed == len(want), f'{passed} policy applications passed, expected {len(want)}'
    assert not bad, f'{bad} divergent'
    print(f'{len(want)} generated, {len(NOT_MATCHING)} namespaces correctly untouched; '
          'no cluster requests made')


if __name__ == '__main__':
    main()
