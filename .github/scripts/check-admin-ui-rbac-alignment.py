#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Guard: an admin-ui route permission may not grant a persona the backing service's
# `@RolesAllowed` refuses (issues #7790, #7783, #7788, #7824).
#
# WHY THIS EXISTS
#   `roles.ts` decides which nav links and routes a console session may reach. It is NOT a
#   security control — the service re-checks every call and OPA sits behind that — so a role
#   listed here that the backend rejects does not leak data. It does something else, and the
#   distinction is the whole point of this gate: it tells an operator they may do something
#   they may not. They click the link they were shown and get a 403.
#
#   That is exactly what one line did. A single `payments:view` permission mapped TWELVE
#   unrelated routes (/payments /product-catalog /standing-orders /sdd /sepa-instant /clearing
#   /fx /swift /interest /pid /fees /lending) to five personas, and the twelve backends do not
#   agree with each other, let alone with the UI. Measured 2026-09-01 against origin/main:
#   ROLE_SUPERVISOR was granted on all twelve routes and admitted by NONE of them;
#   /standing-orders, /pid and /lending each also over-granted ROLE_VIEWER; five routes
#   over-granted ROLE_PAYMENTS. /pid was the sharpest case — its list call goes to
#   `/api/v1/pids`, a path that exists in no Kotlin resource in the fleet, so the read that
#   actually backs the page is PartyResource.search (OPERATOR/ADMIN only).
#
#   Nothing could notice. The two sides are written in different languages, in different
#   trees, reviewed by different people, and the divergence produces no failing test, no log
#   line and no metric — only a 403 in somebody's browser. The pre-existing guard for the
#   neighbouring party permission (`party-rbac-alignment.guard.test.ts`) asserts the roles
#   LINE AS A STRING, which pins the file against itself: it goes red when the line is edited
#   and stays green while the backend moves underneath it. A test that supplies both sides of
#   the comparison cannot fail.
#
# WHAT IT CHECKS
#   HARD (exit 1) — a permission granting a role outside its backends' intersection.
#     The intersection, not the union: where a page's read path fans out to several resources
#     with different sets (LendingResource is the live example — the two `applications/*`
#     calls exclude VIEWER/COMPLIANCE while the two `loans/*` calls admit them, and the page
#     `Promise.all`s them so one 403 blanks the whole page), only the intersection can be
#     shown without lying.
#
#   HARD (exit 1) — a route-gating permission with neither an `@rbac-source` annotation nor an
#     entry in UNPINNED_BASELINE. This is what stops the bucket being reintroduced: merging
#     routes back under one permission does not silently pass, it has to survive the
#     intersection of every backend it just absorbed.
#
#   HARD (exit 1) — a STALE declaration in either direction: a baseline entry for a permission
#     that is now annotated (or no longer gates a route), an `@rbac-source` naming a file or
#     method that does not exist, or a resolved backend set that is empty. A gate whose scope
#     is a hand-kept list reads as PASSING when the list is short, never as unchecked, so the
#     list is only allowed to shrink and every exit from it is verified.
#
#   HARD (exit 1) — fewer than --min-pinned permissions actually pinned. An empty corpus makes
#     every subset assertion vacuously true; this is the known-positive the gate runs on
#     itself.
#
#   ADVISORY — a permission NARROWER than its backend allows. Under-granting hides a page from
#     someone entitled to it: a usability bug, not a lie to the operator, and sometimes
#     deliberate. Reported, never blocking.
#
# COMMENTS ARE STRIPPED from the Kotlin before matching (nesting-aware — Kotlin block comments
# NEST, so a KDoc containing `/*` closes early and its tail is scanned as code). Shared
# rationale with check-roles-allowed-realm.py: a KDoc that QUOTES an annotation to explain a
# past defect is prose about code, and a scanner that cannot tell the two apart manufactures
# its own findings.
#
# Run:  python3 .github/scripts/check-admin-ui-rbac-alignment.py [--root .] [--min-pinned N]
#       python3 .github/scripts/check-admin-ui-rbac-alignment.py --self-test

