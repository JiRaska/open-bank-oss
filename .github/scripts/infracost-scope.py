# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Run Infracost only when a PR changes the environment being priced."""
import json
import os
import subprocess
import sys

PREFIXES = {
    'platform': ('openbank-infra/aws/envs/sandbox-platform/',),
    'substrate': ('openbank-infra/aws/envs/sandbox-substrate/', 'openbank-infra/aws/modules/'),
}

# This recipe builds a runner image; Terraform consumes a separately pinned
# local.runner_image, not the Dockerfile. Keep all other environment inputs in
# scope, including unknown file types and both sides of renames.
BUILD_ONLY_PATHS = {
    'openbank-infra/aws/envs/sandbox-platform/runner-image/Dockerfile',
}

# GitHub REST diff-entry.status is a required string from this closed enum.
# Unknown status cannot establish that a missing rename origin is harmless.
FILE_STATUSES = {'added', 'removed', 'modified', 'renamed', 'copied', 'changed', 'unchanged'}


def valid_path(name):
    return (isinstance(name, str) and bool(name) and '\0' not in name
            and all(part not in ('', '.', '..') for part in name.split('/')))


def cost_scope(files, expected_count, target):
    if target not in PREFIXES:
        raise ValueError(f'unknown cost target: {target}')
    if (not isinstance(files, list) or type(expected_count) is not int
            or expected_count < 0):
        raise ValueError('invalid PR file inventory or count')
    for item in files:
        if not isinstance(item, dict) or not valid_path(item.get('filename')):
            raise ValueError('invalid PR filename')
        status = item.get('status')
        if not isinstance(status, str) or status not in FILE_STATUSES:
            raise ValueError('invalid PR file status')
        previous = item.get('previous_filename')
        if status == 'renamed' and not valid_path(previous):
            raise ValueError('renamed PR file has no valid previous filename')
        if previous is not None and not valid_path(previous):
            raise ValueError('invalid previous PR filename')
    if len(files) != expected_count or len({item['filename'] for item in files}) != len(files):
        raise ValueError('PR file inventory is incomplete or duplicated')
    prefixes = PREFIXES[target]
    return any(
        name.startswith(prefixes) and name not in BUILD_ONLY_PATHS
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
        '--jq', '.[] | {filename, previous_filename, status}',
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
