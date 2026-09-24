# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Run Infracost only when a PR changes the environment being priced."""
import json
import os
import subprocess
import sys

PREFIXES = {
    'platform': ('openbank-infra/aws/envs/',),
    'substrate': ('openbank-infra/aws/envs/sandbox-substrate/', 'openbank-infra/aws/modules/'),
}


def cost_scope(files, expected_count, target):
    if target not in PREFIXES:
        raise ValueError(f'unknown cost target: {target}')
    if len(files) != expected_count or len({item['filename'] for item in files}) != len(files):
        raise ValueError('PR file inventory is incomplete or duplicated')
    prefixes = PREFIXES[target]
    return any(
        name.startswith(prefixes)
        for item in files
        for name in (item['filename'], item.get('previous_filename'))
        if name
    )


def main(target):
    repo = os.environ['GITHUB_REPOSITORY']
    number = int(os.environ['PR_NUMBER'])
    if number < 1:
        raise ValueError('invalid PR metadata')
    expected = int(subprocess.check_output([
        'gh', 'api', f'repos/{repo}/pulls/{number}', '--jq', '.changed_files',
    ], text=True).strip())
    if expected < 0:
        raise ValueError('invalid PR file count')
    result = subprocess.check_output([
        'gh', 'api', '--paginate', f'repos/{repo}/pulls/{number}/files?per_page=100',
        '--jq', '.[] | {filename, previous_filename}',
    ], text=True)
    files = [json.loads(line) for line in result.splitlines()]
    run = cost_scope(files, expected, target)
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
        print(f'run={str(run).lower()}', file=output)
    print(f'Infracost scope: {target}, changed files: {len(files)}, run: {run}')


if __name__ == '__main__':
    try:
        main(sys.argv[1])
    except (IndexError, KeyError, ValueError, subprocess.CalledProcessError) as exc:
        print(f'::error::Infracost scope unknown: {exc}', file=sys.stderr)
        sys.exit(1)
