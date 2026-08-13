#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Guard: every @RolesAllowed role name must exist in a Keycloak realm (issue #2404).
#
# WHY THIS EXISTS
#   `@RolesAllowed` takes a compile-time string, and nothing checked that the string names a role
#   the realm actually issues. A name that exists nowhere does not fail loudly — it just never
#   matches, so the endpoint answers 403 to every caller, forever, and no test, lint or gate says
#   so. openbank-finrep-service shipped `@RolesAllowed("SERVICE", "ADMIN", "OPERATOR")` (the realm
#   issues ROLE_ADMIN / ROLE_OPERATOR / …) and FINREP + COREP were unreachable from the day they
#   were written until #2403.
#
#   The failure is invisible from every angle a normal PR looks at. In particular a unit test
#   cannot catch it: `@TestSecurity(roles = ["SERVICE"])` MINTS whatever string it is handed, so a
#   test written from the annotation matches the annotation and passes against a broken resource.
#   finrep's did exactly that. A test that supplies both sides of the comparison cannot fail —
#   which is why this has to be a gate over the realm, not more tests.
#
# WHAT IT CHECKS
#   HARD (exit 1) — an `@RolesAllowed` whose roles are ALL absent from every realm. That endpoint
#   is unreachable by construction: there is no token any Keycloak in this platform can mint that
#   satisfies it. This is a mechanical fact, not a policy judgement, which is why it blocks.
#
#   HARD (exit 1) — an individual unknown role inside a list that also names a live one
#   (e.g. the old `("ROLE_SERVICE", "ROLE_OPERATOR", "ROLE_ADMIN")`). Humans still get in, so this
#   fails quietly: only the caller the dead name was meant for is denied, forever, with nothing to
#   read. This was ADVISORY at introduction because 163 sites across 29 modules were in that state
#   (ROLE_SERVICE ×152, ROLE_CREDIT_RISK ×9, ROLE_LENDING_OFFICER ×6) and clearing them needed a
#   decision, not a rename: #2442 granted ROLE_API to service-account-openbank-services, created
#   the two lending roles, and swept ROLE_SERVICE -> ROLE_API fleet-wide. The count is 0, so the
#   warning became a hard gate — an advisory over a set that is empty is just a future regression
#   waiting to be merged.
#
# COMMENTS ARE STRIPPED FIRST, and that is load-bearing rather than tidy. #2403 fixed finrep by
# replacing the literals and writing a KDoc that QUOTES the broken annotation to explain what went
# wrong. A scanner that reads raw text flags that comment — the first draft of this script did,
# reporting finrep as still broken after it was fixed. A guard that cannot tell code from prose
# about code manufactures its own findings (same class as the prod-readiness scorer that graded
# services on the word "contract" appearing in a comment, #2291).
#
# Run:  python3 .github/scripts/check-roles-allowed-realm.py [--root .]

import argparse
import json
import pathlib
import re
import sys

import gatelib

REALM_GLOB = "openbank-infra/gitops/components/keycloak/*realm-template*.json"
ROLES_KT = "openbank-libs-domain/src/main/kotlin/com/openbank/libs/security/Roles.kt"
CATALOG_ROLES_KT = "openbank-product-catalog/src/main/kotlin/com/openbank/productcatalog/infrastructure/security/CatalogScopeIdentityAugmentor.kt"

ANNOTATION_RE = re.compile(r"@RolesAllowed\s*\(([^)]*)\)", re.S)
ARG_RE = re.compile(r'"([^"]+)"|(?:(Roles|CatalogRoles)\.(\w+))')
CONST_RE = re.compile(r'const\s+val\s+(\w+)\s*[:=][^"]*"([^"]+)"')

# Kotlin block comments NEST (a `/*` inside a KDoc opens a second level) — mirror that, or a KDoc
# containing one closes early and its tail is scanned as code.
LINE_COMMENT_RE = re.compile(r"//[^\n]*")


