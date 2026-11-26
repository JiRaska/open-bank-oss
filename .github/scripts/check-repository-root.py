#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Check the Git index, including force-added ignored files, against root policy.

Untracked/ignored local data is deliberately outside the publishable-tree check.
Both policy and module markers come from the index so unstaged edits cannot mask
what a commit will publish. Run after staging, or in a clean CI checkout.
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

import yaml

POLICY = "openbank-libs/governance/rules.yaml"


def git(root: Path, *args: str) -> str:
    return subprocess.check_output(
        ["git", "-C", str(root), *args], stderr=subprocess.PIPE
    ).decode("utf-8")


def inspect(root: Path) -> tuple[list[str], int]:
    paths = set(git(root, "ls-files", "-z").split("\0")) - {""}
    if not paths:
        raise ValueError("empty Git index; no repository subjects inspected")
    document = yaml.safe_load(git(root, "show", f":{POLICY}"))
    policy = document["repository_root_layout"]
    allowed = {}
    for kind in ("files", "directories"):
        values = policy[kind]
        if not isinstance(values, list) or not values:
            raise ValueError(f"root policy {kind} must be a nonempty list")
        if any(not isinstance(x, str) or not x or '/' in x or x in ('.', '..')
               or any(c in x for c in '*?[]\n\r') for x in values):
            raise ValueError(f"root policy {kind} must contain exact root names")
        if len(set(values)) != len(values):
            raise ValueError(f"duplicate root policy {kind}")
        allowed[kind] = set(values)
    if allowed['files'] & allowed['directories']:
        raise ValueError("root policy file/directory overlap")
    files = {x for x in paths if '/' not in x}
    directories = {x.split('/')[0] for x in paths if '/' in x}
    # settings.gradle.kts discovers modules from this exact file marker. Requiring
    # it in the index prevents an untracked build file from legitimising scratch.
    modules = {d for d in directories if re.fullmatch(r'openbank-[a-z0-9-]+', d)
               and f'{d}/build.gradle.kts' in paths}
    errors = [f"unexpected root file: {x}" for x in sorted(files - allowed['files'])]
    errors += [f"unexpected root directory: {x}/"
               for x in sorted(directories - allowed['directories'] - modules)]
    errors += [f"stale root file declaration: {x}"
               for x in sorted(allowed['files'] - files)]
    errors += [f"stale root directory declaration: {x}/"
               for x in sorted(allowed['directories'] - directories)]
    return errors, len(files | directories)


def check(root: Path) -> int:
    try:
        errors, subjects = inspect(root)
    except (subprocess.CalledProcessError, UnicodeError, OSError, ValueError,
            KeyError, TypeError, yaml.YAMLError) as exc:
        print(f"::error::Cannot determine repository root layout: {exc}")
        return 2
    print(f"SUBJECTS={subjects}")
    for error in errors:
        print(f"::error::{error}; see docs/repository-layout.md")
    if not errors:
        print("Repository root layout OK (Git index).")
    return 1 if errors else 0


def self_test() -> int:
    # run-gates supplies a private GIT_INDEX_FILE; fixture repositories must not
    # inherit that index (nor a hook's GIT_DIR/WORK_TREE) and mutate the caller.
    for name in ("GIT_INDEX_FILE", "GIT_DIR", "GIT_WORK_TREE", "GIT_COMMON_DIR"):
        os.environ.pop(name, None)
    script = Path(__file__).resolve()
    cases = 0
    with tempfile.TemporaryDirectory(prefix="root-layout-") as tmp:
        root = Path(tmp)
        git(root, "init", "-q")

        def put(path: str, content: str = "fixture\n", stage: bool = True) -> None:
            target = root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content)
            if stage:
                git(root, "add", "-f", "--", path)

        def expect(code: int, message: str) -> None:
            nonlocal cases
            result = subprocess.run([sys.executable, str(script), "--root", str(root)],
                                    capture_output=True, text=True, check=False)
            if result.returncode != code or message not in result.stdout:
                raise AssertionError(f"expected {code}/{message}: {result.stdout} {result.stderr}")
            cases += 1

        expect(2, "empty Git index")
        policy = {'repository_root_layout': {'files': ['README.md', '.gitignore'],
                  'directories': ['openbank-libs', 'docs']}}
        put(POLICY, yaml.safe_dump(policy))
        put('README.md')
        put('.gitignore', '/tmp/\n/build/\n')
        put('docs/layout.md')
        expect(0, 'layout OK')
        put('tmp/local.txt', stage=False)
        put('notes.txt', stage=False)
        expect(0, 'layout OK')
        git(root, 'add', '-f', '--', 'tmp/local.txt')
        expect(1, 'unexpected root directory: tmp/')
        git(root, 'rm', '--cached', '--', 'tmp/local.txt')
        git(root, 'add', '--', 'notes.txt')
        expect(1, 'unexpected root file: notes.txt')
        git(root, 'rm', '--cached', '--', 'notes.txt')
        put('openbank-scratch/report.txt')
        put('openbank-scratch/build.gradle.kts', stage=False)
        expect(1, 'unexpected root directory: openbank-scratch/')
        git(root, 'add', '--', 'openbank-scratch/build.gradle.kts')
        expect(0, 'layout OK')
        git(root, 'rm', '--cached', '--', 'README.md')
        expect(1, 'stale root file declaration: README.md')
        (root/'README.md').unlink()
        put('README.md/child.txt')
        expect(1, 'unexpected root directory: README.md/')
        git(root, 'rm', '--cached', '--', 'README.md/child.txt')
        (root/'README.md/child.txt').unlink()
        (root/'README.md').rmdir()
        put('README.md')
        git(root, 'add', '--', 'README.md')
        put(POLICY, 'broken: [', stage=False)
        expect(0, 'layout OK')
        git(root, 'add', '--', POLICY)
        expect(2, 'Cannot determine')
        put(POLICY, yaml.safe_dump({'repository_root_layout': {'files': ['*'],
                                                              'directories': ['docs']}}))
        expect(2, 'exact root names')
        git(root, 'rm', '--cached', '--', POLICY)
        expect(2, 'Cannot determine')
    print(f"self-test OK: {cases} index/CLI cases")
    return 0


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    sys.exit(self_test() if args.self_test else check(args.root))
