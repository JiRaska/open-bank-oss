#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Detector: does the Keycloak realm that ACTUALLY RUNS hold the roles the realm
# template declares — and only those? (issue #2540)
#
# WHY THIS EXISTS
#   check-roles-allowed-realm.py (#2404, #2418) compares every `@RolesAllowed` string to
#   `openbank-infra/gitops/components/keycloak/*realm-template*.json`. That closes one gap and
#   opens the assumption that the template describes the running realm. Nothing checked that.
#
#   It does not, and it drifts BY CONSTRUCTION. Keycloak reads those JSONs via `--import-realm`,
#   which runs on COLD START ONLY. Every role added to a template after the realm first came up is
#   a file change that reaches nothing. There is no reconciler and no ArgoCD hook, and ArgoCD
#   reports Synced/Healthy throughout — the resource it manages does match the repo. The drift is
#   one layer below anything ArgoCD or a PR-time gate can see.
#
#   Worse, the template in git is not even the artifact Keycloak reads: the `realm-import` volume
#   in keycloak.yaml projects the out-of-band SECRETS `keycloak-realm-import` and
#   `keycloak-customers-realm-import`. So the file the CI gate reads and the file Keycloak imports
#   are two different objects with no link between them. That is a second, independent drift layer,
#   and this detector is deliberately positioned to catch BOTH at once by reading the live realm
#   rather than either file.
#
#   Measured on the sandbox 2026-07-26 (issue #2540): four roles were declared in the template and
#   absent from the live realm — ROLE_KYC, ROLE_KYC_OPENER, ROLE_KYC_REVIEWER, ROLE_SUPERVISOR —
#   and ROLE_DEMO existed live and nowhere in git. Twenty `@RolesAllowed` sites named the four
#   missing roles. None 403'd, because each annotation also listed a live role; the cost was
#   quieter and worse. ADR-0116's KYC four-eyes could not be enforced: ROLE_KYC_OPENER (opens the
#   case) and ROLE_KYC_REVIEWER (approves it, must be a different identity) did not exist, so both
#   halves fell through to ROLE_ADMIN/ROLE_OPERATOR and ONE identity could do both. Separation of
#   duties was declared in the annotations, documented in the ADR, and unenforceable in the
#   running system — with every gate green.
#
# WHY IT IS NOT A PR-TIME GATE
#   The live realm is not reachable from a PR runner: the Keycloak admin REST API is blocked at
#   the nginx edge by design (keycloak.yaml), no workflow holds cluster access, and the admin
#   credential is an out-of-band secret. This script is therefore a pure function over two
#   snapshots — the templates in the repo, and a role list captured from a live realm — so that
#   the CAPTURE can happen in-cluster (the keycloak-realm-drift CronJob) while the COMPARISON is
#   ordinary, reviewable, unit-tested repo code.
#
# WHAT IT REPORTS — both directions, because they are different defects:
#   declared-not-live  a grant that never applied. Every `@RolesAllowed` naming it silently
#                      degrades to whatever else the annotation lists; a four-eyes split
#                      collapses. This is the ADR-0116 failure above.
#   live-not-declared  an undeclared role. It works today and a cold-started cluster loses it —
#                      a disaster-recovery landmine that no test can see, because the only thing
#                      that triggers it is a rebuild.
#   unreachable-live   (highest severity) a role named by an `@RolesAllowed` in the code that the
#                      LIVE realm does not issue. The template agreeing is no comfort: the token
#                      cannot be minted, so that caller is denied and nothing says so.
#
# Keycloak's own built-ins (offline_access, uma_authorization, default-roles-<realm>) are created
# by the server, never appear in a template, and would otherwise be a permanent live-not-declared
# finding — a detector that always fires is a detector nobody reads. They are excluded by name.
#
# Run:
#   python3 .github/scripts/check-realm-role-parity.py --live openbank=/tmp/openbank-roles.json
# where the file is `kcadm.sh get roles -r openbank` output (or a plain JSON array of names).
# A realm with no --live snapshot is reported as unchecked, never as clean.

import argparse
import importlib.util
import json
import pathlib
import sys

REALM_GLOB = "openbank-infra/gitops/components/keycloak/*realm-template*.json"
SIBLING_GATE = ".github/scripts/check-roles-allowed-realm.py"


def keycloak_builtins(realm: str) -> set:
    """Roles the Keycloak server creates itself; never declared in a template.

    `uma_protection` is CLIENT-scoped, unlike the other three: Keycloak adds it to any
    client that enables authorization services, including our own. It only became
    reachable here once the capture started including client roles (the realm-roles-only
    capture reported every client role as declared-not-live — that is what kept
    `mcp-caller` red). Keycloak's own clients — realm-management, account,
    account-console, broker — are excluded at capture time by clientId instead of by
    role name, so that a role we define that happens to share a built-in's name is still
    compared rather than silently skipped.
    """
    return {
        "offline_access",
        "uma_authorization",
        "uma_protection",
        f"default-roles-{realm.lower()}",
    }


def template_roles(root: pathlib.Path) -> dict:
    """{realm name: {role names}} from every *realm-template*.json in the repo."""
    out = {}
    for p in sorted(root.glob(REALM_GLOB)):
        doc = json.loads(p.read_text())
        realm = doc.get("realm")
        if not realm:
            raise SystemExit(f"{p}: no `realm` key — cannot tell which realm it describes")
        roles = doc.get("roles", {}) or {}
        names = {r["name"] for r in (roles.get("realm") or [])}
        for client_roles in (roles.get("client", {}) or {}).values():
            names |= {r["name"] for r in client_roles or []}
        out.setdefault(realm, set())
        out[realm] |= names
    return out


