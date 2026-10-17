#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Every ExternalSecret that reads an OpenBao dynamic DB credential (`creds/<role>` on the
# `vault-db` store) must name a role that the openbao-config CronJob actually creates.
#
# WHY: a role missing from `configure_service` in dynamic-db-credentials.yaml is not an error
# anywhere at review time. The ExternalSecret simply fails every reconcile with `unknown role`
# once deployed (ledger-pension-co, #13172), and the service keeps running on a static secret,
# so nothing pages. The role set is DERIVED from the script, never kept in a second list.
#
# Exit: 0 clean, 1 an undeclared role is referenced, 2 could not run (no corpus).
#
# Run: python3 .github/scripts/check-dynamic-db-roles-declared.py [--root .] [--self-test]

import argparse
import pathlib
import re
import sys
import tempfile

import gatelib

DECL = pathlib.Path("openbank-infra/gitops/components/openbao/dynamic-db-credentials.yaml")
GITOPS = pathlib.Path("openbank-infra/gitops")
# Role convention (ADR-0099): <service>-db-vault-role, created from `configure_service <service> ...`.
CALL = re.compile(r"^\s*configure_service\s+([a-z][a-z0-9-]*)\s", re.M)
REF = re.compile(r"^\s*key:\s*[\"']?(?:database/)?creds/([A-Za-z0-9._-]+)[\"']?\s*$", re.M)


def declared_roles(text: str) -> set[str]:
    # drop comment lines so prose mentioning the function cannot declare a role
    code = "\n".join(ln for ln in text.splitlines() if not ln.lstrip().startswith("#"))
    return {f"{svc}-db-vault-role" for svc in CALL.findall(code)}


def referenced_roles(root: pathlib.Path) -> dict[str, list[str]]:
    refs: dict[str, list[str]] = {}
    for p in gatelib.rglob(root / GITOPS, "*.yaml"):
        for role in REF.findall(gatelib.read_text(p, errors="replace")):
            refs.setdefault(role, []).append(str(p.relative_to(root)))
    return refs


def check(root: pathlib.Path):
    decl = root / DECL
    if not decl.is_file():
        return None, 0, set()
    declared = declared_roles(gatelib.read_text(decl))
    refs = referenced_roles(root)
    findings = [
        f"{role} is read by {', '.join(sorted(set(files)))} but not created by {DECL} "
        f"(add `configure_service {role.removesuffix('-db-vault-role')} ...`)"
        for role, files in sorted(refs.items())
        if role not in declared
    ]
    return findings, len(refs), declared


def _tree(root: pathlib.Path, declared: list[str], referenced: list[str]) -> None:
    d = root / DECL
    d.parent.mkdir(parents=True)
    d.write_text("\n".join(f"    configure_service {s}  c ns db \"x\"" for s in declared) + "\n")
    for i, r in enumerate(referenced):
        (root / GITOPS / f"c{i}.yaml").write_text(f"data:\n  - remoteRef:\n      key: creds/{r}\n")


def self_test() -> int:
    with tempfile.TemporaryDirectory() as t:
        root = pathlib.Path(t)
        _tree(root, ["a", "b"], ["a-db-vault-role", "b-db-vault-role"])
        ok, n, _ = check(root)
        if ok != [] or n != 2:
            print(f"SELF-TEST FAIL: known-negative flagged {ok}")
            return 1
    with tempfile.TemporaryDirectory() as t:
        root = pathlib.Path(t)
        _tree(root, ["a"], ["a-db-vault-role", "ghost-db-vault-role"])
        bad, _, _ = check(root)
        if not bad or "ghost-db-vault-role" not in bad[0]:
            print("SELF-TEST FAIL: undeclared role not flagged")
            return 1
    print("self-test OK")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    findings, subjects, declared = check(pathlib.Path(a.root))
    if findings is None:
        print(f"ERROR: {DECL} not found", file=sys.stderr)
        return 2
    gatelib.subjects(subjects, "dynamic-db roles referenced by ExternalSecrets")
    if findings:
        for f in findings:
            print(f"::error::{f}")
        return 1
    print(f"OK: {subjects} referenced role(s), all among {len(declared)} declared.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
