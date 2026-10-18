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
import contextlib
import io
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
# Block style (`  key: v`, `- key: v`) and flow style (`{key: v}`, `{a: b, key: v}`) both count.
_K = r"(?:^[ \t]*-?[ \t]*|[{,][ \t]*)(?:key|path|remoteKey|property|secretKey):[ \t]*"
# A value ends at end of line (optionally with a ` # comment`) or at a flow `,` / `}`.
REF = re.compile(_K + r"[\"']?(?:[\w./-]*/)?creds/([A-Za-z0-9._-]+)[\"']?[ \t]*(?:[,}]|(?:[ \t]#.*)?$)", re.M)
TEMPLATED = re.compile(_K + r"[^\n]*creds/[^\n]*(?:\{\{|\$\{|\$\()", re.M)
# ANY `creds/` in a key position. Whatever this finds that neither REF nor TEMPLATED explains is an
# unparseable shape, and is reported: a shape the parser cannot read must not read as clean.
ANY_CREDS = re.compile(_K + r"[^\n]*creds/[^\n]*", re.M)
ES_KIND = re.compile(r"^kind:[ \t]*ExternalSecret\b", re.M)
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
        opens = re.match(r"(if|case)\b", st) is not None
        # `if ...; then configure_service x ...; fi` opens AND closes on one line: it is
        # conditional, but must leave the depth untouched or every later call is misread.
        one_liner = opens and re.search(r"[;\s](fi|esac)\s*;?\s*$", st) is not None
        if opens and not one_liner:
            depth += 1
        # Only look for the call in the part that runs after `then`; a call inside the `if`
        # CONDITION (or a line starting `if ` with no `then`) declares nothing.
        body = ln
        if st.startswith("if "):
            body = ln.split("then", 1)[1] if "then" in ln else ""
        m = re.search(r"configure_service\s+([a-z][a-z0-9-]*)\s", body)
        if m and not st.startswith("echo "):
            role = f"{m.group(1)}-db-vault-role"
            chained = re.search(r"(&&|\|\|)\s*configure_service\b", ln) is not None
            (cond if depth > 0 or chained or one_liner else always).add(role)
        if re.match(r"(fi|esac)\b", st):
            depth = max(0, depth - 1)
    return always, cond - always


def _strip_comments(text: str) -> str:
    return re.sub(r"(?m)(^|[ \t])#.*$", r"\1", text)


def referenced_roles(root: pathlib.Path) -> tuple[dict[str, list[str]], list[str], list[str]]:
    """(role -> files, files with a templated creds/ path, `file: line` of unparseable shapes)."""
    refs: dict[str, list[str]] = {}
    templated: list[str] = []
    unparsed: list[str] = []
    for p in gatelib.rglob(root / GITOPS, "*.yaml"):
        text = gatelib.read_text(p, errors="replace")
        # Cheap text prefilter: only ExternalSecret manifests can read a dynamic credential.
        if "creds/" not in text or not ES_KIND.search(text):
            continue
        text = _strip_comments(text)
        rel = str(p.relative_to(root))
        for role in REF.findall(text):
            refs.setdefault(role, []).append(rel)
        if TEMPLATED.search(text):
            templated.append(rel)
        for m in ANY_CREDS.finditer(text):
            if not (REF.match(text, m.start()) or TEMPLATED.match(text, m.start())):
                unparsed.append(f"{rel}: {m.group(0).strip()}")
    return refs, templated, unparsed


def check(root: pathlib.Path, known_conditional: dict[str, str] | None = None):
    known_conditional = KNOWN_CONDITIONAL if known_conditional is None else known_conditional
    decl = root / DECL
    if not decl.is_file():
        return None, 0, set()
    always, cond = declared_roles(gatelib.read_text(decl))
    refs, templated, unparsed = referenced_roles(root)
    findings = []
    for role, files in sorted(refs.items()):
        where = ", ".join(sorted(set(files)))
        svc = role.removesuffix("-db-vault-role")
        if role in always:
            continue
        if role in cond:
            if role in known_conditional:
                print(f"::warning::{role} read by {where} is created only under a runtime "
                      f"conditional ({known_conditional[role]}); it may not exist")
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
                 for r in sorted(known_conditional) if r in always]
    findings += [f"{f}: templated creds/ path cannot be verified statically; spell the role out"
                 for f in sorted(templated)]
    findings += [f"{u}: `creds/` value in a key/path position matched no known shape; the gate cannot "
                 "tell which role it names, so spell it as `key: creds/<role>`" for u in sorted(unparsed)]
    return findings, len(refs), always | cond