import argparse
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib

ROLES_TS = "openbank-admin-ui/src/lib/auth/roles.ts"
ROLES_KT = "openbank-libs-domain/src/main/kotlin/com/openbank/libs/security/Roles.kt"
CATALOG_ROLES_KT = ("openbank-product-catalog/src/main/kotlin/com/openbank/productcatalog/"
                    "infrastructure/security/CatalogScopeIdentityAugmentor.kt")

# Permissions that gate a route but are not yet pinned to a backend, each with the reason.
# RATCHET: this may only shrink. An entry that becomes pinned, or stops gating a route, is a
# STALE DECLARATION and fails the gate — a debt entry cannot quietly become permanent.
UNPINNED_BASELINE = {
    "dashboard:view": "aggregates many services; no single backend read path to pin to",
    "system:config": "proxies telemetry (Prometheus/Holmes/k8s), no service @RolesAllowed",
    "system:view": "proxies telemetry (Prometheus/Holmes/k8s), no service @RolesAllowed",
    "catalog:read": "gates /product-studio, whose reads are agent-service /api/agent/* (issue #7824 tail)",
    "templates:view": "document-service template reads not yet traced",
    "delegations:view": "delegation-service class-level gate plus OPA delegation.list; OPA half unpinnable here",
    "feedback:view": "screen-feedback BFF has no upstream service resource",
    "regulatory:view": "finrep/corep render path, not a single resource read",
    "audit:view": "audit reads fan out across services",
    "kyc:view": "kyc-service case endpoints not yet traced",
    "onboarding:view": "onboarding-service reads not yet traced",
    "identity-cases:view": "pid-service VerificationCaseResource not yet traced",
    "parties:create": "party-service POST path, not a read gate",
    "parties:view": "party-service PartyResource; pinned by party-rbac-alignment.guard.test.ts today",
    "transactions:view": "transaction-service reads not yet traced",
    "accounts:create": "account-service POST path, not a read gate",
    "accounts:view": "spans account/ledger/day-end across three services",
    "cards:view": "card-issuance reads not yet traced",
    "sanctions:view": "sanctions-service reads not yet traced",
    "compliance:view": "spans aml/fraud/disputes/consents/customer-360 across five services",
    "campaign:view": "campaign-service audience endpoints not yet traced",
    "campaign:create": "campaign-service POST path, not a read gate",
    "lending:compliance:view": "CompliancePackResource /active and /proposals/pending DISAGREE "
                               "(CREDIT_RISK/LENDING_OFFICER 403 on the second); needs a decision, not a pin",
    "approvals:view": "agent-service ProposalResource not yet traced",
    "notifications:view": "notification-service @RolesAllowed and rest.rego disagree in both directions",
    "agent:view": "agent-service MCP endpoint not yet traced",
    "docs:view": "static docs, no backend",
    "settings:view": "no backend read path",
}

# ---------------------------------------------------------------------------- Kotlin reading

ANNOTATION_RE = re.compile(r"@RolesAllowed\s*\(([^)]*)\)", re.S)
ARG_RE = re.compile(r'"([^"]+)"|(?:(Roles|CatalogRoles)\.(\w+))')
CONST_RE = re.compile(r'const\s+val\s+(\w+)\s*[:=][^"]*"([^"]+)"')
FUN_RE = re.compile(r"\bfun\s+(\w+)\s*\(")
CLASS_RE = re.compile(r"\b(?:class|object|interface)\s+\w+")


def strip_comments(src: str) -> str:
    """Blank // line comments and (NESTING) /* */ blocks, preserving offsets and line numbers."""
    out, i, depth, n = [], 0, 0, len(src)
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


def role_constants(root: pathlib.Path) -> dict:
    constants = {}
    for qualifier, rel in (("Roles", ROLES_KT), ("CatalogRoles", CATALOG_ROLES_KT)):
        src = root / rel
        if src.is_file():
            constants.update({f"{qualifier}.{k}": v for k, v in CONST_RE.findall(src.read_text())})
    return constants


