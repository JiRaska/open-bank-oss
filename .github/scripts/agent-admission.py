#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Bound new autonomous work; a full queue is backpressure, unreadable data is failure."""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import yaml

RULES = Path("openbank-libs/governance/rules.yaml")


def load_policy(path=RULES):
    try:
        doc = yaml.safe_load(Path(path).read_text())
    except (OSError, yaml.YAMLError) as error:
        raise ValueError(f"could not read autonomous PR rules: {error}") from error
    config = doc.get("autonomous_agent_prs") if isinstance(doc, dict) else None
    if not isinstance(config, dict):
        raise ValueError("autonomous_agent_prs must be a mapping")
    prefixes = config.get("agent_branch_prefixes")
    if not isinstance(prefixes, list) or not prefixes or any(not isinstance(p, str) or not p for p in prefixes):
        raise ValueError("autonomous_agent_prs.agent_branch_prefixes must be a non-empty string list")
    limit = config.get("max_open_prs")
    if type(limit) is not int or limit < 1:
        raise ValueError("autonomous_agent_prs.max_open_prs must be a positive integer")
    return tuple(prefixes), limit


def validate_snapshot(pages):
    if not isinstance(pages, list) or not pages or any(not isinstance(p, list) for p in pages):
        raise ValueError('expected paginated REST pull-request arrays')
    numbers = set()
    for page in pages:
        for pr in page:
            if not isinstance(pr, dict) or not isinstance(pr.get('number'), int):
                raise ValueError('invalid PR identity')
            if pr['number'] in numbers:
                raise ValueError('duplicate PR in pagination; retry the snapshot')
            numbers.add(pr['number'])
            head = pr.get('head')
            if not isinstance(head, dict) or not isinstance(head.get('ref'), str):
                raise ValueError('missing PR head ref')
            if pr.get('state') != 'open':
                raise ValueError('snapshot must contain only open PRs')
    return [pr for page in pages for pr in page]


def admit(pages, prefixes, limit):
    # Count drafts and blocked PRs too: waiting for review still consumes WIP.
    count = sum(pr['head']['ref'].startswith(prefixes) for pr in validate_snapshot(pages))
    return count < limit, count


def admit_current_pr(pages, prefixes, limit, number, grandfather_before):
    """Order concurrent openings by immutable PR number; existing work may drain."""
    prs = validate_snapshot(pages)
    current = next((pr for pr in prs if pr['number'] == number), None)
    if current is None:
        raise ValueError(f"current PR #{number} missing from snapshot")
    if not current['head']['ref'].startswith(prefixes):
        return True, 0
    try:
        created = datetime.fromisoformat(current['created_at'].replace('Z', '+00:00'))
        cutoff = datetime.fromisoformat(grandfather_before.replace('Z', '+00:00'))
        if created.tzinfo is None or cutoff.tzinfo is None:
            raise ValueError("timestamps must include a timezone")
    except (AttributeError, KeyError, TypeError, ValueError) as error:
        raise ValueError(f"invalid PR creation or grandfather timestamp: {error}") from error
    rank = sum(
        pr['number'] <= number and pr['head']['ref'].startswith(prefixes)
        for pr in prs
    )
    return created.astimezone(timezone.utc) < cutoff.astimezone(timezone.utc) or rank <= limit, rank


def main():
    args = sys.argv[1:]
    try:
        if not args:
            raise ValueError("snapshot path is required")
        prefixes, limit = load_policy()
        pages = json.loads(Path(args[0]).read_text())
        if len(args) == 1:
            proceed, count = admit(pages, prefixes, limit)
            current_mode = False
        elif len(args) == 4 and args[1] == '--current-pr':
            number = int(args[2])
            if number < 1:
                raise ValueError("current PR number must be positive")
            proceed, count = admit_current_pr(pages, prefixes, limit, number, args[3])
            current_mode = True
        else:
            raise ValueError("usage: agent-admission.py SNAPSHOT [--current-pr NUMBER GRANDFATHER_BEFORE]")
    except (ValueError, OSError, IndexError) as error:
        print(f'::error::Agent admission could not verify the queue: {error}', file=sys.stderr)
        return 1
    print(f'proceed={str(proceed).lower()}')
    if current_mode and proceed and count > limit:
        reason = 'grandfathered existing PR may finish'
    else:
        reason = 'capacity available' if proceed else 'finish existing work before opening another PR'
    print(f'Agent PRs: {count}/{limit}; ' +
          reason, file=sys.stderr)
    return 0


if __name__ == '__main__':
    sys.exit(main())
