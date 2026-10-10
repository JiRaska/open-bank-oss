# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Validate resolved project scope before creating an isolated metadata checkout."""
import json
import re
from pathlib import Path


def plan(receipts, selected, known_projects, root_inputs, model_commit, current_commit, source_clean):
    known = set(known_projects)
    full = lambda reason: {'mode': 'full', 'reason': reason, 'projects': sorted(known)}
    if not source_clean or not isinstance(model_commit, str) or not re.fullmatch('[0-9a-f]{40}', model_commit) or model_commit != current_commit:
        return full('unbound or stale source model')
    if not selected:
        return full('empty project selection')
    requested = set(selected) | set(root_inputs)
    if not requested or not requested <= known:
        return full('unknown or empty project selection')
    pending = list(requested)
    visited = set()
    while pending:
        name = pending.pop()
        if name in visited:
            continue
        data = receipts.get(name)
        if not isinstance(data, dict) or not isinstance(data.get('closure'), list) or not isinstance(data.get('receipts'), dict):
            return full('missing or malformed resolved model: ' + name)
        closure = data['closure']
        if any(not isinstance(p, str) or p not in known for p in closure) or name not in closure:
            return full('unknown project edge: ' + name)
        for artifacts in data['receipts'].values():
            if not isinstance(artifacts, list):
                return full('malformed configuration: ' + name)
            for artifact in artifacts:
                if not isinstance(artifact, dict) or any(not isinstance(artifact.get(k), str) or not artifact[k] for k in ('component', 'artifact', 'sha256')):
                    return full('missing artifact receipt: ' + name)
                if not re.fullmatch('[0-9a-f]{64}', artifact['sha256']):
                    return full('invalid artifact checksum: ' + name)
        visited.add(name)
        pending.extend(set(closure) - visited)
    return {'mode': 'scoped', 'projects': sorted(visited)}



def source_identity(root):
    """Untracked sources can affect dynamic project discovery, so reject them too."""
    import subprocess
    status = subprocess.check_output(['git', 'status', '--porcelain', '--untracked-files=normal'], cwd=root, text=True)
    if status.strip():
        return None
    return subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()


def tracked_projects(root, commit):
    import subprocess
    paths = subprocess.check_output(['git', 'ls-tree', '-r', '--name-only', commit], cwd=root, text=True).splitlines()
    return {':' + p.split('/')[0] for p in paths if re.fullmatch(r'openbank-[a-z0-9-]+/build\.gradle\.kts', p)}


def archive_source(root, commit, projects, destination):
    """Archive tracked sources, retaining non-Gradle trees and the project closure."""
    import subprocess
    import tarfile
    import tempfile
    if source_identity(root) != commit:
        raise ValueError('source changed before archive creation')
    known = {p[1:] for p in tracked_projects(root, commit)}
    retained = {p[1:] for p in projects}
    if not retained or not retained <= known:
        raise ValueError('unknown archive project')
    with tempfile.TemporaryDirectory() as tmp:
        tar_path = Path(tmp) / 'source.tar'
        subprocess.run(['git', 'archive', '--format=tar', '--output', str(tar_path), commit], cwd=root, check=True)
        with tarfile.open(tar_path) as archive:
            members = [m for m in archive.getmembers() if m.name.split('/')[0] not in known or m.name.split('/')[0] in retained]
            archive.extractall(destination, members=members, filter='data')
    if source_identity(root) != commit:
        raise ValueError('source changed during archive creation')