def _resolve(arg_blob: str, consts: dict) -> set:
    names = set()
    for literal, qualifier, const in ARG_RE.findall(arg_blob):
        key = f"{qualifier}.{const}" if qualifier else ""
        names.add(literal or consts.get(key, key))
    return names


def roles_for_method(source: str, method: str, consts: dict):
    """Roles admitted by `method`, honouring the method-level-overrides-class-level rule.

    JAX-RS/Jakarta semantics: a method-level @RolesAllowed REPLACES the class-level one, it does
    not add to it. LendingResource is the live proof — its class says
    LENDING_OFFICER/CREDIT_RISK/COMPLIANCE/ADMIN while every read method overrides, which is the
    only way OPERATOR and VIEWER get in at all. A reader that unions the two would compute a set
    no caller actually has, and would have called the over-grant correct.

    Returns (roles, error). Scanning is done on comment-stripped source.
    """
    funs = [(m.start(), m.group(1)) for m in FUN_RE.finditer(source)]
    target = next((i for i, (_, name) in enumerate(funs) if name == method), None)
    if target is None:
        return set(), f"method '{method}' not found"
    start = funs[target - 1][0] if target > 0 else 0
    end = funs[target][0]
    # The annotation block belongs to this fun if it sits between the previous fun and this one.
    method_level = list(ANNOTATION_RE.finditer(source, start, end))
    if method_level:
        return _resolve(method_level[-1].group(1), consts), None
    # Fall back to the class-level annotation: the last one before the type declaration.
    decl = CLASS_RE.search(source)
    limit = decl.start() if decl else len(source)
    class_level = list(ANNOTATION_RE.finditer(source, 0, limit))
    if class_level:
        return _resolve(class_level[-1].group(1), consts), None
    return set(), f"no @RolesAllowed applies to '{method}' (no method-level and no class-level)"


# ---------------------------------------------------------------------------- roles.ts reading

ROLES_CONST_RE = re.compile(r"^\s*(\w+):\s*\"([^\"]+)\",", re.M)
PERM_RE = re.compile(r"^([ \t]*)\"([\w:-]+)\":\s*\[([^\]]*)\]", re.M)
ROUTE_ENTRY_RE = re.compile(r"\['([\w:-]+)',\s*\[([^\]]*)\]\]", re.S)
SOURCE_RE = re.compile(r"@rbac-source\s+(\S+)#(\w+)")
EXCLUDE_RE = re.compile(r"@rbac-exclude\s+([A-Z_]+)")


def parse_roles_ts(text: str):
    """Return (role_consts, permissions{name:(roles,sources,excludes)}, route_permissions)."""
    block = text.split("export const ROLES", 1)[1].split("} as const", 1)[0]
    role_consts = {f"ROLES.{k}": v for k, v in ROLES_CONST_RE.findall(block)}

    perms = {}
    perm_block = text.split("export const PERMISSIONS", 1)[1].split("\n} as const", 1)[0]
    for m in PERM_RE.finditer(perm_block):
        name = m.group(2)
        roles = {role_consts.get(tok.strip(), tok.strip())
                 for tok in m.group(3).split(",") if tok.strip()}
        # Directives live in the contiguous comment block immediately above the key.
        preceding = perm_block[:m.start()].rstrip("\n").split("\n")
        comment = []
        for line in reversed(preceding):
            if line.strip().startswith("//"):
                comment.append(line)
            else:
                break
        blob = "\n".join(comment)
        perms[name] = (roles, SOURCE_RE.findall(blob), set(EXCLUDE_RE.findall(blob)))

    route_block = text.split("const ROUTE_PREFIXES", 1)[1].split("\n]\n", 1)[0]
    route_perms = {m.group(1) for m in ROUTE_ENTRY_RE.finditer(route_block)}
    return role_consts, perms, route_perms


