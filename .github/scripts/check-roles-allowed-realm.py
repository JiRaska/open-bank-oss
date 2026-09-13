#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Guard: every @RolesAllowed role name must exist in a Keycloak realm (issue #2404), and
# every literal role probed via hasRole("...") must have an issuer too (issue #8495 — the
# same dead-name defect one layer down, where it reads as a working endpoint with a
# silently unreachable branch).
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
# The same dead-role defect one layer down (#8495): `hasRole("ROLE_X")` is a runtime probe of
# the same string. A role no realm issues makes the branch it guards unreachable forever, and
# worse than the annotation case, it fails CLOSED-looking: the endpoint answers 200 through its
# other caller shapes, so nothing anywhere notices the branch is dead. ROLE_DPO sat in exactly
# that state for the life of the GDPR export endpoints — the DPO branch was dead code and the
# only test mentioning it stubbed the probe to `false`. Literal-argument calls only; a
# `hasRole(Roles.X)` constant is resolved through `consts` the same way the annotation form is.
HASROLE_RE = re.compile(r'hasRole\s*\(\s*(?:"([^"]+)"|(?:(Roles|CatalogRoles)\.(\w+)))\s*\)')
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


AUGMENTOR_MARKERS = ("SecurityIdentityAugmentor", "addRoles")


def augmentor_roles(root: pathlib.Path):
    """Role names a `SecurityIdentityAugmentor` synthesises per request, and so no realm issues.

    A third source of roles exists alongside the realm and the annotation, and this gate could
    not see it: Quarkus lets a `SecurityIdentityAugmentor` add roles to the identity AFTER the
    token is verified. `CatalogScopeIdentityAugmentor` does exactly that — it derives
    CATALOG_SCOPE_READ/AUTHOR/PUBLISH from the token's OAuth `scope` claim, and its KDoc says
    why: *"without trusting a tenant claim or OPA"*. The scope-to-role mapping is meant to be
    the ONLY path to those roles.

    Before this, the only way to satisfy this gate was to declare them as realm roles, which is
    the one thing that must not happen: a realm role is assignable in the admin console, so an
    operator could grant CATALOG_SCOPE_PUBLISH to a user directly and reach every
    `@RolesAllowed(CATALOG_SCOPE_PUBLISH)` endpoint with a token carrying no catalog scope at
    all — the exact bypass the augmentor exists to prevent. And declaring them put the two
    checks in direct conflict: `check-realm-role-parity` (template vs LIVE realm) then reported
    all three as declared-and-never-issued, failing the keycloak-realm-drift CronJob daily,
    because a role that only ever exists at runtime cannot appear in a live realm dump either.
    Both gates were right about their own comparison and both were blind to the same third
    source (2026-08-16).

    DERIVED, not a list. A file counts only if it both implements `SecurityIdentityAugmentor`
    and calls `addRoles`, and only the constants in that file are admitted. Delete the augmentor
    and its roles stop being known here, so the 25 catalog sites go red again — which is the
    correct verdict at that point, since nothing would issue them any more.
    """
    names = set()
    for f in sorted(root.glob("openbank-*/src/main/kotlin/**/*.kt")):
        text = f.read_text()
        if not all(marker in text for marker in AUGMENTOR_MARKERS):
            continue
        names |= {value for _, value in CONST_RE.findall(text)}
    return names


def role_constants(root: pathlib.Path):
    constants = {}
    for qualifier, relative_path in (("Roles", ROLES_KT), ("CatalogRoles", CATALOG_ROLES_KT)):
        source = root / relative_path
        if source.is_file():
            constants.update({f"{qualifier}.{name}": value for name, value in CONST_RE.findall(source.read_text())})
    return constants


def scan(root: pathlib.Path, known, consts):
    """Yield (path, line, all_names, dead_names) for every role reference in service main sources.

    Covers both reference shapes: `@RolesAllowed(...)` annotations and `hasRole(...)` probes
    (the same dead-name defect one layer down, #8495). A hasRole call names exactly one role,
    so for it `names` is always a one-element list. The fifth element is the reference form
    ("annotation" or "probe") so the finding names what was actually read.
    """
    for f in sorted(root.glob("openbank-*/src/main/kotlin/**/*.kt")):
        src = strip_comments(f.read_text())
        for pattern, form in ((ANNOTATION_RE, "annotation"), (HASROLE_RE, "probe")):
            for m in pattern.finditer(src):
                # The annotation captures its whole argument list in group 1; the probe
                # captures either the literal (1) or the qualifier.constant (2, 3).
                argtext = m.group(1) if form == "annotation" else (
                    f'"{m.group(1)}"' if m.group(1) is not None else f"{m.group(2)}.{m.group(3)}"
                )
                names = []
                for literal, qualifier, const in ARG_RE.findall(argtext):
                    key = f"{qualifier}.{const}" if qualifier else ""
                    names.append(literal or consts.get(key, key))
                if not names:
                    continue
                line = src[: m.start()].count("\n") + 1
                dead = [n for n in names if n not in known]
                yield f.relative_to(root), line, names, dead, form


