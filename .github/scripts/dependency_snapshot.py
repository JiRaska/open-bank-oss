# SPDX-License-Identifier: Apache-2.0
"""Generate a complete, bounded-memory fleet snapshot. Publication is a separate step."""
import json
from copy import deepcopy

IDENTITY = ('version', 'sha', 'ref', 'job', 'detector')

def load_snapshot(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('duplicate JSON key: ' + key)
            result[key] = value
        return result
    with open(path) as stream:
        return json.load(stream, object_pairs_hook=unique)

def validate_snapshot(snapshot):
    from datetime import datetime
    if not isinstance(snapshot, dict) or set(snapshot) != set(IDENTITY) | {'scanned', 'manifests'}:
        raise ValueError('unexpected snapshot schema')
    scanned = snapshot['scanned']
    if not isinstance(scanned, str) or not scanned.endswith('Z'):
        raise ValueError('invalid snapshot timestamp')
    datetime.fromisoformat(scanned.replace('Z', '+00:00'))
    if not isinstance(snapshot['manifests'], dict):
        raise TypeError('invalid manifests')
    for manifest in snapshot['manifests'].values():
        if not isinstance(manifest, dict) or set(manifest) != {'name', 'file', 'resolved'}:
            raise ValueError('unexpected manifest schema')
        if not isinstance(manifest['resolved'], dict):
            raise TypeError('invalid resolved graph')
        for node in manifest['resolved'].values():
            if not isinstance(node, dict) or set(node) - {'package_url', 'relationship', 'scope', 'dependencies'}:
                raise ValueError('unexpected node schema')
            if not isinstance(node.get('package_url'), str) or not node['package_url'].startswith('pkg:'):
                raise ValueError('invalid package identity')
            if node.get('relationship') not in ('direct', 'indirect'):
                raise ValueError('invalid dependency relationship')
            if 'scope' in node and node['scope'] not in ('runtime', 'development'):
                raise ValueError('invalid dependency scope')
            edges = node.get('dependencies', [])
            if not isinstance(edges, list) or not all(isinstance(edge, str) for edge in edges):
                raise ValueError('malformed dependency edges')


def merge_snapshots(parts, expected_shards, identity):
    if not parts or set(parts) != set(expected_shards) or len(expected_shards) != len(set(expected_shards)):
        raise ValueError('incomplete or duplicate shard inventory')
    result = None
    for shard in sorted(parts):
        snapshot = parts[shard]
        validate_snapshot(snapshot)
        if any(snapshot.get(key) != identity.get(key) for key in IDENTITY):
            raise ValueError('snapshot identity mismatch: ' + shard)
        if not snapshot.get('manifests'):
            raise ValueError('empty manifests: ' + shard)
        if result is None:
            result = deepcopy(snapshot)
            result['manifests'] = {}
        result['scanned'] = max(result['scanned'], snapshot['scanned'])
        for name, manifest in snapshot['manifests'].items():
            header = {k: v for k, v in manifest.items() if k != 'resolved'}
            if name not in result['manifests']:
                result['manifests'][name] = dict(deepcopy(header), resolved={})
            target = result['manifests'][name]
            if {k: v for k, v in target.items() if k != 'resolved'} != header:
                raise ValueError('manifest metadata mismatch')
            if not manifest.get('resolved'):
                raise ValueError('empty dependency graph')
            for node_id, node in manifest['resolved'].items():
                if not node.get('package_url'):
                    raise ValueError('missing package identity')
                if node_id not in target['resolved']:
                    target['resolved'][node_id] = deepcopy(node)
                    continue
                prior = target['resolved'][node_id]
                special = {'dependencies', 'relationship', 'scope'}
                if {k:v for k,v in prior.items() if k not in special} != {k:v for k,v in node.items() if k not in special}:
                    raise ValueError('conflicting package identity or metadata')
                for field, priority in (('relationship', ('indirect', 'direct')), ('scope', ('development', 'runtime'))):
                    a, b = prior.get(field), node.get(field)
                    if a is None and b is None:
                        continue
                    if a not in priority or b not in priority:
                        raise ValueError('incompatible ' + field)
                    prior[field] = max((a,b), key=priority.index)
                prior['dependencies'] = sorted(set(prior.get('dependencies', [])) | set(node.get('dependencies', [])))
    for manifest in result['manifests'].values():
        nodes = manifest['resolved']
        for node in nodes.values():
            edges = node.get('dependencies', [])
            if not isinstance(edges, list) or not all(isinstance(e, str) for e in edges):
                raise ValueError('malformed edges')
            if not set(edges) <= set(nodes):
                raise ValueError('dangling dependency edge')
            node['dependencies'] = sorted(set(edges))
    return result


def inventory(repo):
    """Mirror settings.gradle.kts module discovery; never accept an empty fleet."""
    import re
    modules = sorted(p.name for p in repo.glob('openbank-*')
                     if p.is_dir() and (p / 'build.gradle.kts').is_file())
    if not modules or any(not re.fullmatch(r'openbank-[a-z0-9-]+', m) for m in modules):
        raise ValueError('invalid or empty Gradle module inventory')
    return modules


def shard_timeout(deadline, now, limit):
    """Keep serial shards inside the producer's total resolution budget."""
    remaining = deadline - now
    if remaining <= 0:
        raise RuntimeError('fleet dependency resolution exceeded its 20-minute budget')
    return min(limit, remaining)


def run_bounded(command, repo, env, logfile, timeout=180):
    import os
    import signal
    import subprocess
    with logfile.open('w') as log:
        process = subprocess.Popen(command, cwd=repo, env=env, stdout=log,
                                   stderr=subprocess.STDOUT, start_new_session=True)
        try:
            code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            raise RuntimeError('dependency shard timed out; see ' + str(logfile)) from None
    if code:
        raise RuntimeError(f'dependency shard failed ({code}); see {logfile}')


def verify_coverage(directory, projects, sha):
    """Require one successful resolution receipt per intended project, on this SHA."""
    receipts = [load_snapshot(p) for p in directory.glob('*.json')]
    found = [r.get('project') for r in receipts]
    if len(found) != len(set(found)) or set(found) != set(projects):
        raise ValueError('incomplete or duplicate project resolution receipts')
    for receipt in receipts:
        if receipt.get('version') != 1 or receipt.get('sha') != sha:
            raise ValueError('wrong resolution receipt subject')
        configs = receipt.get('configurations')
        if not isinstance(configs, list) or len(configs) != len(set(configs)):
            raise ValueError('malformed configuration inventory')
        if receipt['project'] != ':' and not configs:
            raise ValueError('module has no resolved configurations')


def generate(repo, output, env, extra_arguments=()):
    import os
    import re
    import subprocess
    import time

    output.mkdir(parents=True, exist_ok=False)
    sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip()
    required = ('GITHUB_DEPENDENCY_GRAPH_SHA', 'GITHUB_DEPENDENCY_GRAPH_REF',
                'GITHUB_DEPENDENCY_GRAPH_JOB_ID', 'GITHUB_DEPENDENCY_GRAPH_JOB_CORRELATOR')
    if any(not env.get(key) for key in required):
        raise ValueError('missing dependency graph identity from setup-gradle')
    if sha != env['GITHUB_DEPENDENCY_GRAPH_SHA']:
        raise ValueError('checkout and dependency graph SHA differ')
    if env.get('GITHUB_DEPENDENCY_GRAPH_WORKSPACE') != str(repo):
        raise ValueError('checkout and dependency graph workspace differ')
    correlator = env['GITHUB_DEPENDENCY_GRAPH_JOB_CORRELATOR']
    if not re.fullmatch(r'[A-Za-z0-9_. -]+', correlator):
        raise ValueError('unsafe dependency graph correlator')
    for key in ('DEPENDENCY_GRAPH_INCLUDE_PROJECTS', 'DEPENDENCY_GRAPH_EXCLUDE_PROJECTS',
                'DEPENDENCY_GRAPH_INCLUDE_CONFIGURATIONS'):
        if env.get(key):
            raise ValueError('fleet resolution cannot use partial graph filters')
    excluded = '^(detachedConfiguration.*|classpath)$'
    if env.get('DEPENDENCY_GRAPH_EXCLUDE_CONFIGURATIONS') != excluded:
        raise ValueError('unexpected dependency configuration policy')
    modules = inventory(repo)
    # Nineteen serial shards can otherwise consume 58 minutes under their
    # individual caps, while the Actions job ends after 30. Reserve ten minutes
    # for setup, validation and submission; never publish a partial graph.
    deadline = time.monotonic() + 20 * 60
    (output / 'inventory.json').write_text(json.dumps(modules, indent=2))
    expected = [f'shard-{i // 4:02}' for i in range(0, len(modules), 4)]
    identity = {'version': 0, 'sha': sha, 'ref': env['GITHUB_DEPENDENCY_GRAPH_REF'],
                    'job': {'id': env['GITHUB_DEPENDENCY_GRAPH_JOB_ID'], 'correlator': correlator},
                    'detector': {'name': 'GitHub Dependency Graph Gradle Plugin', 'version': '1.4.2',
                                  'url': 'https://github.com/gradle/github-dependency-graph-gradle-plugin'}}
    parts, receipts = {}, []
    for i in range(0, len(modules), 4):
        shard = output / expected[i // 4]
        shard.mkdir()
        reports, coverage = shard / 'reports', shard / 'coverage'
        reports.mkdir()
        coverage.mkdir()
        projects = [':' + module for module in modules[i:i + 4]]
        if i == 0:
            projects.insert(0, ':')
        tasks = [p.rstrip(':') + ':ForceDependencyResolutionPlugin_resolveProjectDependencies'
                 for p in projects]
        child_env = dict(env, DEPENDENCY_GRAPH_REPORT_DIR=str(reports),
                         DEPENDENCY_GRAPH_COVERAGE_DIR=str(coverage))
        command = ['./gradlew', '--init-script', str(repo / '.github/scripts/dependency-resolution-strict.init.gradle'),
                   '--no-daemon', '--no-configuration-cache', '--no-configure-on-demand',
                   '--max-workers=2', '-Dorg.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g',
                   *extra_arguments, *tasks]
        started = time.monotonic()
        # The first process also warms build-logic and resolves the root project.
        # Its successful hosted run took 135s; a cold run was killed at 180s
        # while still resolving projects. Keep every shard bounded, but give
        # only this extra-work shard measured cold-run headroom.
        run_bounded(command, repo, child_env, shard / 'run.log',
                    timeout=shard_timeout(deadline, time.monotonic(), 240 if i == 0 else 180))
        verify_coverage(coverage, projects, sha)
        snapshots = list(reports.glob('*.json'))
        if len(snapshots) != 1:
            raise ValueError('expected one fresh snapshot per shard')
        parts[shard.name] = load_snapshot(snapshots[0])
        receipts.append({'shard': shard.name, 'projects': projects,
                             'seconds': round(time.monotonic() - started, 2)})
        (output / 'results.json').write_text(json.dumps(receipts, indent=2))
        print(f'{shard.name}: {len(projects)} projects resolved', flush=True)
    if subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip() != sha:
        raise ValueError('checkout changed while generating graph')
    if inventory(repo) != modules:
        raise ValueError('module inventory changed while generating graph')
    merged = merge_snapshots(parts, expected, identity)
    temporary = output / 'merged.json.tmp'
    temporary.write_text(json.dumps(merged, indent=2))
    os.replace(temporary, output / 'merged.json')
    return merged


if __name__ == '__main__':
    import argparse
    import os
    from pathlib import Path
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    try:
        generate(root, args.out.resolve(), os.environ)
    except (TypeError, ValueError, RuntimeError) as error:
        parser.exit(1, str(error) + '\n')
