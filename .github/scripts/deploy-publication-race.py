#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Preserve a concurrent pin only for automatic reconciliation of unchanged sources.

Compare the entire tracked tree, not Pact's narrower classpath model: workflow flags,
Dockerfiles, dependency locks and version markers all count. Only image-pin values in
GitOps component YAML may differ. This avoids the publication race; it does not skip
builds or certify reproducible binaries. Manual security rebuilds and provenance refreshes
retain their normal path. Unresolvable evidence aborts publication.
"""
import argparse
import re
import subprocess
import sys

TAG = re.compile(r'sandbox-([0-9a-f]{8,40})')
IMAGE = re.compile(
    rb'(?m)^([ \t]*image:[ \t]+[^\s#]+/openbank-[a-z0-9-]+:sandbox-)'
    rb'[0-9a-f]{8,40}(-run[1-9][0-9]*)?([ \t]*(?:#[^\r\n]*)?\r?$)'
)


def git(*args):
    return subprocess.check_output(['git', *args], stderr=subprocess.DEVNULL)


def resolve(tag):
    match = TAG.fullmatch(tag)
    if not match:
        raise ValueError('cannot resolve image source tag')
    return git('rev-parse', '--verify', match[1] + '^{commit}').decode().strip()


def same_sources(left, right):
    changed = git('diff', '--no-renames', '--name-only', '-z', left, right).split(b'\0')
    for raw in filter(None, changed):
        path = raw.decode()
        if not path.startswith('openbank-infra/gitops/components/') or not path.endswith(('.yaml', '.yml')):
            return False
        entries = [git('ls-tree', ref, '--', path).split() for ref in (left, right)]
        if any(not entry or entry[0] != b'100644' or entry[1] != b'blob' for entry in entries):
            return False
        blobs = [git('show', ref + ':' + path) for ref in (left, right)]
        normalized = [IMAGE.sub(rb'\1PIN\3', blob) for blob in blobs]
        if normalized[0] != normalized[1]:
            return False
    return True


def decision(event, reconcile, initial, current, candidate, source):
    if event != 'schedule' and not (event == 'workflow_dispatch' and reconcile == 'true'):
        return 'publish'
    if current == initial or current == candidate:
        return 'publish'
    # A run suffix denotes an intentional evidence/image refresh, not stale reconciliation.
    if re.fullmatch(r'sandbox-[0-9a-f]{8,40}-run[1-9][0-9]*', candidate):
        return 'publish'
    if not re.fullmatch(r'[0-9a-f]{40}', source):
        raise ValueError('invalid build source')
    target = resolve(candidate)
    if target != git('rev-parse', '--verify', source + '^{commit}').decode().strip():
        raise ValueError('candidate image does not match this build source')
    # Preserve a concurrently published provenance refresh when its source is identical.
    current_source_tag = re.sub(r'-run[1-9][0-9]*$', '', current)
    return 'preserve' if same_sources(resolve(current_source_tag), target) else 'publish'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('event', 'reconcile', 'initial', 'current', 'candidate', 'source'):
        parser.add_argument('--' + name, required=True)
    try:
        print(decision(**vars(parser.parse_args())))
        return 0
    except (ValueError, UnicodeError, subprocess.CalledProcessError):
        print('::error::cannot establish concurrent deployment source identity; refusing publication', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
