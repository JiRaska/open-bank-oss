#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Negative qualification checks for the artifact-only communication producer."""
import os
import subprocess
from pathlib import Path

import yaml

workflow = yaml.safe_load(Path('.github/workflows/communication-image-only.yml').read_text())
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
