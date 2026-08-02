#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""No duplicate resource inside one gitops manifest, and no repeated env-list entry.

Background. Adding a domain namespace to admin-ui is a documented two-part edit:
append the namespace to `OPENBANK_NAMESPACES`, and add a RoleBinding for
`admin-ui-discovery`. Two agents did it for `delegation` nine minutes apart
(#3450 at 20:17, #3460 at 20:26). Both PRs were green and neither conflicted —
one appended a YAML document at the end of the file, the other appended to a
comma-separated string, so git saw two edits in different places and merged
them. `main` ended up with the namespace listed twice and TWO byte-identical
`RoleBinding/admin-ui-discovery` documents in namespace `delegation`.

Nothing in the tree could see it. A duplicate within a multi-document YAML file
is valid YAML; `yamllint` checks style, not resource identity; kustomize
tolerates it; and the second document simply wins at apply time, so the cluster
looks correct while the manifest no longer says one thing.

The failure mode is not the duplicate itself — it is that the two copies are now
free to drift, and the one that loses is invisible. That is the same shape as a
published doc keeping its own copy of a list (#2280): the second copy IS the
drift.

Two rules, both within a single file:

  1. No two documents share (kind, metadata.name, metadata.namespace). Across
     files this is legitimate — overlays and environments redefine resources on
     purpose — so the scope is deliberately one file at a time.
  2. No repeated element in a comma-separated `value:` belonging to an env var.
     `OPENBANK_NAMESPACES` is the live instance; the rule is general because the
     next such list will not be called that.

Usage:
    check-gitops-duplicate-resources.py             # warn
    check-gitops-duplicate-resources.py --enforce   # fail
    check-gitops-duplicate-resources.py --self-test # prove it can fail
"""

from __future__ import annotations

import argparse
import collections
import pathlib
import sys
import tempfile

import yaml

ROOTS = ("openbank-infra/gitops",)

# A value worth de-duplicating: several comma-separated slugs, nothing else. This
# deliberately does not match a single value, a path, or a URL — a lone comma in
# prose must not arm the check.
SLUG_LIST_MIN = 2


def _is_slug_list(value: str) -> bool:
    if "," not in value:
        return False
    parts = value.split(",")
    if len(parts) < SLUG_LIST_MIN:
        return False
    return all(p and all(c.isalnum() or c in "-_" for c in p) for p in parts)


def _env_lists(node, path=""):
    """Yield (name, value) for every {name, value} mapping whose value is a slug list."""
    if isinstance(node, dict):
        name, value = node.get("name"), node.get("value")
        if isinstance(name, str) and isinstance(value, str) and _is_slug_list(value):
            yield name, value
        for k, v in node.items():
            yield from _env_lists(v, f"{path}.{k}")
    elif isinstance(node, list):
        for i, v in enumerate(node):
            yield from _env_lists(v, f"{path}[{i}]")


def check_file(path: pathlib.Path) -> list[str]:
    problems: list[str] = []
    try:
        docs = list(yaml.safe_load_all(path.read_text(encoding="utf-8")))
    except yaml.YAMLError as exc:
        return [f"{path}: not parseable as YAML ({type(exc).__name__}) — fix that first."]

    identities = []
    for doc in docs:
        if not isinstance(doc, dict):
            continue
        meta = doc.get("metadata") or {}
        if not isinstance(meta, dict):
            continue
        kind, name = doc.get("kind"), meta.get("name")
        if not kind or not name:
            continue
        identities.append((kind, name, meta.get("namespace")))

    for identity, count in collections.Counter(identities).items():
        if count > 1:
            kind, name, ns = identity
            where = f" in namespace {ns}" if ns else " (cluster-scoped)"
            problems.append(
                f"{path}: {count} documents define {kind}/{name}{where}. Only the last one "
                f"applies, so the others are invisible copies free to drift. Keep one."
            )

    for doc in docs:
        for name, value in _env_lists(doc):
            parts = value.split(",")
            repeated = [v for v, c in collections.Counter(parts).items() if c > 1]
            if repeated:
                problems.append(
                    f"{path}: env var {name} lists {', '.join(sorted(repeated))} more than once. "
                    f"Two edits appended the same entry independently."
                )
    return problems


SELF_TEST_DUPLICATE_DOC = """\
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: probe-binding
  namespace: probe-ns
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: probe-binding
  namespace: probe-ns
"""

SELF_TEST_DUPLICATE_ENV = """\
apiVersion: v1
kind: Pod
metadata:
  name: probe-pod
spec:
  containers:
    - name: c
      env:
        - name: PROBE_NAMESPACES
          value: alpha,beta,alpha
"""

SELF_TEST_CLEAN = """\
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: probe-binding
  namespace: probe-ns
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: probe-binding
  namespace: other-ns
---
apiVersion: v1
kind: Pod
metadata:
  name: probe-pod
spec:
  containers:
    - name: c
      env:
        - name: PROBE_NAMESPACES
          value: alpha,beta
        - name: PROBE_SINGLE
          value: alpha
"""


def self_test() -> int:
    """Each case is one the check MUST get right, including the ones it must NOT flag.

    The clean case matters as much as the dirty ones: the same (kind, name) in two
    DIFFERENT namespaces is normal, and so is a non-repeating list.
    """
    cases = [
        ("duplicate document", SELF_TEST_DUPLICATE_DOC, True),
        ("duplicate env-list entry", SELF_TEST_DUPLICATE_ENV, True),
        ("clean: same name, different namespace + non-repeating list", SELF_TEST_CLEAN, False),
    ]
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        for label, content, must_flag in cases:
            p = pathlib.Path(tmp) / "probe.yaml"
            p.write_text(content, encoding="utf-8")
            flagged = bool(check_file(p))
            ok = flagged == must_flag
            print(f"  [{'ok' if ok else 'FAIL'}] {label}: flagged={flagged}, expected={must_flag}")
            if not ok:
                failures += 1
    if failures:
        print(f"\nself-test: {failures} case(s) wrong — the check does not measure what it claims.")
        return 1
    print("\nself-test: OK — flags both duplicate shapes, leaves the legitimate ones alone.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--enforce", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    problems: list[str] = []
    checked = 0
    for root in ROOTS:
        for path in sorted(pathlib.Path(root).rglob("*.yaml")):
            checked += 1
            problems.extend(check_file(path))

    if not problems:
        print(f"check-gitops-duplicate-resources: OK — {checked} manifests, no duplicate resource or list entry")
        return 0

    level = "error" if args.enforce else "warning"
    for p in problems:
        print(f"::{level}::{p}")
    print(f"\n{len(problems)} problem(s) across {checked} manifests.")
    return 1 if args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
