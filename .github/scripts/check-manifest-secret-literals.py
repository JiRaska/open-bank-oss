#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Reject checked-in Secret bodies, including literal Kyverno-generated credentials."""
from pathlib import Path
import re
import sys

import yaml


# Only this constrained runtime derivation is understood, not arbitrary templates.
# A literal, concatenation or fallback cannot supply the password in this grammar.
RUNTIME_DIGEST = re.compile(
    r"\{\{\s*sha256\(join\(':',\s*\['[a-zA-Z0-9:_-]{1,64}',\s*"
    r"request\.object\.data\.[a-zA-Z0-9_-]+\]\)\)\s*\}\}"
)


class StrictLoader(yaml.SafeLoader):
    pass


def mapping(loader, node, deep=False):
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in result:
            raise ValueError('duplicate YAML mapping key')
        result[key] = loader.construct_object(value_node, deep=deep)
    return result


StrictLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, mapping)


def check_document(document):
    if not isinstance(document, dict):
        return
    if document.get('kind') == 'List':
        for item in document.get('items', []):
            check_document(item)
    if document.get('kind') == 'Secret':
        if document.get('data') or document.get('stringData'):
            raise ValueError('inline Secret payload')
    if document.get('kind') not in ('ClusterPolicy', 'Policy'):
        return
    for rule in document.get('spec', {}).get('rules', []):
        generated = rule.get('generate', {})
        if generated.get('kind') != 'Secret' or 'data' not in generated:
            continue
        body = generated['data']
        if not isinstance(body, dict) or body.get('data'):
            raise ValueError('unverified generated Secret payload')
        values = body.get('stringData', {})
        if not isinstance(values, dict):
            raise ValueError('unverified generated Secret template')
        for key, value in values.items():
            # The basic-auth username is public identity, never an authentication secret.
            if key == 'username' and body.get('type') == 'kubernetes.io/basic-auth' and isinstance(value, str):
                continue
            if not isinstance(value, str) or RUNTIME_DIGEST.fullmatch(value) is None:
                raise ValueError('literal or unsupported generated Secret credential')


def main(root):
    failures = 0
    for path in sorted(Path(root).rglob('*')):
        if not path.is_file() or not path.name.endswith(('.yaml', '.yml', '.yaml.tmpl', '.yml.tmpl')):
            continue
        source = path.read_text()
        if 'Secret' not in source:
            continue
        try:
            for document in yaml.load_all(source, Loader=StrictLoader):
                check_document(document)
        except (yaml.YAMLError, ValueError, TypeError, AttributeError):
            # Do not print parser exceptions or payloads: they can contain the secret.
            print(f'{path}: inline Secret credential or unverified manifest structure')
            failures += 1
    return int(failures > 0)


if __name__ == '__main__':
    sys.exit(main(sys.argv[1] if len(sys.argv) == 2 else 'openbank-infra'))
