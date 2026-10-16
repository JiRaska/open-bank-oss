#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""A WRITE action may not join `authz.role_action_matrix` under a role an M2M
service-account holds without being declared first.

WHY THIS EXISTS (#3765, #3734)

`rest.rego`'s `matrix-allows` reason turns every entry of
`rules.yaml: authz.role_action_matrix[<role>].grant` into a permit for any HUMAN principal
carrying `<role>`. `ROLE_OPERATOR` reads as "a member of staff" and is not only that: the
Keycloak service-accounts the platform's own services authenticate as are classified HUMAN
by `AuthorizeInterceptor` (a client_credentials JWT never yields `principal.type ==
"SERVICE"` — `rules.yaml: authz_policy`), and the realm grants at least one of them
`ROLE_OPERATOR`. So a line added to that grant list is a grant to a machine identity, and
nothing in the diff says so: the action name, the block it joins and the role it joins
under all read as staff authorization.

The failure is silent in the direction that matters. `openbank-libs/governance/policies/
rest.rego` carries a `prohibited` veto for the shared identity, but it is keyed by REASON
NAME (`shared_m2m_write_prohibition.reasons`) and `matrix-allows` is a base-layer reason
that can never be listed there — listing it would veto every legitimate matrix-granted
call. No per-service `*_rest_ext.rego` can veto it either: `matrix-allows` lives in base
`rest.rego` and consults no per-service exclusion. There is therefore no policy-layer
mechanism that can catch a bad matrix write grant, which is why this is a build gate.

Measured on the live bundle ConfigMaps with `opa eval` (#3765): on AUTHZ_ENFORCE=true
services, `service-account-openbank-services` presenting ROLE_OPERATOR reaches 128 write
actions and `service-account-openbank-edge` reaches 133; `matrix-allows` is a contributing
reason on roughly 40 of them.

WHAT IT ENFORCES

Every WRITE action granted to a role that any M2M service-account holds must appear in
`rules.yaml: shared_m2m_matrix_write_grants.declared`. That register is not an exemption
list and does not make anything safe — it makes the grant an explicit, reviewable act
instead of a line in an alphabetised list. Adding an entry is the decision; this gate only
insists the decision be taken.

The ratchet runs both ways: a declared action that is no longer granted must be removed, or
the register stops describing the matrix and quietly becomes permanent.

TWO THINGS DERIVED, NEVER LISTED

  1. The ROLES. Read out of every Keycloak realm JSON in the tree, for every user whose
     `username` starts with `service-account-`. A hand-kept role list is the shape this repo
     has been burnt by (`pact-drift-check.yml`, #2284): the realm changes, the list does
     not, and the gate silently stops covering the role that was added. The three realms in
     this tree do NOT agree today — the deployed gitops template gives
     `service-account-openbank-services` only ROLE_API while the docker and CI realms also
     give it ROLE_OPERATOR — so taking the UNION is the only choice that is not a guess
     about which environment matters.
  2. INHERITANCE. `matrix_grants` in rest.rego resolves one level of `inherits`, so a role
     held by a service-account also reaches its parent's grant list. Mirrored here.

WRITE-vs-READ is the one heuristic: an action whose last dot-segment is list/read/readonly.
It is the same split the matrix's own comment uses, and it errs toward calling things
writes (`account.search` is classified a write and is declared as such) — over-covering is
safe here, under-covering is the failure this gate exists to prevent.

Usage:  check-matrix-write-grants.py [--enforce] [--self-test]
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parents[2]
RULES = REPO / "openbank-libs" / "governance" / "rules.yaml"
REGISTER_KEY = "shared_m2m_matrix_write_grants"
REST_REGO = REPO / "openbank-libs" / "governance" / "policies" / "rest.rego"
MATRIX_RULE = re.compile(r'allowed_reasons contains "matrix-allows" if \{(.*?)\n\}', re.S)
SA_EXCLUSION = 'not principal_is_machine'

# The last dot-segment of a read action. Everything else is treated as a write.
READ_VERBS = {"list", "read", "readonly"}


def realm_files(repo: Path) -> list[Path]:
    """Every Keycloak realm JSON in the tree — discovered, so a new realm is covered."""
    out: list[Path] = []
    for p in sorted(repo.rglob("*realm*.json")):
        if "node_modules" in p.parts or "/build/" in str(p):
            continue
        try:
            doc = json.loads(p.read_text())
        except Exception:
            continue
        if isinstance(doc, dict) and "users" in doc and "realm" in doc:
            out.append(p)
    return out


def service_account_roles(repo: Path) -> dict[str, set[str]]:
    """service-account username -> union of realmRoles across every realm file."""
    acc: dict[str, set[str]] = {}
    for p in realm_files(repo):
        doc = json.loads(p.read_text())
        for u in doc.get("users") or []:
            name = u.get("username", "")
            if not name.startswith("service-account-"):
                continue
            acc.setdefault(name, set()).update(u.get("realmRoles") or [])
    return acc


def effective_grants(matrix: dict, roles: set[str]) -> set[str]:
    """Actions matrix-allows admits for a principal holding `roles`, mirroring rest.rego's
    matrix_grants: own grant list plus ONE level of `inherits`."""
    out: set[str] = set()
    for role in roles:
        entry = matrix.get(role)
        if not isinstance(entry, dict):
            continue
        out.update(entry.get("grant") or [])
        parent = matrix.get(entry.get("inherits"))
        if isinstance(parent, dict):
            out.update(parent.get("grant") or [])
    return out


def matrix_allows_excludes_service_accounts(rego: str) -> bool:
    """True when rest.rego's matrix-allows rule carries the service-account exclusion (#3765).

    While it does, matrix-allows grants no M2M identity anything, so no write is M2M-reachable
    through the matrix and the register must be empty. The rule must exist: a rename that makes
    it unfindable is an error, not a silent 'not excluded'.
    """
    m = MATRIX_RULE.search(rego)
    if not m:
        raise SystemExit("::error::no `matrix-allows` rule found in rest.rego — the check cannot "
                         "tell whether machines are excluded. Fix the pattern, never pass blind.")
    return SA_EXCLUSION in m.group(1)


def is_write(action: str) -> bool:
    return action.rsplit(".", 1)[-1] not in READ_VERBS


def evaluate(rules: dict, roles_by_account: dict[str, set[str]],
             sa_excluded: bool = False) -> tuple[list[str], list[str], set[str]]:
    """Returns (undeclared, stale, m2m_roles). With sa_excluded, nothing is M2M-reachable."""
    matrix = ((rules.get("authz") or {}).get("role_action_matrix") or {})
    m2m_roles: set[str] = set()
    for r in roles_by_account.values():
        m2m_roles |= r
    reachable_writes = set() if sa_excluded else {
        a for a in effective_grants(matrix, m2m_roles) if is_write(a)}
    declared = set((rules.get(REGISTER_KEY) or {}).get("declared") or [])
    return sorted(reachable_writes - declared), sorted(declared - reachable_writes), m2m_roles


def run(rules: dict, roles_by_account: dict[str, set[str]], enforce: bool, sa_excluded: bool = False) -> int:
    undeclared, stale, m2m_roles = evaluate(rules, roles_by_account, sa_excluded)
    level = "error" if enforce else "warning"

    for a in undeclared:
        print(
            f"::{level}::'{a}' is a WRITE action granted in "
            f"rules.yaml: authz.role_action_matrix under a role an M2M service-account holds "
            f"({', '.join(sorted(m2m_roles))}), but is not listed in "
            f"{REGISTER_KEY}.declared. rest.rego's matrix-allows turns that grant into a "
            f"permit for every caller presenting that service-account's token — not for a "
            f"named human — and no policy-layer veto can reach it (#3765). Add it to the "
            f"register with the decision, or grant the action from an identity-scoped rule "
            f"that names the caller instead."
        )
    for a in stale:
        print(
            f"::{level}::{REGISTER_KEY}.declared names '{a}', which is no longer an "
            f"M2M-reachable write grant"
            + (" (rest.rego's matrix-allows excludes service-account principals, so the matrix "
               "reaches no machine at all)" if sa_excluded else " in authz.role_action_matrix")
            + ". Delete it — a stale register overstates the exposure and hides the next real entry."
        )

    if not roles_by_account:
        print("::error::no service-account users found in any realm JSON — the discovery is "
              "wrong, not the realm. Refusing to pass a check that covered nothing.")
        return 1

    print(f"check-matrix-write-grants: {len(roles_by_account)} service-account(s), roles "
          f"{sorted(m2m_roles)}; matrix-allows "
          f"{'EXCLUDES' if sa_excluded else 'admits'} service accounts; "
          f"{len(undeclared)} undeclared, {len(stale)} stale.")
    return 1 if (enforce and (undeclared or stale)) else 0


def self_test() -> int:
    """Prove the check can FAIL. A gate that has only ever passed is unfalsified."""
    base_roles = {"service-account-x": {"ROLE_OPERATOR"}}
    base_rules = {
        "authz": {"role_action_matrix": {
            "ROLE_OPERATOR": {"grant": ["ledger.read", "ledger.create"]},
            "ROLE_ADMIN": {"inherits": "ROLE_OPERATOR", "grant": []},
        }},
        REGISTER_KEY: {"declared": ["ledger.create"]},
    }
    ok = True

    u, s, _ = evaluate(base_rules, base_roles)
    if u or s:
        print(f"::error::self-test: clean case reported {u=} {s=}"); ok = False

    # (1) a new write grant appears -> undeclared
    r = json.loads(json.dumps(base_rules))
    r["authz"]["role_action_matrix"]["ROLE_OPERATOR"]["grant"].append("sdd.authorise")
    u, s, _ = evaluate(r, base_roles)
    if u != ["sdd.authorise"]:
        print(f"::error::self-test: a new write grant was not flagged ({u})"); ok = False

    # (2) a READ grant must NOT be flagged
    r = json.loads(json.dumps(base_rules))
    r["authz"]["role_action_matrix"]["ROLE_OPERATOR"]["grant"].append("sdd.read")
    u, _, _ = evaluate(r, base_roles)
    if u:
        print(f"::error::self-test: a read grant was flagged ({u})"); ok = False

    # (3) inheritance: the write hides in the PARENT's list, the account holds the child
    r = json.loads(json.dumps(base_rules))
    r["authz"]["role_action_matrix"]["ROLE_ADMIN"]["grant"] = ["ledger.read"]
    r["authz"]["role_action_matrix"]["ROLE_OPERATOR"]["inherits"] = "ROLE_ADMIN"
    r["authz"]["role_action_matrix"]["ROLE_ADMIN"]["grant"].append("ledger.wipe")
    u, _, _ = evaluate(r, base_roles)
    if "ledger.wipe" not in u:
        print(f"::error::self-test: an inherited write grant was not flagged ({u})"); ok = False

    # (4) a role NO service-account holds is out of scope
    r = json.loads(json.dumps(base_rules))
    r["authz"]["role_action_matrix"]["ROLE_COMPLIANCE"] = {"grant": ["aml.decide"]}
    u, _, _ = evaluate(r, base_roles)
    if u:
        print(f"::error::self-test: a grant on an unheld role was flagged ({u})"); ok = False

    # (5) the same grant becomes in-scope the moment a realm hands that role to an M2M account
    u, _, _ = evaluate(r, {"service-account-x": {"ROLE_OPERATOR", "ROLE_COMPLIANCE"}})
    if u != ["aml.decide"]:
        print(f"::error::self-test: a newly-held role did not widen the scope ({u})"); ok = False

    # (6) stale register entry
    r = json.loads(json.dumps(base_rules))
    r[REGISTER_KEY]["declared"].append("ledger.gone")
    _, s, _ = evaluate(r, base_roles)
    if s != ["ledger.gone"]:
        print(f"::error::self-test: a stale declaration was not flagged ({s})"); ok = False

    # (7) #3765: with the exclusion in rest.rego nothing is reachable, and every declaration is stale
    u, s, _ = evaluate(base_rules, base_roles, sa_excluded=True)
    if u or s != ["ledger.create"]:
        print(f"::error::self-test: exclusion did not empty the reachable set ({u=} {s=})"); ok = False

    # (8) the exclusion is read from the matrix-allows rule itself, not from anywhere in the file
    with_ex = ('allowed_reasons contains "matrix-allows" if {\n\tinput.principal.type == "HUMAN"\n'
               '\tnot principal_is_machine\n\tmatrix_grants(a, r)\n}\n')
    without = with_ex.replace('\tnot principal_is_machine\n', "")
    elsewhere = without + ('allowed_reasons contains "other" if {\n'
                           '\tnot principal_is_machine\n}\n')
    if not matrix_allows_excludes_service_accounts(with_ex):
        print("::error::self-test: the exclusion was not detected"); ok = False
    if matrix_allows_excludes_service_accounts(without):
        print("::error::self-test: a missing exclusion read as present"); ok = False
    if matrix_allows_excludes_service_accounts(elsewhere):
        print("::error::self-test: another rule's exclusion was credited to matrix-allows"); ok = False

    print("check-matrix-write-grants --self-test: " + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    rules = yaml.safe_load(RULES.read_text())
    excluded = matrix_allows_excludes_service_accounts(REST_REGO.read_text())
    return run(rules, service_account_roles(REPO), args.enforce, excluded)


if __name__ == "__main__":
    sys.exit(main())