# ---------------------------------------------------------------------------------- the check

def run(root: pathlib.Path, min_pinned: int, baseline: dict | None = None):
    baseline = UNPINNED_BASELINE if baseline is None else baseline
    errors, notes = [], []
    ts_path = root / ROLES_TS
    if not ts_path.is_file():
        return [f"{ROLES_TS} not found — the guard cannot run blind"], [], 0

    consts = role_constants(root)
    _, perms, route_perms = parse_roles_ts(ts_path.read_text())
    if not perms or not route_perms:
        return ["parsed zero permissions or zero route entries — the reader is broken"], [], 0

    pinned = 0
    for name in sorted(route_perms):
        if name not in perms:
            errors.append(f"{name}: gates a route but is not defined in PERMISSIONS")
            continue
        ui_roles, sources, excludes = perms[name]
        if not sources:
            if name not in baseline:
                errors.append(
                    f"{name}: gates a route with no @rbac-source annotation and no "
                    f"UNPINNED_BASELINE entry — pin it to the backing resource's @RolesAllowed, "
                    f"or declare why it cannot be pinned")
            continue
        if name in baseline:
            errors.append(f"{name}: STALE — now pinned, remove it from UNPINNED_BASELINE")
            continue

        allowed, failed = None, False
        for rel, method in sources:
            kt = root / rel
            if not kt.is_file():
                errors.append(f"{name}: @rbac-source file does not exist: {rel}")
                failed = True
                continue
            roles, err = roles_for_method(strip_comments(kt.read_text()), method, consts)
            if err:
                errors.append(f"{name}: {rel}: {err}")
                failed = True
                continue
            allowed = roles if allowed is None else (allowed & roles)
        if failed or allowed is None:
            continue
        if not allowed:
            errors.append(f"{name}: backends intersect to the EMPTY set — no persona can use "
                          f"this page; the sources are wrong or the pages disagree")
            continue

        pinned += 1
        effective = allowed - excludes
        over = ui_roles - effective
        if over:
            errors.append(
                f"{name}: grants {sorted(over)} which the backend refuses. "
                f"UI={sorted(ui_roles)} backend-intersection={sorted(allowed)} "
                f"excluded={sorted(excludes) or '[]'} -> allowed={sorted(effective)}. "
                f"Sources: {', '.join(f'{r}#{m}' for r, m in sources)}")
        under = effective - ui_roles
        if under:
            notes.append(f"{name}: backend also admits {sorted(under)} — page hidden from a "
                         f"persona entitled to it (advisory)")

    for name in sorted(baseline):
        if name not in route_perms:
            errors.append(f"{name}: STALE — in UNPINNED_BASELINE but gates no route; remove it")

    if pinned < min_pinned:
        errors.append(f"only {pinned} permissions are pinned, expected at least {min_pinned} — "
                      f"a subset assertion over an empty corpus is vacuously true")
    return errors, notes, pinned


# ----------------------------------------------------------------------------------- selftest

