#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Resolve bounded Gradle batches; publish one complete snapshot only after all succeed."""

import argparse
import copy
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


def require(condition, message):
    if not condition:
        raise ValueError(message)


def project_batches(plans, repository, size=18):
    roots = {str(Path(plan['directory']).resolve()): plan for plan in plans}
    require(len(roots) == len(plans), 'duplicate Gradle build plan')
    main = roots[str(repository.resolve())]
    # Included builds have their own path namespace. Refuse an unfamiliar layout
    # rather than quietly dropping its projects from a main-build regex.
    require(main['included'] == [{'name': 'build-logic',
                                'directory': str(repository.resolve() / 'build-logic')}],
            'unsupported included-build layout; extend dependency inventory coverage')
    logic = roots[str(repository.resolve() / 'build-logic')]
    require(len(roots) == 2 and logic['projects'] == [':'] and not logic['included'],
            'unsupported included-build projects')
    projects = main['projects']
    require(isinstance(projects, list) and ':' in projects and
            len(set(projects)) == len(projects), 'invalid project inventory')
    require(all(isinstance(p, str) and p.startswith(':') for p in projects),
            'invalid Gradle project path')
    modules = sorted(p for p in projects if p != ':')
    require(modules, 'empty fleet inventory')
    return [[':', ':build-logic', *modules[i:i + size]]
            for i in range(0, len(modules), size)]


def merge_snapshots(snapshots):
    """Match the plugin's union: direct wins; retain every dependency edge."""
    require(bool(snapshots), 'no dependency snapshots')
    result = copy.deepcopy(snapshots[0])
    require(len(result['manifests']) == 1, 'unexpected manifest layout')
    name = next(iter(result['manifests']))
    target = result['manifests'][name]
    for snapshot in snapshots[1:]:
        require({k: v for k, v in snapshot.items() if k not in ('manifests', 'scanned')} ==
                {k: v for k, v in result.items() if k not in ('manifests', 'scanned')},
                'dependency snapshot provenance mismatch')
        require(set(snapshot['manifests']) == {name}, 'manifest identity mismatch')
        incoming = snapshot['manifests'][name]
        require({k: v for k, v in incoming.items() if k != 'resolved'} ==
                {k: v for k, v in target.items() if k != 'resolved'},
                'manifest source mismatch')
        for coordinate, node in incoming['resolved'].items():
            if coordinate not in target['resolved']:
                target['resolved'][coordinate] = copy.deepcopy(node)
                continue
            previous = target['resolved'][coordinate]
            require({k: v for k, v in node.items() if k not in ('relationship', 'dependencies')} ==
                    {k: v for k, v in previous.items() if k not in ('relationship', 'dependencies')},
                    f'inconsistent dependency identity or scope: {coordinate}')
            relationships = {node['relationship'], previous['relationship']}
            require(relationships <= {'direct', 'indirect'}, 'unknown dependency relationship')
            previous['relationship'] = 'direct' if 'direct' in relationships else 'indirect'
            previous['dependencies'] = sorted(set(previous['dependencies']) | set(node['dependencies']))
        result['scanned'] = max(result['scanned'], snapshot['scanned'])
    require(bool(target['resolved']), 'empty dependency inventory')
    require(all(edge in target['resolved'] for node in target['resolved'].values()
                for edge in node['dependencies']), 'dangling dependency edge')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gradle-init', type=Path, help='Local-only plugin bootstrap; CI uses setup-gradle')
    args = parser.parse_args()
    repository = Path(__file__).resolve().parents[2]
    scripts = repository / '.github/scripts'
    destination = Path(os.environ['DEPENDENCY_GRAPH_REPORT_DIR']).resolve()
    require(not destination.exists() or not list(destination.rglob('*.json')),
            'snapshot destination must not contain stale reports')
    expected_sha = os.environ['GITHUB_DEPENDENCY_GRAPH_SHA']
    require(re.fullmatch(r'[0-9a-f]{40}', expected_sha), 'invalid snapshot SHA')
    require(not os.environ.get('DEPENDENCY_GRAPH_EXCLUDE_PROJECTS') and
            not os.environ.get('DEPENDENCY_GRAPH_INCLUDE_CONFIGURATIONS'),
            'unexpected dependency scope restriction')
    command = [str(repository / 'gradlew'), '--no-daemon', '--no-parallel', '--max-workers=1',
               '--no-configuration-cache', '--dependency-verification', 'strict']
    with tempfile.TemporaryDirectory(prefix='openbank-dependency-resolution-') as temporary:
        work = Path(temporary)
        environment = dict(os.environ, GITHUB_DEPENDENCY_GRAPH_ENABLED='false',
                           OPENBANK_DEPENDENCY_PLAN_DIRECTORY=str(work / 'plan'))
        subprocess.run([*command, '--init-script', str(scripts / 'dependency-resolution-plan.init.gradle'),
                        'openbankDependencyResolutionPlan'], cwd=repository, env=environment, check=True)
        plans = [json.loads(p.read_text()) for p in (work / 'plan').glob('*.json')]
        batches = project_batches(plans, repository)
        snapshots = []
        for index, projects in enumerate(batches):
            reports = work / f'batch-{index}'
            environment = dict(os.environ, DEPENDENCY_GRAPH_REPORT_DIR=str(reports),
                               GITHUB_DEPENDENCY_GRAPH_ENABLED='false' if args.gradle_init else 'true',
                               DEPENDENCY_GRAPH_INCLUDE_PROJECTS='^(?:' +
                               '|'.join(re.escape(p) for p in projects) + ')$')
            init = ['--init-script', str(args.gradle_init.resolve())] if args.gradle_init else []
            print(f'Resolving dependency batch {index + 1}/{len(batches)}', flush=True)
            subprocess.run([*command, *init, '--init-script',
                            str(scripts / 'verify-dependency-resolution.init.gradle'),
                            ':ForceDependencyResolutionPlugin_resolveAllDependencies'],
                           cwd=repository, env=environment, check=True)
            files = list(reports.rglob('*.json'))
            require(len(files) == 1, 'missing or ambiguous batch snapshot')
            snapshot = json.loads(files[0].read_text())
            require(snapshot['sha'] == expected_sha, 'batch snapshot SHA mismatch')
            require(snapshot['ref'] == os.environ['GITHUB_DEPENDENCY_GRAPH_REF'] and
                    snapshot['job']['id'] == os.environ['GITHUB_DEPENDENCY_GRAPH_JOB_ID'] and
                    snapshot['job']['correlator'] == os.environ['GITHUB_DEPENDENCY_GRAPH_JOB_CORRELATOR'],
                    'batch snapshot workflow identity mismatch')
            snapshots.append(snapshot)
        result = merge_snapshots(snapshots)
        destination.mkdir(parents=True, exist_ok=True)
        pending = destination / 'complete-snapshot.tmp'
        pending.write_text(json.dumps(result, separators=(',', ':')) + '\n')
        pending.replace(destination / 'complete-snapshot.json')
        print(f'Complete dependency snapshot: {len(batches)} verified batches', flush=True)


if __name__ == '__main__':
    main()
