# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Recognize the supported owner-prefix rule in immutable pre-merge source, without executing it.

This is policy CONTEXT, not an attestation of the workflow code that actually executed a merge,
nor a certificate that every other governance requirement was satisfied.
"""
import ast
import base64
import hashlib
import re

POLICY_PATH = '.github/scripts/merge-train.py'
# Deliberately bounded reconstruction, not a Python sandbox. Whole-source identities from
# the canonical first parents of #13750, #13725, #13794 and #13770/#13847 were independently reviewed.
# Any additional whole-source identity requires the same explicit review before extension.
REVIEWED_POLICY_BLOBS = frozenset({
    '1cb6da5c76d3503d4361507a965dc17d28856d9c',
    '0943c6749913e153f4687df42b7e2a53592cf064',
    '0d0616110f79ec217cba1709a0a3908ae3c8e933',
    '32eb598ea12065ec54e6b16769e14d870e2d21e1',
    # Independently reviewed canonical first-parent source: #13697, #13772, #13735.
    '589e9803da67609d7bcb43e79cabaf5e73b44e37',
    # Independently reviewed canonical first-parent source: #13904.
    '6d20261e47cf64d423ac251e236f70e88db61461',
})
_SHA = re.compile(r'[0-9a-f]{40}')
_BINDINGS = {'EXCLUDED_BRANCH_PREFIXES', 'OWNER_APPROVAL_CONTEXT'}
_PROTECTED = _BINDINGS | {'owner_approval_valid', 'any', 'type', 'int', '__builtins__'}
_SUPPORTED_CHECK = '''
def owner_approval_valid(number: int, ref: str, app: dict | None) -> bool:
    if not any(ref.startswith(pre) for pre in EXCLUDED_BRANCH_PREFIXES):
        return True
    return (type(number) is int and number > 0
            and (app or {}).get(OWNER_APPROVAL_CONTEXT) == ("success", f"owner-approved PR #{number}"))
'''


def _check_shape(function):
    # Comments, formatting and the explanatory docstring are not executable policy.
    if function.body and isinstance(function.body[0], ast.Expr) and isinstance(function.body[0].value, ast.Constant) and isinstance(function.body[0].value.value, str):
        function.body = function.body[1:]
    return ast.dump(function, include_attributes=False)


def requires_exact_head(source, branch, expected_context):
    if not isinstance(branch, str) or not branch or len(branch) > 255:
        raise ValueError('missing canonical policy branch')
    try:
        module = ast.parse(source)
    except (SyntaxError, RecursionError, ValueError) as error:
        raise ValueError('unreadable owner-prefix policy') from error
    functions = [n for n in ast.walk(module) if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)) and n.name == 'owner_approval_valid']
    if len(functions) != 1 or functions[0] not in module.body or _check_shape(functions[0]) != _check_shape(ast.parse(_SUPPORTED_CHECK).body[0]):
        raise ValueError('unsupported owner-prefix policy function')
    bindings = {}
    approved_targets = set()
    for statement in module.body:
        if isinstance(statement, ast.Assign) and len(statement.targets) == 1 and isinstance(statement.targets[0], ast.Name):
            name = statement.targets[0].id
            if name in _BINDINGS:
                if name in bindings:
                    raise ValueError('duplicate owner-prefix policy binding')
                try:
                    bindings[name] = ast.literal_eval(statement.value)
                except (ValueError, TypeError, RecursionError) as error:
                    raise ValueError('owner-prefix policy must be literal') from error
                approved_targets.add(id(statement.targets[0]))
    if bindings != {'EXCLUDED_BRANCH_PREFIXES': ('codex/',), 'OWNER_APPROVAL_CONTEXT': expected_context}:
        raise ValueError('unsupported owner-prefix policy constants')
    parents = {id(child): parent for parent in ast.walk(module) for child in ast.iter_child_nodes(parent)}
    for node in ast.walk(module):
        if isinstance(node, ast.Name) and node.id in _PROTECTED and isinstance(node.ctx, (ast.Store, ast.Del)) and id(node) not in approved_targets:
            raise ValueError('owner-prefix policy is rebound')
        if isinstance(node, ast.Global) and set(node.names) & _PROTECTED:
            raise ValueError('owner-prefix policy has a dynamic global binding')
        if isinstance(node, ast.Attribute) and node.attr in _PROTECTED and isinstance(node.ctx, (ast.Store, ast.Del)):
            raise ValueError('owner-prefix policy is rebound through an attribute')
        if isinstance(node, ast.Name) and node.id in {'globals', 'locals', 'exec', 'eval'}:
            # Existing self-tests temporarily replace unrelated API/clock fixtures through
            # globals()['literal']. Do not mistake these for changes to the owner rule.
            call = parents.get(id(node))
            access = parents.get(id(call))
            if not (node.id == 'globals' and isinstance(call, ast.Call) and call.func is node
                    and not call.args and not call.keywords and isinstance(access, ast.Subscript)
                    and access.value is call and isinstance(access.slice, ast.Constant)
                    and isinstance(access.slice.value, str) and access.slice.value not in _PROTECTED):
                raise ValueError('dynamic owner-prefix policy is unsupported')
        if isinstance(node, (ast.Import, ast.ImportFrom)) and any((a.asname or a.name.split('.')[0]) in _PROTECTED for a in node.names):
            raise ValueError('owner-prefix policy is rebound by an import')
    return branch.startswith(bindings['EXCLUDED_BRANCH_PREFIXES'])


def _sha(value):
    return isinstance(value, str) and _SHA.fullmatch(value) is not None


def premerge_policy_context(pull, repo, github, expected_context):
    """Read only the first parent's policy tree; never use the PR's policy or current main's copy."""
    merge_sha = pull['merge_commit_sha']
    commit = github.get(f'/repos/{repo}/git/commits/{merge_sha}')
    parents = commit.get('parents') or []
    if commit.get('sha') != merge_sha or not parents or not _sha(parents[0].get('sha')):
        raise ValueError('canonical merge parent is unavailable')
    parent = parents[0]['sha']
    comparison = github.get(f'/repos/{repo}/compare/{parent}...main')
    if (comparison.get('status') not in ('ahead', 'identical')
            or (comparison.get('base_commit') or {}).get('sha') != parent
            or (comparison.get('merge_base_commit') or {}).get('sha') != parent):
        raise ValueError('pre-merge policy context is not on canonical main')
    base = github.get(f'/repos/{repo}/git/commits/{parent}')
    tree_sha = (base.get('tree') or {}).get('sha')
    if base.get('sha') != parent or not _sha(tree_sha):
        raise ValueError('pre-merge policy tree is unavailable')
    for name in POLICY_PATH.split('/'):
        tree = github.get(f'/repos/{repo}/git/trees/{tree_sha}')
        if tree.get('sha') != tree_sha or tree.get('truncated') is True or not isinstance(tree.get('tree'), list):
            raise ValueError('pre-merge policy tree is incomplete')
        entries = [e for e in tree['tree'] if isinstance(e, dict) and e.get('path') == name]
        if len(entries) != 1 or not _sha(entries[0].get('sha')):
            raise ValueError('pre-merge policy path is missing or ambiguous')
        entry = entries[0]
        if name == 'merge-train.py':
            if entry.get('type') != 'blob' or entry.get('mode') not in ('100644', '100755'):
                raise ValueError('pre-merge policy must be a regular file')
            blob_sha = entry['sha']
        else:
            if entry.get('type') != 'tree' or entry.get('mode') != '040000':
                raise ValueError('pre-merge policy parent must be a directory')
            tree_sha = entry['sha']
    blob = github.get(f'/repos/{repo}/git/blobs/{blob_sha}')
    if (blob.get('sha') != blob_sha or blob.get('encoding') != 'base64'
            or type(blob.get('size')) is not int or not 0 < blob['size'] <= 500_000
            or not isinstance(blob.get('content'), str) or len(blob['content']) > 700_000):
        raise ValueError('pre-merge policy blob is invalid')
    try:
        raw = base64.b64decode(''.join(blob['content'].split()), validate=True)
        source = raw.decode('utf-8')
    except (ValueError, UnicodeError) as error:
        raise ValueError('pre-merge policy encoding is invalid') from error
    if len(raw) != blob['size'] or hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest() != blob_sha:
        raise ValueError('pre-merge policy blob digest disagrees')
    if blob_sha not in REVIEWED_POLICY_BLOBS:
        raise ValueError('unreviewed immutable policy blob; explicit policy-source review required')
    if requires_exact_head(source, (pull.get('head') or {}).get('ref'), expected_context):
        raise ValueError('canonical branch requires exact-head owner authorization')
    return {'policyCommitSha': parent, 'policyBlobSha': blob_sha}