def self_test() -> int:
    """Falsify every part that can go quiet: the Kotlin reader, the class/method precedence,
    the intersection, the comment stripper, and both directions of the stale-declaration rule.

    A guard is not proven by what it prints, only by what it prevents — so each case below is
    a fixture the gate MUST reject, paired with a near-identical one it must accept.
    """
    import tempfile
    fails = []

    def build(td, perm_body, kt_body, route_perm="x:view"):
        root = pathlib.Path(td)
        ts = root / ROLES_TS
        ts.parent.mkdir(parents=True, exist_ok=True)
        ts.write_text(
            'export const ROLES = {\n'
            '  ADMIN:      "ROLE_ADMIN",\n'
            '  OPERATOR:   "ROLE_OPERATOR",\n'
            '  VIEWER:     "ROLE_VIEWER",\n'
            '  SUPERVISOR: "ROLE_SUPERVISOR",\n'
            '} as const\n\n'
            'export const PERMISSIONS = {\n' + perm_body + '\n} as const\n\n'
            "const ROUTE_PREFIXES = [\n  ['" + route_perm + "', ['/x']],\n]\n"
        )
        kt = root / "openbank-x-service/src/main/kotlin/X.kt"
        kt.parent.mkdir(parents=True, exist_ok=True)
        kt.write_text(kt_body)
        rk = root / ROLES_KT
        rk.parent.mkdir(parents=True, exist_ok=True)
        rk.write_text('object Roles {\n  const val ADMIN: String = "ROLE_ADMIN"\n'
                      '  const val OPERATOR: String = "ROLE_OPERATOR"\n}\n')
        return root

    SRC = "  // @rbac-source openbank-x-service/src/main/kotlin/X.kt#list\n"
    KT_OK = ('@Path("/x")\nclass X {\n'
             '  @GET\n  @RolesAllowed("ROLE_ADMIN", "ROLE_OPERATOR", "ROLE_VIEWER")\n'
             '  fun list() {}\n}\n')

    cases = [
        # (label, perm_body, kt, must_fail, needle)
        ("aligned permission passes",
         SRC + '  "x:view": [ROLES.ADMIN, ROLES.OPERATOR],', KT_OK, False, None),
        ("THE DEFECT: widened past the backend",
         SRC + '  "x:view": [ROLES.ADMIN, ROLES.SUPERVISOR],', KT_OK, True, "ROLE_SUPERVISOR"),
        ("route permission with no annotation and no baseline entry",
         '  "x:view": [ROLES.ADMIN],', KT_OK, True, "no @rbac-source"),
        ("@rbac-source naming a missing method",
         "  // @rbac-source openbank-x-service/src/main/kotlin/X.kt#nope\n"
         '  "x:view": [ROLES.ADMIN],', KT_OK, True, "not found"),
        ("@rbac-source naming a missing file",
         "  // @rbac-source openbank-x-service/src/main/kotlin/Gone.kt#list\n"
         '  "x:view": [ROLES.ADMIN],', KT_OK, True, "does not exist"),
        ("@rbac-exclude admits a role the UI correctly drops",
         SRC + "  // @rbac-exclude ROLE_VIEWER\n"
         '  "x:view": [ROLES.ADMIN, ROLES.OPERATOR],', KT_OK, False, None),
        ("exclude does NOT let a role back in",
         SRC + "  // @rbac-exclude ROLE_VIEWER\n"
         '  "x:view": [ROLES.ADMIN, ROLES.VIEWER],', KT_OK, True, "ROLE_VIEWER"),
        # Method-level REPLACES class-level. If the reader unioned them, ROLE_SUPERVISOR
        # (class-level only) would be admitted and this case would pass — which is the bug.
        ("method-level annotation replaces class-level, it does not union",
         SRC + '  "x:view": [ROLES.SUPERVISOR],',
         '@Path("/x")\n@RolesAllowed("ROLE_SUPERVISOR")\nclass X {\n'
         '  @GET\n  @RolesAllowed("ROLE_ADMIN")\n  fun list() {}\n}\n', True, "ROLE_SUPERVISOR"),
        ("class-level applies when the method has none",
         SRC + '  "x:view": [ROLES.SUPERVISOR],',
         '@Path("/x")\n@RolesAllowed("ROLE_SUPERVISOR")\nclass X {\n  @GET\n  fun list() {}\n}\n',
         False, None),
        # Two sources -> INTERSECTION. Under a union this passes; under intersection it must not.
        ("two sources intersect rather than union",
         "  // @rbac-source openbank-x-service/src/main/kotlin/X.kt#list\n"
         "  // @rbac-source openbank-x-service/src/main/kotlin/X.kt#other\n"
         '  "x:view": [ROLES.VIEWER],',
         '@Path("/x")\nclass X {\n'
         '  @GET\n  @RolesAllowed("ROLE_ADMIN", "ROLE_VIEWER")\n  fun list() {}\n'
         '  @GET\n  @RolesAllowed("ROLE_ADMIN")\n  fun other() {}\n}\n', True, "ROLE_VIEWER"),
        ("a commented-out annotation is prose, not code",
         SRC + '  "x:view": [ROLES.ADMIN, ROLES.OPERATOR],',
         '@Path("/x")\nclass X {\n'
         '  /* historic: @RolesAllowed("ROLE_SUPERVISOR") /* nested */ was wrong */\n'
         '  @GET\n  @RolesAllowed("ROLE_ADMIN", "ROLE_OPERATOR", "ROLE_VIEWER")\n'
         '  fun list() {}\n}\n', False, None),
        ("Roles.X constants resolve",
         SRC + '  "x:view": [ROLES.ADMIN],',
         '@Path("/x")\nclass X {\n  @GET\n  @RolesAllowed(Roles.ADMIN, Roles.OPERATOR)\n'
         '  fun list() {}\n}\n', False, None),
    ]

    for label, body, kt, must_fail, needle in cases:
        with tempfile.TemporaryDirectory() as td:
            root = build(td, body, kt)
            errs, _, _ = run(root, min_pinned=0, baseline={})
            got = bool(errs)
            if got != must_fail:
                fails.append(f"{label}: expected {'FAIL' if must_fail else 'PASS'}, "
                             f"got {'FAIL' if got else 'PASS'} ({errs})")
            elif must_fail and needle and not any(needle in e for e in errs):
                fails.append(f"{label}: failed for the wrong reason ({errs}), want {needle!r}")

    # Stale-declaration rule, BOTH directions.
    with tempfile.TemporaryDirectory() as td:
        root = build(td, SRC + '  "x:view": [ROLES.ADMIN],', KT_OK)
        errs, _, _ = run(root, min_pinned=0, baseline={"x:view": "test"})
        if not any("STALE" in e and "remove it from UNPINNED" in e for e in errs):
            fails.append(f"annotated permission still in baseline must be STALE, got {errs}")
    with tempfile.TemporaryDirectory() as td:
        root = build(td, SRC + '  "x:view": [ROLES.ADMIN],', KT_OK)
        errs, _, _ = run(root, min_pinned=0, baseline={"gone:view": "test"})
        if not any("gates no route" in e for e in errs):
            fails.append(f"baseline entry gating no route must be STALE, got {errs}")

    # The min-pinned floor is the gate's own known-positive.
    with tempfile.TemporaryDirectory() as td:
        root = build(td, SRC + '  "x:view": [ROLES.ADMIN],', KT_OK)
        errs, _, n = run(root, min_pinned=99, baseline={})
        if n != 1 or not any("vacuously true" in e for e in errs):
            fails.append(f"min-pinned floor did not fire (pinned={n}, errs={errs})")

    for f in fails:
        print(f"SELF-TEST FAIL: {f}")
    print(f"self-test: {len(cases) + 3} cases, {len(fails)} failures")
    return 1 if fails else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--min-pinned", type=int, default=10)
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()

    errors, notes, pinned = run(pathlib.Path(args.root).resolve(), args.min_pinned)
    # The subject count is what makes the green meaningful: a subset assertion over an empty
    # corpus passes. run-gates.py cross-checks this against min_subjects in gates.yaml.
    gatelib.subjects(pinned, "route permissions pinned to backend @RolesAllowed")
    for n in notes:
        print(f"note: {n}")
    if errors:
        print(f"\nadmin-ui RBAC alignment: {len(errors)} error(s)\n")
        for e in errors:
            print(f"  ERROR {e}")
        print("\nroles.ts must not grant a persona the backing service's @RolesAllowed refuses:")
        print("the console would render a link that answers 403 on click.")
        return 1
    print(f"admin-ui RBAC alignment OK — {pinned} route permissions pinned to backend "
          f"@RolesAllowed, {len(UNPINNED_BASELINE)} declared unpinned")
    return 0


if __name__ == "__main__":
    sys.exit(main())