def _tree(root: pathlib.Path, declared: list[str], referenced: list[str], cond: list[str] = (),
          shape: str = "key: creds/{r}", kind: str = "ExternalSecret") -> None:
    d = root / DECL
    d.parent.mkdir(parents=True)
    lines = [f"    configure_service {s}  c ns db \"x\"" for s in declared]
    for s in cond:
        lines += ['    if [ -n "${X:-}" ]; then', f"      configure_service {s}  c ns db \"x\"", "    fi"]
    d.write_text("\n".join(lines) + "\n")
    (root / GITOPS).mkdir(parents=True, exist_ok=True)
    for i, r in enumerate(referenced):
        (root / GITOPS / f"c{i}.yaml").write_text(
            f"kind: {kind}\ndata:\n  - remoteRef:\n      " + shape.format(r=r) + "\n")


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
    def fresh(*args, **kw):
        t = tempfile.TemporaryDirectory()
        _tree(pathlib.Path(t.name), *args, **kw)
        return t

    def has(findings, *needles):
        return any(all(n in f for n in needles) for f in findings)

    def declared_case(label, text, want_always, want_cond):
        a, c = declared_roles(text)
        if (a, c) != (want_always, want_cond):
            print(f"SELF-TEST FAIL: {label}: got always={sorted(a)} cond={sorted(c)}")
            return False
        return True

    # trailing comment and flow style are references, not silently dropped
    for label, shape in (("trailing comment", "key: creds/{r}  # why"),
                         ("flow style", "{{key: creds/{r}}}"),
                         ("flow style with sibling keys", "{{key: creds/{r}, property: password}}")):
        with fresh(["a"], ["ghost-db-vault-role"], shape=shape) as t:
            bad, n, _ = check(pathlib.Path(t))
            if n != 1 or not has(bad, "ghost-db-vault-role"):
                print(f"SELF-TEST FAIL: {label} reference dropped: n={n} {bad}")
                return 1
        with fresh(["a"], ["a-db-vault-role"], shape=shape) as t:
            bad, n, _ = check(pathlib.Path(t))
            if bad != [] or n != 1:
                print(f"SELF-TEST FAIL: {label} declared reference flagged: n={n} {bad}")
                return 1
    # a creds/ value in a key position that no shape explains must be an error, not a silent drop
    with fresh(["a"], ["a-db-vault-role"], shape="key: creds/{r} trailing-junk") as t:
        bad, _, _ = check(pathlib.Path(t))
        if not has(bad, "matched no known shape"):
            print(f"SELF-TEST FAIL: unparseable creds/ value read as clean: {bad}")
            return 1
    # only ExternalSecret manifests are subjects
    with fresh(["a"], ["ghost-db-vault-role"], kind="ConfigMap") as t:
        bad, n, _ = check(pathlib.Path(t))
        if bad != [] or n != 0:
            print(f"SELF-TEST FAIL: non-ExternalSecret file was scanned: n={n} {bad}")
            return 1
    # stale KNOWN_CONDITIONAL entry: the role is now unconditional -> must fail
    with fresh(["a"], ["a-db-vault-role"]) as t:
        bad, _, _ = check(pathlib.Path(t), {"a-db-vault-role": "#1"})
        if not has(bad, "a-db-vault-role", "drop the entry"):
            print(f"SELF-TEST FAIL: stale KNOWN_CONDITIONAL entry not reported: {bad}")
            return 1
    # a listed conditional role passes WITH the warning
    with fresh(["a"], ["b-db-vault-role"], cond=["b"]) as t:
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            bad, _, _ = check(pathlib.Path(t), {"b-db-vault-role": "#1"})
        if bad != [] or "::warning::b-db-vault-role" not in buf.getvalue():
            print(f"SELF-TEST FAIL: listed conditional role: findings={bad} out={buf.getvalue()!r}")
            return 1
    # conditional detection in declared_roles
    ok = True
    ok &= declared_case("&& chain", "[ -n \"$X\" ] && configure_service a c\n", set(), {"a-db-vault-role"})
    ok &= declared_case("|| chain", "probe || configure_service a c\n", set(), {"a-db-vault-role"})
    ok &= declared_case("one-line if...fi then later call",
                        "if [ x ]; then configure_service a c; fi\nconfigure_service b c\n",
                        {"b-db-vault-role"}, {"a-db-vault-role"})
    ok &= declared_case("one-line case...esac then later call",
                        "case $x in y) configure_service a c;; esac\nconfigure_service b c\n",
                        {"b-db-vault-role"}, {"a-db-vault-role"})
    ok &= declared_case("multi-line if still scopes its body",
                        "if [ x ]; then\n  configure_service a c\nfi\nconfigure_service b c\n",
                        {"b-db-vault-role"}, {"a-db-vault-role"})
    if not ok:
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
