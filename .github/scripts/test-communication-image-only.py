#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Negative qualification checks for the artifact-only communication producer."""
import os
import subprocess
from pathlib import Path

import yaml

workflow = yaml.safe_load(Path('.github/workflows/communication-image-only.yml').read_text())
assert set(workflow.get('on', workflow.get(True))) == {'workflow_dispatch'}
assert set(workflow['jobs']) == {'changes', 'build-push'}
assert workflow['name'] not in {'Auto deploy', 'Admin-UI deploy', 'Services CI'}
step = next(s for s in workflow['jobs']['build-push']['steps'] if s.get('name') == 'Require complete target evidence')
script = step['run'].split('mkdir -p image-evidence')[0]
target = '["openbank-communication-service"]'
base = dict(PUSHED=target, QUALIFIED=target, FAILED='[]', BLOCKED='[]', DIGESTS='{"openbank-communication-service":"sha256:' + 'a' * 64 + '"}')
cases = [
    ('complete', {}, True),
    ('no push', {'PUSHED': '[]'}, False),
    ('failed push', {'FAILED': target}, False),
    ('blocked image', {'BLOCKED': target}, False),
    ('no scan survivor', {'QUALIFIED': '[]'}, False),
    ('missing digest', {'DIGESTS': '{}'}, False),
    ('invalid digest', {'DIGESTS': '{"openbank-communication-service":"tag"}'}, False),
]
for name, patch, expected in cases:
    result = subprocess.run(['bash', '-c', script], env={**os.environ, **base, **patch}, capture_output=True)
    assert (result.returncode == 0) == expected, name
    print('PASS', name)
print('PASS no deployment jobs/triggers')

# Exercise the exact registry-proof jq expressions, including cosign v2's ID spelling.
import base64
import copy
import json
import re

proof = step['run']
expressions = re.findall(r"'\n(.*?)\n  ' image-evidence/(provenance|sbom)-envelopes.json", proof, re.S)
assert len(expressions) == 2
sha = 'a' * 64
source = 'b' * 40
workflow_sha = 'c' * 40
invocation = 'https://github.com/example/repo/actions/runs/1/attempts/1'
statement = dict(subject=[dict(digest=dict(sha256=sha))], predicateType='https://slsa.dev/provenance/v0.2', predicate=dict(metadata=dict(buildInvocationId=invocation), invocation=dict(configSource=dict(digest=dict(sha1=workflow_sha), entryPoint='.github/workflows/communication-image-only.yml')), materials=[dict(digest=dict(sha1=source))]))

def verify(expression, value, expected, name):
    envelope = dict(payload=base64.b64encode(json.dumps(value).encode()).decode())
    result = subprocess.run(['jq', '-e', '-s', '--arg', 'digest', sha, '--arg', 'source', source, '--arg', 'workflow', workflow_sha, '--arg', 'invocation', invocation, expression], input=json.dumps(envelope), text=True, capture_output=True)
    assert (result.returncode == 0) == expected, (name, result.stderr)
    print('PASS', name)

for expression, kind in expressions:
    if kind == 'provenance':
        verify(expression, statement, True, 'provenance exact identity')
        value = copy.deepcopy(statement)
        value['predicate']['metadata'] = dict(buildInvocationID=invocation)
        verify(expression, value, True, 'cosign v2 invocation ID')
        for name, path in [
            ('digest', ['subject', 0, 'digest', 'sha256']),
            ('source', ['predicate', 'materials', 0, 'digest', 'sha1']),
            ('workflow', ['predicate', 'invocation', 'configSource', 'digest', 'sha1']),
            ('entrypoint', ['predicate', 'invocation', 'configSource', 'entryPoint']),
            ('invocation', ['predicate', 'metadata', 'buildInvocationId']),
        ]:
            value = copy.deepcopy(statement)
            target = value
            for key in path[:-1]:
                target = target[key]
            target[path[-1]] = 'wrong'
            verify(expression, value, False, 'reject wrong ' + name)
        result = subprocess.run(['jq', '-e', '-s', expression], input='', text=True, capture_output=True)
        assert result.returncode != 0
        print('PASS reject absent provenance')
    else:
        value = dict(subject=[dict(digest=dict(sha256=sha))], predicate=dict(bomFormat='CycloneDX', components=[dict(name='library')]))
        verify(expression, value, True, 'substantive SBOM')
        value['predicate']['components'] = []
        verify(expression, value, False, 'reject empty SBOM')
        value['predicate']['components'] = [dict(name='library')]
        value['subject'][0]['digest']['sha256'] = 'wrong'
        verify(expression, value, False, 'reject wrong SBOM digest')