def self_test() -> int:
    """Falsify the annotation reader, the constant resolver and the comment stripper.

    What this prevents: an endpoint annotated with a role the realm has never defined. Nobody
    can hold a role that does not exist, so the endpoint is unreachable by everyone — a 403
    for every caller, which reads as an authorization problem rather than a typo, and cannot
    fail at compile time because the role is a STRING.

    Three ways it goes quiet, all covered below: an annotation form the reader does not
    recognise (silently zero findings for that form), a `Roles.X` constant it cannot resolve
    (compared as the literal "Roles.X", which is in no realm — a false positive that gets the
    gate switched off), and prose in a comment being read as code.
    """
    import tempfile

    fails: list[str] = []

    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)

        realm = root / "openbank-infra/gitops/components/keycloak"
        realm.mkdir(parents=True)
        (realm / "realm-template.json").write_text(json.dumps(
            {"roles": {"realm": [{"name": "ROLE_OPERATOR"}, {"name": "ROLE_API"}]}}))

        kt = root / "openbank-libs-domain/src/main/kotlin/com/openbank/libs/security"
        kt.mkdir(parents=True)
        (kt / "Roles.kt").write_text('object Roles {\n  const val OPERATOR: String = "ROLE_OPERATOR"\n'
                                     '  const val GHOST: String = "ROLE_NEVER_DEFINED"\n}\n')

        catalog_roles = root / CATALOG_ROLES_KT
        catalog_roles.parent.mkdir(parents=True)
        catalog_roles.write_text(
            'object CatalogRoles {\n  const val AUTHOR: String = "ROLE_CATALOG_AUTHOR"\n}\n'
        )

        svc = root / "openbank-x/src/main/kotlin/com/openbank/x"
        svc.mkdir(parents=True)
        (svc / "R.kt").write_text(
            '@RolesAllowed("ROLE_OPERATOR")\nfun a() {}\n\n'                       # known literal
            '@RolesAllowed("ROLE_TYPO")\nfun b() {}\n\n'                           # THE DEFECT
            '@RolesAllowed(Roles.OPERATOR)\nfun c() {}\n\n'                        # constant, known
            '@RolesAllowed(Roles.GHOST)\nfun d() {}\n\n'                           # constant, unknown
            '@RolesAllowed("ROLE_OPERATOR", "ROLE_TYPO2")\nfun e() {}\n\n'         # partial
            '@RolesAllowed(CatalogRoles.AUTHOR)\nfun g() {}\n\n'                  # catalog constant
            '@RolesAllowed("ROLE_DERIVED_AT_RUNTIME")\nfun h() {}\n\n'            # augmentor-issued
            '// @RolesAllowed("ROLE_IN_A_COMMENT")\nfun f() {}\n'                   # prose
            'fun i() { id.hasRole("ROLE_OPERATOR") }\n\n'                        # probe, known
            'fun j() { id.hasRole("ROLE_TYPO3") }\n\n'                           # THE PROBE DEFECT (#8495)
            'fun k() { id.hasRole(Roles.GHOST) }\n\n'                            # probe via constant
            '// id.hasRole("ROLE_PROBE_IN_A_COMMENT")\n'                          # probe prose
        )

        # A real augmentor: implements SecurityIdentityAugmentor AND calls addRoles, so the role
        # it synthesises has an issuer even though no realm declares it. The CatalogRoles fixture
        # above is the NEGATIVE control for the same rule — constants only, neither marker — so
        # ROLE_CATALOG_AUTHOR must stay dead. Without that pair the new branch could admit every
        # constant in the tree and this self-test would still pass.
        aug = root / "openbank-y/src/main/kotlin/com/openbank/y"
        aug.mkdir(parents=True)
        (aug / "ScopeAugmentor.kt").write_text(
            'object DerivedRoles {\n  const val SCOPED: String = "ROLE_DERIVED_AT_RUNTIME"\n}\n'
            'class ScopeAugmentor : SecurityIdentityAugmentor {\n'
            '  override fun augment(i: SecurityIdentity) = builder(i).addRoles(setOf(DerivedRoles.SCOPED)).build()\n'
            '}\n'
        )

        errors: list = []
        known, files = realm_roles(root, errors)
        if known != {"ROLE_OPERATOR", "ROLE_API"}:
            fails.append(f"realm roles wrong: {sorted(known)}")
        derived = augmentor_roles(root)
        if derived != {"ROLE_DERIVED_AT_RUNTIME"}:
            fails.append(f"augmentor-derived roles wrong: {sorted(derived)} "
                         f"(want exactly the constants of files carrying BOTH markers)")
        known |= derived
        consts = role_constants(root)
        expected_consts = {
            "Roles.OPERATOR": "ROLE_OPERATOR",
            "Roles.GHOST": "ROLE_NEVER_DEFINED",
            "CatalogRoles.AUTHOR": "ROLE_CATALOG_AUTHOR",
        }
        if consts != expected_consts:
            fails.append(f"role constants wrong: {consts}")

        found = {str(rel) + ":" + str(line): (names, dead) for rel, line, names, dead, _ in scan(root, known, consts)}
        allnames = [n for names, _ in found.values() for n in names]
        alldead = sorted({d for _, dead in found.values() for d in dead})

        # THE DEFECT, its constant-valued twin, and the probe-shape twin must all be dead.
        for want in ("ROLE_TYPO", "ROLE_NEVER_DEFINED", "ROLE_TYPO2", "ROLE_CATALOG_AUTHOR", "ROLE_TYPO3"):
            if want not in alldead:
                fails.append(f"{want} should be reported as not in the realm; dead={alldead}")
        # Known roles must NOT be — a gate that flags valid roles is one nobody keeps.
        # ROLE_DERIVED_AT_RUNTIME is in no realm at all; it must pass purely on having an
        # augmentor that issues it, which is the whole point of the new branch.
        for notdead in ("ROLE_OPERATOR", "ROLE_DERIVED_AT_RUNTIME"):
            if notdead in alldead:
                fails.append(f"{notdead} has an issuer and must not be reported dead")
        # The CONSTANT must be resolved to its value. Unresolved it compares as "Roles.GHOST",
        # which is in no realm either — right verdict, wrong reason, and it would mask a
        # resolver that had stopped working entirely.
        if "ROLE_NEVER_DEFINED" not in allnames:
            fails.append("Roles.GHOST was not resolved to its literal value")
        if any(n.startswith(("Roles.", "CatalogRoles.")) for n in allnames):
            fails.append(f"a constant was left unresolved: {allnames}")
        # Prose must not be read as code.
        if "ROLE_IN_A_COMMENT" in allnames:
            fails.append("an annotation inside a // comment was read as code")
        if "ROLE_PROBE_IN_A_COMMENT" in allnames:
            fails.append("a hasRole probe inside a // comment was read as code")

    # --- the comment stripper, which is where nesting bites (Kotlin block comments NEST) ---
    if "X" in strip_comments("/* a /* b */ X */"):
        fails.append("nested block comments closed early — text after the inner */ was kept")
    if "KEEP" not in strip_comments("/* gone */ KEEP"):
        fails.append("code after a closed block comment was dropped")
    if strip_comments("a\n// c\nb").count("\n") != 2:
        fails.append("line numbering was not preserved by the stripper")
    # A `*/` at depth ZERO — inside a string literal, say — must be left alone. Without the
    # `depth and` guard it drives the counter NEGATIVE, and since a negative depth is truthy
    # every following character is blanked: the rest of the file silently disappears and the
    # gate reports no annotations at all. The nesting fixture above cannot reach this branch,
    # because there the depth is always positive when a `*/` arrives.
    if "KEEPME" not in strip_comments('val s = "a */ KEEPME"'):
        fails.append("an unbalanced */ at depth 0 blanked the rest of the source — "
                     "the gate would silently see no annotations")

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: @RolesAllowed + hasRole realm parity is falsifiable (20 cases)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()
    root = pathlib.Path(args.root).resolve()

    errors = []
    known, realm_files = realm_roles(root, errors)
    derived = augmentor_roles(root)
    known |= derived
    consts = role_constants(root)

    unreachable, partial, dead_probes, checked = [], [], [], 0
    for rel, line, names, dead, form in scan(root, known, consts):
        checked += 1
        if form == "probe":
            if dead:
                dead_probes.append((rel, line, names[0]))
            continue
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

    for rel, line, role in dead_probes:
        errors.append(
            f"{rel}:{line} — hasRole(\"{role}\") probes a role no realm issues, so the branch it "
            f"guards is unreachable forever while the endpoint answers through its other caller "
            f"shapes — nothing anywhere says so (#8495: ROLE_DPO sat exactly like this on the "
            f"GDPR export endpoints). Define the role in the realm template or delete the branch.",
        )

    # Before the verdict, so a gate that found its corpus and then failed on it is not also
    # reported as having lost the corpus.
    gatelib.subjects(checked, "role-reference sites")

    if errors:
        for e in errors:
            sys.stderr.write(f"::error title=RolesAllowed realm parity::{e}\n")
        sys.stderr.write(f"::error::check-roles-allowed-realm: {len(errors)} role name(s) no realm issues.\n")
        return 1

    print(
        f"roles-allowed parity: {checked} role-reference site(s) (@RolesAllowed + hasRole) checked "
        f"against {len(known)} role(s) — {len(known) - len(derived)} from "
        f"{', '.join(realm_files)} and {len(derived)} synthesised by a SecurityIdentityAugmentor "
        f"({', '.join(sorted(derived)) or 'none'}); every named role has an issuer.",
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