def load_live(spec: str) -> tuple:
    """Parse a `realm=path` snapshot. Accepts kcadm's array-of-objects or an array of strings."""
    if "=" not in spec:
        raise SystemExit(f"--live expects realm=path, got {spec!r}")
    realm, _, path = spec.partition("=")
    raw = sys.stdin.read() if path == "-" else pathlib.Path(path).read_text()
    doc = json.loads(raw)
    if isinstance(doc, dict):  # tolerate {"roles": [...]}
        doc = doc.get("roles", [])
    names = set()
    for entry in doc:
        if isinstance(entry, str):
            names.add(entry)
        elif isinstance(entry, dict) and entry.get("name"):
            names.add(entry["name"])
        else:
            raise SystemExit(f"{path}: unrecognised role entry {entry!r}")
    if not names:
        # An empty snapshot is a capture failure (bad credential, wrong realm), not a realm with
        # no roles — Keycloak always issues at least its own built-ins. Refusing to treat it as
        # data is what stops this reporting "everything is missing" on an auth error.
        raise SystemExit(f"{path}: no roles parsed — treating as a failed capture, not an empty realm")
    return realm, names


def annotation_roles(root: pathlib.Path) -> dict:
    """{role name: [\"path:line\", ...]} for every role any @RolesAllowed names.

    Reuses the sibling gate's scanner rather than re-implementing it: its comment stripping is
    load-bearing (a KDoc that quotes a broken annotation must not be scanned as code) and two
    copies of that logic would drift.
    """
    spec = importlib.util.spec_from_file_location("roles_allowed_gate", root / SIBLING_GATE)
    if spec is None or spec.loader is None:
        return {}
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    consts = mod.role_constants(root)
    sites = {}
    for rel, line, names, _dead, _form in mod.scan(root, set(), consts):
        for n in names:
            sites.setdefault(n, []).append(f"{rel}:{line}")
    return sites


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument(
        "--live",
        action="append",
        default=[],
        metavar="REALM=PATH",
        help="live role snapshot for a realm; PATH may be - for stdin. Repeatable.",
    )
    ap.add_argument(
        "--skip-annotations",
        action="store_true",
        help="skip the @RolesAllowed cross-check (for a snapshot taken outside a repo checkout)",
    )
    args = ap.parse_args()
    root = pathlib.Path(args.root).resolve()

    declared = template_roles(root)
    if not declared:
        sys.stderr.write(f"::error::no realm template found under {REALM_GLOB} — cannot run blind\n")
        return 1
    live = dict(load_live(s) for s in args.live)
    sites = {} if args.skip_annotations else annotation_roles(root)

    findings, report = [], {"realms": {}}
    for realm in sorted(set(declared) | set(live)):
        want = declared.get(realm, set())
        have = live.get(realm)
        if have is None:
            findings.append(
                f"realm `{realm}` has a template in git but no live snapshot was supplied — "
                f"UNCHECKED, not clean. Capture it with `kcadm.sh get roles -r {realm}`.",
            )
            report["realms"][realm] = {"status": "unchecked"}
            continue
        # Subtract the built-ins from BOTH sides. Only the live side is obvious; the customers
        # template also declares `default-roles-openbank-customers` explicitly, so excluding it
        # live-side only turns a server-managed role into a permanent declared-not-live finding.
        # (Caught by check_realm_role_parity_test.py's identical-sets case, which is exactly what
        # a negative case is for.)
        builtins = keycloak_builtins(realm)
        want, have = want - builtins, have - builtins
        if not want:
            findings.append(f"realm `{realm}` is live but no template in git declares it")
        missing = sorted(want - have)
        extra = sorted(have - want)
        # Only the realm the platform authenticates against carries @RolesAllowed names.
        unreachable = sorted(r for r in missing if r in sites) if want else []
        report["realms"][realm] = {
            "status": "checked",
            "declaredNotLive": missing,
            "liveNotDeclared": extra,
            "namedByCodeButNotLive": unreachable,
            "inSync": not missing and not extra,
        }
        for r in missing:
            where = sites.get(r)
            if where:
                findings.append(
                    f"realm `{realm}`: `{r}` is declared in the template and NOT issued by the "
                    f"live realm, and {len(where)} @RolesAllowed site(s) name it "
                    f"({', '.join(where[:3])}{', …' if len(where) > 3 else ''}). No token can "
                    f"carry it: that caller is denied, or the annotation degrades to whatever "
                    f"else it lists. Create the role (`kcadm.sh create roles -r {realm} -s "
                    f"name={r}`) or delete the declaration.",
                )
            else:
                findings.append(
                    f"realm `{realm}`: `{r}` is declared in the template and NOT issued by the "
                    f"live realm. The grant never applied — --import-realm runs on cold start only.",
                )
        for r in extra:
            findings.append(
                f"realm `{realm}`: `{r}` is issued by the live realm and declared nowhere in git. "
                f"It works today and a cold-started cluster would not have it. Add it to the "
                f"template, or delete it from the realm.",
            )

    print(json.dumps(report, indent=2, sort_keys=True))
    if findings:
        for f in findings:
            sys.stderr.write(f"::error title=Keycloak realm parity::{f}\n")
        sys.stderr.write(
            f"::error::check-realm-role-parity: {len(findings)} realm drift finding(s). "
            f"The template and the running realm disagree.\n",
        )
        return 1
    checked = ", ".join(sorted(live))
    print(f"realm role parity: template and live realm agree for {checked}.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
