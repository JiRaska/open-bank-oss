#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Prevent action tag drift and ratchet GitHub workflow write capabilities."""
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
WRITE_BASELINE = ROOT / '.github/gates/workflow-write-permissions-baseline.txt'
# The SLSA builder verifies its identity using a version tag (see release-please.yml).
SLSA = 'slsa-framework/slsa-github-generator/.github/workflows/generator_generic_slsa3.yml@v2.1.0'


def write_grants(name, doc):
    """Return stable workflow|owner|permission coordinates for every write grant."""
    grants = set()
    owners = [('top', doc), *[(f'job:{key}', job) for key, job in doc.get('jobs', {}).items()]]
    for owner, value in owners:
        permissions = value.get('permissions')
        if permissions == 'write-all':
            grants.add(f'{name}|{owner}|write-all')
        elif isinstance(permissions, dict):
            grants.update(
                f'{name}|{owner}|{permission}'
                for permission, access in permissions.items()
                if access == 'write'
            )
    return grants


def grant_drift(actual, expected):
    errors = []
    for grant in sorted(actual - expected):
        errors.append(f'unexpected workflow write grant: {grant}')
    for grant in sorted(expected - actual):
        errors.append(f'stale workflow write baseline entry: {grant}')
    return errors


def findings(name, doc):
    errors = []
    jobs = doc.get('jobs', {})
    for key, job in jobs.items():
        for item in [job, *job.get('steps', [])]:
            uses = item.get('uses', '')
            if uses and not (uses.startswith('./') or re.fullmatch(r'.+@[0-9a-f]{40}', uses)
                             or (name == 'release-please.yml' and item is job and uses == SLSA)):
                errors.append(f'{key}: action must use a full commit SHA: {uses}')
    if name in ('main-red-watch.yml', 'admin-ui-deploy.yml'):
        events = doc.get('on', doc.get(True, {}))
        if events.get('workflow_run', {}).get('branches') != ['main']:
            errors.append('workflow_run must reject non-main branches at the trigger')
    if name in ('agent-issue-worker.yml', 'agent-pr-steward.yml'):
        worker = jobs.get('worker', jobs.get('steward', {}))
        if "github.event_name != 'pull_request'" not in worker.get('if', ''):
            errors.append('credential-bearing agent job must exclude pull_request')
        for owner in [doc, *jobs.values()]:
            permissions = owner.get('permissions', {})
            if permissions == 'write-all' or (isinstance(permissions, dict) and 'write' in permissions.values()):
                errors.append('agent workflows must use read-only GITHUB_TOKEN permissions')
        scripts = '\n'.join(step.get('run', '') for step in worker.get('steps', []))
        if 'npm install' in scripts or 'npm ci --prefix .github/scripts/agent-review-claude-cli' not in scripts:
            errors.append('agent CLI must use the shared integrity-locked npm ci installation')
        if name == 'agent-issue-worker.yml':
            if worker.get('needs') != 'admission' or "needs.admission.outputs.proceed == 'true'" not in worker.get('if', ''):
                errors.append('issue worker must depend on successful queue admission')
    return errors


def main():
    if '--self-test' in sys.argv:
        fixture = {
            'permissions': {'contents': 'read', 'issues': 'write'},
            'jobs': {
                'safe': {'permissions': {'contents': 'read'}},
                'oidc': {'permissions': {'id-token': 'write'}},
                'all': {'permissions': 'write-all'},
            },
        }
        expected = {
            'fixture.yml|top|issues',
            'fixture.yml|job:oidc|id-token',
            'fixture.yml|job:all|write-all',
        }
        actual = write_grants('fixture.yml', fixture)
        if actual != expected:
            print(f'::error::write-permission self-test failed: {sorted(actual)}')
            return 1
        if grant_drift(actual | {'new.yml|top|contents'}, expected) != [
            'unexpected workflow write grant: new.yml|top|contents'
        ]:
            print('::error::write-permission self-test did not reject a new grant')
            return 1
        if grant_drift(actual - {'fixture.yml|top|issues'}, expected) != [
            'stale workflow write baseline entry: fixture.yml|top|issues'
        ]:
            print('::error::write-permission self-test did not reject stale debt')
            return 1
        print('self-test ok: write grants are owner-scoped; new and stale grants both fail')
        return 0

    paths = sorted((ROOT / '.github/workflows').glob('*.yml')) + sorted((ROOT / '.github/workflows').glob('*.yaml'))
    print(f'SUBJECTS={len(paths)}')
    errors = []
    for path in paths:
        doc = yaml.safe_load(path.read_text())
        errors.extend(f'{path.name}: {error}' for error in findings(path.name, doc))
    actual_grants = set().union(*(write_grants(path.name, yaml.safe_load(path.read_text())) for path in paths))
    if not WRITE_BASELINE.is_file():
        errors.append(f'{WRITE_BASELINE.relative_to(ROOT)}: write-permission baseline is missing')
    else:
        expected_grants = {
            line.strip() for line in WRITE_BASELINE.read_text().splitlines()
            if line.strip() and not line.lstrip().startswith('#')
        }
        errors.extend(grant_drift(actual_grants, expected_grants))
    for error in errors:
        print(f'::error::{error}')
    return bool(errors)


if __name__ == '__main__':
    sys.exit(main())
