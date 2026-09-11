#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Prevent action tag drift and preserve bounded, read-only agent PR validation."""
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
# The SLSA builder verifies its identity using a version tag (see release-please.yml).
SLSA = 'slsa-framework/slsa-github-generator/.github/workflows/generator_generic_slsa3.yml@v2.1.0'


def findings(name, doc):
    errors = []
    jobs = doc.get('jobs', {})
    events = doc.get('on', doc.get(True, {}))
    if isinstance(events, dict) and 'pull_request' in events and not doc.get('concurrency'):
        errors.append('pull_request workflow must bound superseded runs with concurrency')
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
        concurrency = doc.get('concurrency', {})
        if ('github.event.pull_request.number' not in str(concurrency.get('group', '')) or
                'pull_request' not in str(concurrency.get('cancel-in-progress', ''))):
            errors.append('agent PR validation must use a superseding per-PR concurrency lane')
        if name == 'agent-issue-worker.yml':
            if worker.get('needs') != 'admission' or "needs.admission.outputs.proceed == 'true'" not in worker.get('if', ''):
                errors.append('issue worker must depend on successful queue admission')
    return errors


def steward_scope_findings(prompt, rules, workflow):
    errors = []
    prefixes = ((rules or {}).get('autonomous_agent_prs') or {}).get('agent_branch_prefixes')
    if not isinstance(prefixes, list) or not prefixes:
        errors.append('authoritative autonomous branch prefixes must be readable and non-empty')
    required = ('openbank-libs/governance/rules.yaml', 'agent_branch_prefixes',
                'never fall back to a hard-coded branch prefix')
    if any(text not in prompt for text in required):
        errors.append('steward prompt must fail closed on the authoritative branch-prefix list')
    events = workflow.get('on', workflow.get(True, {}))
    paths = (events.get('pull_request') or {}).get('paths', [])
    if '.github/agent-prompts/pr-steward.md' not in paths:
        errors.append('steward prompt changes must trigger workflow validation')
    return errors


def main():
    paths = sorted((ROOT / '.github/workflows').glob('*.yml')) + sorted((ROOT / '.github/workflows').glob('*.yaml'))
    print(f'SUBJECTS={len(paths)}')
    errors = []
    for path in paths:
        doc = yaml.safe_load(path.read_text())
        errors.extend(f'{path.name}: {error}' for error in findings(path.name, doc))
    steward_path = ROOT / '.github/workflows/agent-pr-steward.yml'
    steward = yaml.safe_load(steward_path.read_text())
    rules = yaml.safe_load((ROOT / 'openbank-libs/governance/rules.yaml').read_text())
    prompt = (ROOT / '.github/agent-prompts/pr-steward.md').read_text()
    errors.extend(f'agent-pr-steward.yml: {error}'
                  for error in steward_scope_findings(prompt, rules, steward))
    for error in errors:
        print(f'::error::{error}')
    return bool(errors)


if __name__ == '__main__':
    sys.exit(main())
