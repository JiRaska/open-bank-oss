#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Account-service metadata pilot; uncertain models retain the existing full gate."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time

from metadata_scope import archive_source, plan, source_identity, tracked_projects

# This locally verified input graph is the pilot's boundary, not a fleet-wide allow-list.
PILOT_BUILD_BASE = '1c5b52181fd65d82b059db077e2b0043ee385234'
PILOT_PROJECTS = ['openbank-account-service', 'openbank-libs', 'openbank-libs-domain',
                  'openbank-libs-runtime', 'openbank-libs-testing', 'openbank-libs-detekt-rules']


def build_profile_matches(root):
    paths = ['settings.gradle.kts', 'build.gradle.kts', 'gradle.properties',
             'build-logic', 'gradle', 'config', 'openbank-libs/gradle',
             ':(glob)openbank-*/build.gradle.kts']
    paths += [p + '/build.gradle.kts' for p in PILOT_PROJECTS]
    return subprocess.run(['git', 'diff', '--quiet', PILOT_BUILD_BASE, 'HEAD', '--', *paths], cwd=root).returncode == 0


def collect(root, selected, known, output):
    receipts = {}
    # Seed the previously proven closure in one owning-task batch. Still expand
    # every newly resolved edge; these seeds never replace model validation.
    pending = set(selected) | {':' + p for p in PILOT_PROJECTS if ':' + p in known}
    while pending:
        if not pending <= known:
            raise ValueError('unknown project dependency')
        env = dict(os.environ, OB_METADATA_PROJECTS=','.join(sorted(pending)),
                   OB_METADATA_MODEL_DIR=str(output))
        subprocess.run(['./gradlew', '-I', '.github/scripts/metadata-scope-model.init.gradle',
                        'exportMetadataScopeModel', '--dependency-verification', 'strict',
                        '--no-configuration-cache', '--console=plain'], cwd=root, env=env, check=True)
        for project in pending:
            receipts[project] = json.loads((output / (project[1:] + '.json')).read_text())
        edges = set()
        for receipt in receipts.values():
            if not isinstance(receipt, dict):
                raise ValueError('malformed project model')
            closure = receipt.get('closure')
            if not isinstance(closure, list) or any(not isinstance(p, str) for p in closure):
                raise ValueError('malformed project model')
            edges.update(closure)
        pending = edges - receipts.keys()
    return receipts


def declared_inputs_fit(root, projects, known, output):
    env = dict(os.environ, OB_METADATA_PROJECTS=':openbank-account-service',
               OB_METADATA_TASK_INPUTS_DIR=str(output))
    subprocess.run(['./gradlew', '-I', '.github/scripts/metadata-scope-model.init.gradle',
                    ':openbank-account-service:testClasses',
                    ':openbank-account-service:quarkusDependenciesBuild', '--dry-run',
                    '--dependency-verification', 'strict', '--no-configuration-cache',
                    '--console=plain'], cwd=root, env=env, check=True)
    for name in ['openbank.json', 'build-logic.json']:
        rows = json.loads((output / name).read_text())
        if not isinstance(rows, list) or not rows:
            return False
        for row in rows:
            if not isinstance(row, dict) or not isinstance(row.get('localInputs'), list):
                return False
            for path in row['localInputs']:
                if not isinstance(path, str) or not path or Path(path).is_absolute() or '..' in Path(path).parts:
                    return False
                project = ':' + path.split('/')[0]
                if project in known and project not in projects:
                    return False
    return True


def run_gate(root, modules, enforce, commit=None):
    command = [sys.executable, '.github/scripts/check-verification-metadata-complete.py', '--modules', modules]
    if enforce:
        command.append('--enforce')
    env = dict(os.environ)
    if commit:
        env['SOURCE_COMMIT'] = commit
    return subprocess.run(command, cwd=root, env=env).returncode


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--modules', required=True)
    parser.add_argument('--enforce', action='store_true')
    args = parser.parse_args()
    root = Path.cwd()
    if args.modules.strip() != 'openbank-account-service':
        print('metadata scope: unsupported pilot selection; using full gate', flush=True)
        return run_gate(root, args.modules, args.enforce)
    with tempfile.TemporaryDirectory(prefix='metadata-scope-') as tmp:
        workspace = Path(tmp)
        try:
            commit = source_identity(root)
            if not commit or not build_profile_matches(root):
                raise ValueError('dirty source or unproven build-input profile')
            known = tracked_projects(root, commit)
            output = workspace / 'model'
            output.mkdir()
            selected = [':openbank-account-service']
            started = time.monotonic()
            receipts = collect(root, selected, known, output)
            print(f'metadata scope: model collection {time.monotonic() - started:.2f}s', flush=True)
            result = plan(receipts, selected, known, [':openbank-libs'], commit,
                          source_identity(root), source_identity(root) == commit)
            if result['mode'] != 'scoped':
                raise ValueError(result['reason'])
            inputs = workspace / 'inputs'
            inputs.mkdir()
            started = time.monotonic()
            if not declared_inputs_fit(root, result['projects'], known, inputs):
                raise ValueError('gate task inputs reach an omitted project')
            print(f'metadata scope: task input verification {time.monotonic() - started:.2f}s', flush=True)
            started = time.monotonic()
            checkout = workspace / 'checkout'
            checkout.mkdir()
            archive_source(root, commit, result['projects'], checkout)
            print(f'metadata scope: source archive {time.monotonic() - started:.2f}s', flush=True)
        except (OSError, ValueError, subprocess.SubprocessError) as error:
            print(f'metadata scope: {error}; using full gate', flush=True)
            return run_gate(root, args.modules, args.enforce)
        print('metadata scope: isolated projects ' + ','.join(result['projects']), flush=True)
        # Preserve the real gate's gap (1) and undetermined (2) outcomes verbatim.
        started = time.monotonic()
        verdict = run_gate(checkout, args.modules, args.enforce, commit)
        print(f'metadata scope: unchanged gate {time.monotonic() - started:.2f}s', flush=True)
        return verdict


if __name__ == '__main__':
    sys.exit(main())
