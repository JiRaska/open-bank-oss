#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Conservative dispatch admission, not an invoice or a subscription quota meter."""
import json
import math
import os
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

MAX_WEEKLY_DISPATCHES = 2


def require(condition, message):
    if not condition:
        raise ValueError(message)


def admit(pages, current_id, now):
    require(isinstance(pages, list) and bool(pages), 'missing run history')
    runs = []
    total = None
    for page in pages:
        require(isinstance(page, dict), 'invalid history page')
        count = page.get('total_count')
        require(type(count) is int and 0 <= count < 1000, 'invalid or capped run history')
        require(total is None or count == total, 'history changed during pagination')
        total = count
        require(isinstance(page.get('workflow_runs'), list), 'missing runs')
        runs.extend(page['workflow_runs'])
    require(len(runs) == total, 'incomplete history')
    ids = set()
    cutoff = now - timedelta(days=7)
    for run in runs:
        require(isinstance(run, dict), 'invalid run')
        identity = run.get('id')
        require(type(identity) is int and identity > 0 and identity not in ids, 'invalid or duplicate run id')
        ids.add(identity)
        require(run.get('event') == 'workflow_dispatch', 'unexpected event')
        created = datetime.fromisoformat(run['created_at'].replace('Z', '+00:00'))
        require(created.tzinfo is not None and cutoff <= created <= now, 'invalid run window')
        if identity == current_id:
            require(run.get('run_attempt') == 1, 'reruns are forbidden')
        else:
            require(run.get('status') == 'completed' and run.get('conclusion') == 'success',
                    'circuit open: previous review failed, was cancelled, or is unresolved')
    require(current_id in ids, 'current dispatch is not visible; do not call the provider')
    # Every dispatch consumes a slot, even failure/cancellation before measured usage.
    require(len(ids) <= MAX_WEEKLY_DISPATCHES, 'rolling seven-day review dispatch allowance exhausted')


def usage(raw):
    results = []
    for line in raw.splitlines():
        try:
            event = json.loads(line)
        except ValueError:
            continue
        if isinstance(event, dict) and event.get('type') == 'result':
            results.append(event)
    result = results[0] if len(results) == 1 else {}
    counts = result.get('usage')
    counts = counts if isinstance(counts, dict) else {}
    tokens = {}
    for key in ('input_tokens', 'output_tokens', 'cache_read_input_tokens', 'cache_creation_input_tokens'):
        value = counts.get(key)
        tokens[key] = value if type(value) is int and value >= 0 else None
    cost = result.get('total_cost_usd')
    valid = type(cost) in (int, float) and math.isfinite(cost) and cost >= 0
    return dict(tokens=tokens, estimated_cost_usd=cost if valid else None,
                cost_basis='cli_estimate_not_invoice' if valid else 'unknown')


def gh(endpoint):
    return json.loads(subprocess.check_output(['gh', 'api', endpoint], text=True))


def main():
    if sys.argv[1:] == ['--usage']:
        raw = Path('/tmp/review.raw')
        evidence = usage(raw.read_text(errors='replace') if raw.exists() else '')
        evidence['invocation_started'] = Path('/tmp/review.started').exists()
        Path('/tmp/review-usage.json').write_text(json.dumps(evidence, indent=2) + '\n')
        if evidence['invocation_started']:
            require(evidence['estimated_cost_usd'] is not None
                    and all(v is not None for v in evidence['tokens'].values()),
                    'unknown provider usage; stop subsequent reviews')
        return
    require(os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch', 'manual dispatch required')
    require(os.environ.get('GITHUB_RUN_ATTEMPT') == '1', 'reruns are forbidden')
    repo = os.environ['GITHUB_REPOSITORY']
    default = gh(f'repos/{repo}')['default_branch']
    require(os.environ.get('GITHUB_REF') == f'refs/heads/{default}', 'default branch controller required')
    require(os.environ.get('GITHUB_SHA') == gh(f'repos/{repo}/commits/{default}')['sha'], 'controller is stale')
    now = datetime.now(timezone.utc).replace(microsecond=0)
    cutoff = (now - timedelta(days=7)).isoformat().replace('+00:00', 'Z')
    endpoint = f'repos/{repo}/actions/workflows/agent-review.yml/runs?event=workflow_dispatch&created=>={cutoff}&per_page=100'
    pages = json.loads(subprocess.check_output(['gh', 'api', '--paginate', '--slurp', endpoint], text=True))
    admit(pages, int(os.environ['GITHUB_RUN_ID']), now)
    print('Admitted: manual, first attempt, dedicated API only, rolling-window allowance available')


if __name__ == '__main__':
    main()
