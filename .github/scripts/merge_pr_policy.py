# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Recognize the supported owner-prefix rule in immutable pre-merge source, without executing it.

This is policy CONTEXT, not an attestation of the workflow code that actually executed a merge,
nor a certificate that every other governance requirement was satisfied.
"""
import ast
import base64
import hashlib
import functools
from pathlib import Path
import re
import subprocess

POLICY_PATH = '.github/scripts/merge-train.py'
# A payload sanity bound, not a policy decision. It was 500_000 until merge-train.py passed it on
# 2026-11-07 (#14891, 508_801 B), after which every App-merged agent PR lost its merge record
# (#16187). The base64 bound follows from it: 4/3 expansion plus GitHub's newline every 60 chars.
MAX_POLICY_BLOB_BYTES = 4_000_000
MAX_POLICY_BLOB_BASE64 = (MAX_POLICY_BLOB_BYTES + 2) // 3 * 4 * 61 // 60 + 64
# Deliberately bounded reconstruction, not a Python sandbox. Whole-source identities from
# the canonical first parents of #13750, #13725, #13794 and #13770/#13847 were independently reviewed.
# Identities that sat at a first-parent commit of main are derived below and need no entry here;
# add one only for a source that never landed on main, with the same explicit review.
REVIEWED_POLICY_BLOBS = frozenset({
    '1cb6da5c76d3503d4361507a965dc17d28856d9c',
    '0943c6749913e153f4687df42b7e2a53592cf064',
    '0d0616110f79ec217cba1709a0a3908ae3c8e933',
    '32eb598ea12065ec54e6b16769e14d870e2d21e1',
    # Independently reviewed canonical first-parent source: #13697, #13772, #13735.
    '589e9803da67609d7bcb43e79cabaf5e73b44e37',
    # Independently reviewed canonical first-parent sources: #13988, #13773, #13992.
    '14bd8b1e81b94eb0a297f645007c44d1245732cc',
    '6ab73053858f41097033b13d5813ad70fcadc088',
    '82d51b27e40de7e15fb7d3180cc9baaad6ebde40',
    # Independently reviewed canonical first-parent source: #13904.
    '6d20261e47cf64d423ac251e236f70e88db61461',
    # Independently reviewed canonical first-parent source: #14186.
    'f1dad9057e095ae790dfe89819e24ae6692e8064',
    # Independently reviewed canonical first-parent source shared by #14150, #14182,
    # #14220, #14233, #14235, #14240, #14253, #14262, #14265, #14272 and #14277.
    'c5fa389c9724b4998f1c82b0281fc164194ca98b',
    # Independently reviewed canonical first-parent source: #14381.
    'a6909111d6622e77549e195be58e6332b64837b6',
    # Independently reviewed canonical first-parent source: #14373.
    '2af53eb7424798ae66558c81417190d2805943e7',
    # Independently reviewed whole-source revisions; fixtures remain inert data.
    '048d8fe9ab3f9b2b01b62d7390077c425031c1d1',
    'cf542f1481d9c5481cb768b8531d361586703657',
    '4a1ee1d5b71c0866893c86c1d56a4f686b3f3d8d',
    '6e04deb65dc7f6594a5037592e95321b41a53bf3',
    '8e5b4dfe28d6bf527fa8e677f21e902b35ca3949',
    # #16187: pre-merge sources of App-merged agent PRs whose records failed since 2026-11-07.
    # Whole source is in git history (no fixture); each passes requires_exact_head for agent/
    # and codex/ heads, census in #16187. PRs per identity follow.
    'c3c231206bd89e1a6e4cc82087c47861849925ec',  # #14599
    'c3f5f84addc56993e48fc6f7584d13c56f116dd1',  # #14878
    '52f3adac4f19689cdea66469f38eab941cdac70a',  # #14950
    '878155c9d9551e81368eedbb080a4180df4a3e6b',  # #15030, #15052
    'cc286f6df37486f27da067d09b398c2b463dbd70',  # #15045
    'd5ac0cb4874158b37b2efa58ecffea12c39f03bb',  # #15117, #15129, #15194, #15217, #15227, #15242
    'aa9e387f7ad18478ef0a715663b2a1b9c5bb25d2',  # #15254, #15258, #15285, #15293, #15295, #15345, #15364, #15365, #15408
    'c4a78ce15a290af44c6fda30557dd615ac5934b2',  # #15429, #15432, #15454
    'b8f977da6f6f493fdd1f9097c1e6517b85015c42',  # #15527, #15528, #15531
    'dca24c7fe6f72939b8de3d6945f5b90effe01226',  # #15534, #15535, #15537, #15540, #15544, #15547, #15548, #15686, #15691, #15692, #15720, #15743, #15783
    '4c030ee32eed2424d3842e8742e2c6fe4665d400',  # #15697
    'cd5f45e08b013b172805f2b9282cec2b2c550a8c',  # #15699, #15701, #15726, #15740, #15745, #15768, #15771, #15824, #15889, #15892
    # Pre-merge policy for #15897, #15960, #16005; structural check passes, see #16187.
    'bdc11a54da45ff594f12d72eb7b2b3bf35139a0f',
    # Pre-merge policy for #15898, #15948, #15983, #15989, #16017, #16020, #16021; structural check passes, see #16187.
    '9337dc5bf351dd3c9310b1ac9dafeea6ee1f2976',
    # Pre-merge policy for #15879, #15959, #15976, #16018, #16026, #16032, #16041, #16097; structural check passes, see #16187.
    'b03f72aa290b53f9826007b8a745fb77ea15b9ec',
    # Pre-merge policy for #16102, #16114, #16119, #16124; structural check passes, see #16187.
    '19f3b8c002b9f09439ca152b581121eba0e83274',
    # Pre-merge policy for main at 2026-11-14 (d907564cdc); structural check passes, see #16187.
    '231ad0e824f57749b3cfa482ba10593557cbec2f',
    # main merge-train.py after #16148 (ecbfaaa97b), #16180 (de481eb1d5) and #16105 (c80c311b04);
    # each is the first-parent source for PRs merged behind it. Structural check passes, see #16187.
    '31799f5c2ec415da6f968b5b0e8c725ccbd82b7e',  # after #16148
    '6df70b126a750a2a9f6e8fc658efc43b1cf2433a',  # after #16180
    '38c70e23111a78639f9aaae62b0494a90d3c0512',  # after #16105
})
# Every merge-train.py identity that canonical main itself carried at a first-parent commit was
# reviewed by the PR that landed it: main admits nothing else. Deriving that set from history
# replaces hand-adding each new blob above (#16187), which reddened every unrelated open PR and
# failed merge records whenever main's controller moved. The hand list stays for identities that
# never sat at a first-parent commit of main. Bounded: newest MAIN_HISTORY_DEPTH revisions only.
# Fully qualified: a tag or branch named origin/main must not shadow the remote-tracking ref.
MAIN_REF = 'refs/remotes/origin/main'
MAIN_HISTORY_DEPTH = 1000
_REPO_ROOT = Path(__file__).resolve().parents[2]


def _git(repo, *args):
    return subprocess.run(['git', '-C', str(repo), *args], capture_output=True, text=True,
                          timeout=60, check=True).stdout


@functools.lru_cache(maxsize=None)
def main_policy_blobs(repo=_REPO_ROOT, ref=MAIN_REF, depth=MAIN_HISTORY_DEPTH):
    """Blob ids of POLICY_PATH at the first-parent commits of `ref` that changed it.

    Empty (fail closed to the hand list) when git, the ref or the history is unavailable.
    """
    try:
        commits = _git(repo, 'log', '--first-parent', f'--max-count={int(depth)}', '--format=%H', '--end-of-options',
                       ref, '--', POLICY_PATH).split()
        if not commits:
            return frozenset()
        # One process for all lookups; a deleted path yields 'missing' and is skipped.
        out = subprocess.run(['git', '-C', str(repo), 'cat-file', '--batch-check=%(objectname) %(objecttype)'],
                             input=''.join(f'{c}:{POLICY_PATH}\n' for c in commits),
                             capture_output=True, text=True, timeout=60, check=True).stdout
    except (OSError, subprocess.SubprocessError, ValueError):
        return frozenset()
    return frozenset(line.split()[0] for line in out.splitlines()
                     if line.endswith(' blob') and _SHA.fullmatch(line.split()[0]))


def is_reviewed_policy_blob(blob_sha):
    return blob_sha in REVIEWED_POLICY_BLOBS or blob_sha in main_policy_blobs()


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
            or type(blob.get('size')) is not int or not 0 < blob['size'] <= MAX_POLICY_BLOB_BYTES
            or not isinstance(blob.get('content'), str) or len(blob['content']) > MAX_POLICY_BLOB_BASE64):
        raise ValueError('pre-merge policy blob is invalid')
    try:
        raw = base64.b64decode(''.join(blob['content'].split()), validate=True)
        source = raw.decode('utf-8')
    except (ValueError, UnicodeError) as error:
        raise ValueError('pre-merge policy encoding is invalid') from error
    if len(raw) != blob['size'] or hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest() != blob_sha:
        raise ValueError('pre-merge policy blob digest disagrees')
    if not is_reviewed_policy_blob(blob_sha):
        raise ValueError('unreviewed immutable policy blob; explicit policy-source review required')
    if requires_exact_head(source, (pull.get('head') or {}).get('ref'), expected_context):
        raise ValueError('canonical branch requires exact-head owner authorization')
    return {'policyCommitSha': parent, 'policyBlobSha': blob_sha}
