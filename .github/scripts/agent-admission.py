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


def load_exceptions(path=RULES):
    """Only reviewed trusted-base policy can grant an expiring, PR-bound exemption."""
    doc = yaml.safe_load(Path(path).read_text())
    entries = doc['autonomous_agent_prs'].get('one_time_wip_exceptions', [])
    if not isinstance(entries, list):
        raise ValueError('one_time_wip_exceptions must be a list')
    seen = set()
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != {'pr', 'repository', 'branch', 'expires_at', 'reason'}:
            raise ValueError('invalid one-time WIP exception fields')
        if type(entry['pr']) is not int or entry['pr'] < 1 or entry['pr'] in seen:
            raise ValueError('invalid or duplicate exception PR')
        seen.add(entry['pr'])
        for key in ('repository', 'branch', 'reason'):
            if not isinstance(entry[key], str) or not entry[key].strip():
                raise ValueError(f'exception {key} must be non-empty')
        parse_utc(entry['expires_at'], 'exception expires_at')
    return entries


def matching_exception(current, exceptions, now):
    for entry in exceptions:
        if (entry['pr'] == current['number']
                and entry['branch'] == current['head']['ref']
                and entry['repository'] == (current['head'].get('repo') or {}).get('full_name')
                and now < parse_utc(entry['expires_at'], 'exception expires_at')):
            return entry
    return None


def parse_utc(value, name):
    try:
        parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
        if parsed.tzinfo is None:
            raise ValueError("timezone missing")
        return parsed.astimezone(timezone.utc)
    except (AttributeError, TypeError, ValueError) as error:
        raise ValueError(f"{name} must be an ISO-8601 timestamp with timezone: {error}") from error


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
    cutoff = config.get("grandfather_created_before")
    parse_utc(cutoff, "autonomous_agent_prs.grandfather_created_before")
    return tuple(prefixes), limit, cutoff


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


def admit_current_pr(pages, prefixes, limit, number, grandfather_before, exceptions=(), now=None):
    """Order concurrent openings by immutable PR number; existing work may drain."""
    prs = validate_snapshot(pages)
    current = next((pr for pr in prs if pr['number'] == number), None)
    if current is None:
        raise ValueError(f"current PR #{number} missing from snapshot")
    if not current['head']['ref'].startswith(prefixes):
        return True, 0
    created = parse_utc(current.get('created_at'), "current PR created_at")
    cutoff = parse_utc(grandfather_before, "grandfather cutoff")
    rank = sum(
        pr['number'] <= number and pr['head']['ref'].startswith(prefixes)
        for pr in prs
    )
    exception = matching_exception(current, exceptions, now or datetime.now(timezone.utc))
    return created < cutoff or rank <= limit or exception is not None, rank


def main():
    args = sys.argv[1:]
    try:
        if not args:
            raise ValueError("snapshot path is required")
        prefixes, limit, cutoff = load_policy()
        exceptions = load_exceptions()
        pages = json.loads(Path(args[0]).read_text())
        if len(args) == 1:
            proceed, count = admit(pages, prefixes, limit)
            current_mode = False
        elif len(args) == 3 and args[1] == '--current-pr':
            number = int(args[2])
            if number < 1:
                raise ValueError("current PR number must be positive")
            proceed, count = admit_current_pr(pages, prefixes, limit, number, cutoff, exceptions)
            current_mode = True
        else:
            raise ValueError("usage: agent-admission.py SNAPSHOT [--current-pr NUMBER]")
    except (ValueError, OSError, IndexError) as error:
        print(f'::error::Agent admission could not verify the queue: {error}', file=sys.stderr)
        return 1
    print(f'proceed={str(proceed).lower()}')
    exception = None
    if current_mode:
        current = next(pr for pr in validate_snapshot(pages) if pr['number'] == number)
        exception = matching_exception(current, exceptions, datetime.now(timezone.utc))
        if exception:
            print(f"WIP exception PR #{number}: {exception['reason']}; expires {exception['expires_at']}", file=sys.stderr)
    if exception:
        reason = f'approved one-time WIP exception for PR #{number}'
    elif current_mode and proceed and count > limit:
        reason = 'grandfathered existing PR may finish'
    else:
        reason = 'capacity available' if proceed else 'finish existing work before opening another PR'
    print(f'Agent PRs: {count}/{limit}; ' +
          reason, file=sys.stderr)
    return 0


if __name__ == '__main__':
    sys.exit(main())