def strip_comments(src: str) -> str:
    """Blank out // line comments and (nesting) /* */ blocks, preserving line numbering."""
    out = []
    i, depth, n = 0, 0, len(src)
    while i < n:
        if depth == 0 and src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
            continue
        if src.startswith("/*", i):
            depth += 1
            out.append("  ")
            i += 2
            continue
        if depth and src.startswith("*/", i):
            depth -= 1
            out.append("  ")
            i += 2
            continue
        ch = src[i]
        out.append(ch if (depth == 0 or ch == "\n") else " ")
        i += 1
    return "".join(out)


def realm_roles(root: pathlib.Path, errors):
    names, files = set(), []
    for p in sorted(root.glob(REALM_GLOB)):
        files.append(p.name)
        doc = json.loads(p.read_text())
        roles = doc.get("roles", {})
        names |= {r["name"] for r in roles.get("realm", []) or []}
        for client_roles in (roles.get("client", {}) or {}).values():
            names |= {r["name"] for r in client_roles or []}
    if not names:
        errors.append(f"no realm roles found under {REALM_GLOB} — the guard cannot run blind")
    return names, files


def role_constants(root: pathlib.Path):
    constants = {}
    for qualifier, relative_path in (("Roles", ROLES_KT), ("CatalogRoles", CATALOG_ROLES_KT)):
        source = root / relative_path
        if source.is_file():
            constants.update({f"{qualifier}.{name}": value for name, value in CONST_RE.findall(source.read_text())})
    return constants


def scan(root: pathlib.Path, known, consts):
    """Yield (path, line, all_names, dead_names) for every @RolesAllowed in service main sources."""
    for f in sorted(root.glob("openbank-*/src/main/kotlin/**/*.kt")):
        src = strip_comments(f.read_text())
        for m in ANNOTATION_RE.finditer(src):
            names = []
            for literal, qualifier, const in ARG_RE.findall(m.group(1)):
                key = f"{qualifier}.{const}" if qualifier else ""
                names.append(literal or consts.get(key, key))
            if not names:
                continue
            line = src[: m.start()].count("\n") + 1
            dead = [n for n in names if n not in known]
            yield f.relative_to(root), line, names, dead


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    args = ap.parse_args()
    root = pathlib.Path(args.root).resolve()

    errors = []
    known, realm_files = realm_roles(root, errors)
    consts = role_constants(root)

    unreachable, partial, checked = [], [], 0
    for rel, line, names, dead in scan(root, known, consts):
        checked += 1
        if dead and len(dead) == len(names):
            unreachable.append((rel, line, names))
        elif dead:
            partial.append((rel, line, dead))

    for rel, line, names in unreachable:
        errors.append(
            f"{rel}:{line} — @RolesAllowed({', '.join(names)}) names no role any realm issues, so "
            f"this endpoint answers 403 to every caller. Known roles: {', '.join(sorted(known))}",
        )

    for rel, line, dead in partial:
        errors.append(
            f"{rel}:{line} — @RolesAllowed names {', '.join(dead)}, which no realm issues. The "
            f"endpoint still admits its other roles, so this fails quietly: only the caller the "
            f"dead name was reserved for is denied, and nothing else says so. Grant the role in "
            f"Keycloak or delete it from the annotation.",
        )

    # Before the verdict, so a gate that found its corpus and then failed on it is not also
    # reported as having lost the corpus.
    gatelib.subjects(checked, "@RolesAllowed sites")

    if errors:
        for e in errors:
            sys.stderr.write(f"::error title=RolesAllowed realm parity::{e}\n")
        sys.stderr.write(f"::error::check-roles-allowed-realm: {len(errors)} @RolesAllowed name(s) no realm issues.\n")
        return 1

    print(
        f"roles-allowed parity: {checked} @RolesAllowed site(s) checked against "
        f"{len(known)} role(s) from {', '.join(realm_files)}; every named role is issued by a realm.",
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
