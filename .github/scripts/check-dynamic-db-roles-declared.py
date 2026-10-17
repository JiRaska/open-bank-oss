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
# Any YAML value of key/path/remoteKey/property naming `creds/<role>`, quoted or not. Templated
# shapes (`creds/{{ .x }}`, `creds/$(..)`) cannot be resolved statically and are reported.
_K = r"^\s*-?\s*(?:key|path|remoteKey|property|secretKey):\s*"
REF = re.compile(_K + r"[\"']?(?:[\w./-]*/)?creds/([A-Za-z0-9._-]+)[\"']?\s*$", re.M)
TEMPLATED = re.compile(_K + r".*creds/.*(?:\{\{|\$\{|\$\()", re.M)
# Roles declared only under a runtime conditional TODAY, owned by an open issue. Such a role
# passes with a warning; an entry that is no longer conditional is reported stale (like
# KNOWN_UNCOVERED in check-pact-provider-replay.py). Remove the entry when the `if` goes.
KNOWN_CONDITIONAL = {"ledger-pension-co-db-vault-role": "#13172"}


def declared_roles(text: str) -> tuple[set[str], set[str]]:
    """(unconditional, conditional). A call inside if/case, or chained with && / ||, may be
    skipped at runtime: the role then does not exist and the ExternalSecret fails `unknown role`."""
    always: set[str] = set()
    cond: set[str] = set()
    depth = 0
    for ln in text.splitlines():
        st = ln.strip()
        if st.startswith("#"):
            continue  # prose mentioning the function cannot declare a role
        if re.match(r"(if|case)\b", st):
            depth += 1
        m = re.search(r"configure_service\s+([a-z][a-z0-9-]*)\s", ln)
        if m and not st.startswith(("if ", "echo ")):
            role = f"{m.group(1)}-db-vault-role"
            chained = re.search(r"(&&|\|\|)\s*configure_service\b", ln) is not None
            (cond if depth > 0 or chained else always).add(role)
        if re.match(r"(fi|esac)\b", st):
            depth = max(0, depth - 1)
    return always, cond - always


def referenced_roles(root: pathlib.Path) -> tuple[dict[str, list[str]], list[str]]:
    refs: dict[str, list[str]] = {}
    templated: list[str] = []
    for p in gatelib.rglob(root / GITOPS, "*.yaml"):
        text = gatelib.read_text(p, errors="replace")
        for role in REF.findall(text):
            refs.setdefault(role, []).append(str(p.relative_to(root)))
        if TEMPLATED.search(text):
            templated.append(str(p.relative_to(root)))
    return refs, templated


def check(root: pathlib.Path):
    decl = root / DECL
    if not decl.is_file():
        return None, 0, set()
    always, cond = declared_roles(gatelib.read_text(decl))
    refs, templated = referenced_roles(root)
    findings = []
    for role, files in sorted(refs.items()):
        where = ", ".join(sorted(set(files)))
        svc = role.removesuffix("-db-vault-role")
        if role in always:
            continue
        if role in cond:
            if role in KNOWN_CONDITIONAL:
                print(f"::warning::{role} read by {where} is created only under a runtime "
                      f"conditional ({KNOWN_CONDITIONAL[role]}); it may not exist")
                continue
            findings.append(
                f"{role} is read by {where} but `configure_service {svc}` in {DECL} sits under a "
                "conditional and may be skipped at runtime (`unknown role`); make it unconditional"
            )
        else:
            findings.append(
                f"{role} is read by {where} but not created by {DECL} (add `configure_service {svc} ...`)"
            )
    findings += [f"{r} is in KNOWN_CONDITIONAL but is now declared unconditionally: drop the entry"
                 for r in sorted(KNOWN_CONDITIONAL) if r in always]
    findings += [f"{f}: templated creds/ path cannot be verified statically; spell the role out"
                 for f in sorted(templated)]
    return findings, len(refs), always | cond


def _tree(root: pathlib.Path, declared: list[str], referenced: list[str], cond: list[str] = (),
          shape: str = "key: creds/{r}") -> None:
    d = root / DECL
    d.parent.mkdir(parents=True)
    lines = [f"    configure_service {s}  c ns db \"x\"" for s in declared]
    for s in cond:
        lines += ['    if [ -n "${X:-}" ]; then', f"      configure_service {s}  c ns db \"x\"", "    fi"]
    d.write_text("\n".join(lines) + "\n")
    (root / GITOPS).mkdir(parents=True, exist_ok=True)
    for i, r in enumerate(referenced):
        (root / GITOPS / f"c{i}.yaml").write_text("data:\n  - remoteRef:\n      " + shape.format(r=r) + "\n")


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
    with tempfile.TemporaryDirectory() as t:
        root = pathlib.Path(t)
        _tree(root, ["a"], ["a-db-vault-role", "b-db-vault-role"], cond=["b"])
        bad, _, _ = check(root)
        if not bad or "conditional" not in bad[0] or "b-db-vault-role" not in bad[0]:
            print(f"SELF-TEST FAIL: conditionally declared role not flagged: {bad}")
            return 1
    with tempfile.TemporaryDirectory() as t:
        root = pathlib.Path(t)
        _tree(root, ["a"], ["ghost-db-vault-role"], shape="path: database/creds/{r}")
        bad, _, _ = check(root)
        if not bad or "ghost-db-vault-role" not in bad[0]:
            print(f"SELF-TEST FAIL: path: reference shape not matched: {bad}")
            return 1
    with tempfile.TemporaryDirectory() as t:
        root = pathlib.Path(t)
        _tree(root, ["a"], ["{{ .Values.r }}"])
        bad, _, _ = check(root)
        if not any("templated" in b for b in bad):
            print(f"SELF-TEST FAIL: templated reference not reported: {bad}")
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
