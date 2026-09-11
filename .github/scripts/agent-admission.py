#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Bound new autonomous work; a full queue is backpressure, unreadable data is failure."""
import json
import sys
from pathlib import Path

import yaml

MAX_AGENT_PRS = 3
RULES = Path("openbank-libs/governance/rules.yaml")


def load_prefixes(path=RULES):
    try:
        doc = yaml.safe_load(Path(path).read_text())
    except (OSError, yaml.YAMLError) as error:
        raise ValueError(f"could not read autonomous PR rules: {error}") from error
    prefixes = ((doc or {}).get("autonomous_agent_prs") or {}).get("agent_branch_prefixes")
    if not isinstance(prefixes, list) or not prefixes or any(not isinstance(p, str) or not p for p in prefixes):
        raise ValueError("autonomous_agent_prs.agent_branch_prefixes must be a non-empty string list")
    return tuple(prefixes)


def admit(pages, prefixes):
    if not isinstance(pages, list) or not pages or any(not isinstance(p, list) for p in pages):
        raise ValueError('expected paginated REST pull-request arrays')
    numbers = set()
    count = 0
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
            # Count drafts and blocked PRs too: waiting for review still consumes WIP.
            if head['ref'].startswith(prefixes):
                count += 1
    return count < MAX_AGENT_PRS, count


def main():
    try:
        proceed, count = admit(json.loads(Path(sys.argv[1]).read_text()), load_prefixes())
    except (ValueError, OSError, IndexError) as error:
        print(f'::error::Agent admission could not verify the queue: {error}', file=sys.stderr)
        return 1
    print(f'proceed={str(proceed).lower()}')
    print(f'Agent PRs: {count}/{MAX_AGENT_PRS}; ' +
          ('capacity available' if proceed else 'finish existing work before opening another PR'), file=sys.stderr)
    return 0


if __name__ == '__main__':
    sys.exit(main())
