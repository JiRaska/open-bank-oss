#!/usr/bin/env python3
"""Select cross-package Admin UI RBAC guards from their literal backend paths.

Used by CI's backend-only fast lane. UI changes run the full UI suite instead.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
GUARDS = Path('openbank-admin-ui/src/test')
LITERAL = re.compile(r"['\"](\.\./\.\./openbank-[^'\"]+)['\"]")
SAFE_TEST = re.compile(r"[a-z0-9.-]+-rbac\.guard\.test\.ts")
GITOPS_PREFIX = 'openbank-infra/gitops/'


def dependencies(repo: Path) -> dict[str, set[str]]:
    """Read every RBAC guard's backend inputs; fail on dynamic backend paths."""
    selected: dict[str, set[str]] = {}
    resolved_repo = repo.resolve()
    for test in sorted((repo / GUARDS).glob('*-rbac.guard.test.ts')):
        if not SAFE_TEST.fullmatch(test.name):
            raise ValueError(f'unsafe RBAC test name: {test.name}')
        body = test.read_text()
        inputs = set()
        paths = LITERAL.findall(body)
        if body.count('../../') != len(paths):
            raise ValueError(f'dynamic backend source in {test.name}')
        for path in paths:
            source = (repo / 'openbank-admin-ui/src' / path).resolve()
            source.relative_to(resolved_repo)
            if not source.is_file():
                raise ValueError(f'RBAC source missing: {source.relative_to(resolved_repo)}')
            inputs.add(source.relative_to(resolved_repo).as_posix())
        if inputs:
            selected[test.name] = inputs
    return selected


def select(repo: Path, changed: set[str]) -> list[str]:
    return sorted(name for name, inputs in dependencies(repo).items() if inputs & changed)


def registry_guard_needed(changed: set[str]) -> bool:
    """The service-registry guard reads the whole GitOps tree, not named files."""
    return any(path.startswith(GITOPS_PREFIX) for path in changed)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--base', required=True)
    parser.add_argument('--head', default='HEAD')
    args = parser.parse_args()
    changed = subprocess.check_output(
        ['git', '-C', str(ROOT), 'diff', '--name-only', '-z', f'{args.base}...{args.head}', '--']
    )
    names = {p.decode() for p in changed.split(b'\0') if p}
    print('rbac_tests=' + ' '.join(select(ROOT, names)))
    print('registry_test=' + str(registry_guard_needed(names)).lower())


if __name__ == '__main__':
    main()
