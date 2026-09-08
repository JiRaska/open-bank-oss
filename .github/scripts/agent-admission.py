#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Bound new autonomous work; a full queue is backpressure, unreadable data is failure."""
import json
import sys
from pathlib import Path

MAX_AGENT_PRS = 3


def admit(pages):
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
            if head['ref'].startswith('agent/'):
                count += 1
    return count < MAX_AGENT_PRS, count


def main():
    try:
        proceed, count = admit(json.loads(Path(sys.argv[1]).read_text()))
    except (ValueError, OSError, IndexError) as error:
        print(f'::error::Agent admission could not verify the queue: {error}', file=sys.stderr)
        return 1
    print(f'proceed={str(proceed).lower()}')
    print(f'Agent PRs: {count}/{MAX_AGENT_PRS}; ' +
          ('capacity available' if proceed else 'finish existing work before opening another PR'), file=sys.stderr)
    return 0


if __name__ == '__main__':
    sys.exit(main())
