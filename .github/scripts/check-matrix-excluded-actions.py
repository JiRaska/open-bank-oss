#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""An action whose own allow rule EXCLUDES service-accounts must not be reachable through
`authz.role_action_matrix`, because `matrix-allows` cannot express that exclusion.

WHY THIS EXISTS (#3765 residual, found while declaring ADR-0285 D6)

Several `rest.rego` rules end with an explicit

    not startswith(input.principal.id, "service-account-")

because a client_credentials JWT is classified `HUMAN` by `AuthorizeInterceptor` (a machine
never yields `principal.type == "SERVICE"` — `rules.yaml: authz_policy`), so the principal
TYPE cannot separate staff from the platform's own services. That line is the only thing
that does. It is how ADR-0176 D5 keeps a machine from composing customer messages, and how
ADR-0285 D6 keeps one from publishing the bank's voice.

`matrix-allows` is a second, independent permit path over the same actions:

    allowed_reasons contains "matrix-allows" if {
        input.principal.type == "HUMAN"
        some role in input.principal.roles
        matrix_grants(input.action, role)
    }

It gates on the type and the role and nothing else. So the moment an SA-excluded action also
appears in `role_action_matrix[<role>].grant` for a role a service-account holds, the
exclusion is defeated — not weakened, defeated, because `allow` is a disjunction over
reasons. Nothing in the diff says so: the action name, the block it joins and the role it
joins under all read as staff authorization, and the bespoke rule with its exclusion is
still sitting there looking correct.

There is no policy-layer fix. `shared_m2m_write_prohibition` is keyed by REASON NAME and
`matrix-allows` is a base-layer reason that can never be listed there (listing it would veto
every legitimate matrix-granted call), and a per-service `*_rest_ext.rego` cannot help
because `matrix-allows` lives in base `rest.rego` and consults no per-service exclusion.
Same structural reason `check-matrix-write-grants.py` exists: this has to be a build gate.

MEASURED, with `opa eval` against the derived data in the sidecar's own layout
(`rules-opa-data.yaml` -> `rules/data.yaml`), 2026-09-12:

    service-account-openbank-services + ROLE_OPERATOR -> opsmessage.compose
        allowed_reasons = ["matrix-allows"]              <- allowed, exclusion defeated
    the same principal            -> opsmessage.approval.decide
        allowed_reasons = ["matrix-allows"]              <- allowed, exclusion defeated
    a HUMAN operator (bob)        -> opsmessage.compose
        allowed_reasons = ["matrix-allows", "operator-compose-message"]

The third line is the control that makes the first two readable: the bespoke rule fires for
a person and not for the machine, which is exactly what it was written to do — and the
matrix carries the machine anyway. The `rules.yaml` comment above that action asserted this
could not happen ("its own action namespace specifically so this list-membership can never
accidentally cover an M2M-reachable action"); that reasoning holds for the `notification.*`
PREFIX match in `edge-service-notification` and does not extend to `matrix-allows`.

Which realms actually grant that service-account `ROLE_OPERATOR` decides whether this is
live or latent, and they disagree — CI grants it `[ROLE_OPERATOR, ROLE_COMPLIANCE]`,
docker-dev `[ROLE_OPERATOR, ROLE_API]`, the deployed gitops template only `[ROLE_API]`. So
today it is live in dev/CI and one realm grant away from live in the deployed environment.
This gate deliberately does NOT read the realms: an invariant that holds only because of
today's role mapping is one realm edit from silently breaking, and the point is to make the
GRANT the reviewable act rather than the mapping.

WHAT IT ENFORCES

  * The subject is DERIVED from `rest.rego`, never listed here: every `allowed_reasons`
    rule carrying a real negative service-account exclusion contributes the actions it
    matches (both `input.action == "x"` and `input.action in {"a", "b"}` forms).
  * None of those actions may appear in `authz.role_action_matrix[<role>].grant`, directly
    or through the single `inherits` hop `matrix_grants` honours.
  * Today's two known violations are baselined in
    `rules.yaml: matrix_sa_excluded_action_grants.declared`. That register is not an
    exemption that makes anything safe — it makes an existing exposure explicit and
    reviewable, and a new one fail.
  * The ratchet runs BOTH ways: a declared entry that no longer violates must be removed,
    or the register stops describing the matrix and quietly becomes permanent.

TWO TRAPS THIS SCRIPT WAS WRITTEN AROUND, both of which produced a wrong answer first

  1. `startswith(...)` is not `not startswith(...)`. A substring search for
     `startswith(input.principal.id, "service-account-")` also matches
     `m2m-sanctions-screening`, which REQUIRES a service-account (it is the M2M screening
     path). The first run of this analysis reported `sanctions.create` as a violation on
     that basis — a false security finding about a rule doing the opposite of what was
     claimed. The exclusion must be matched as a negated term on its own line.
  2. An action set written as `input.action in {"a", "b"}` is invisible to an
     `input.action == "..."` probe. No SA-excluded rule uses that form today, and two other
     rules in the same file do, so a parser that only handles `==` would silently
     under-report the day one is added — the failure mode where a gate reports clean about
     work it never did.

Run:
  python3 .github/scripts/check-matrix-excluded-actions.py [--enforce] [--self-test]
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

import yaml

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib  # noqa: E402

EXCLUSION = re.compile(r'^not\s+startswith\(\s*input\.principal\.id\s*,\s*"service-account-"\s*\)\s*$')
RULE_HEAD = re.compile(r'\nallowed_reasons contains\s+"([^"]+)"\s+if\s*\{')


def derive_excluded_actions(rego: str) -> dict:
    """{action: [rule names]} for every allowed_reasons rule with a negative SA exclusion."""
    out: dict = {}
    for m in RULE_HEAD.finditer(rego):
        name = m.group(1)
        body = rego[m.end():]
        end = body.find("\n}")
        body = body[:end] if end != -1 else body
        lines = [ln.strip() for ln in body.splitlines()]
        if not any(EXCLUSION.match(ln) for ln in lines):
            continue
        actions = set(re.findall(r'input\.action\s*==\s*"([^"]+)"', body))
        for setexpr in re.findall(r'input\.action\s+in\s*\{([^}]*)\}', body):
            actions |= set(re.findall(r'"([^"]+)"', setexpr))
        for a in actions:
            out.setdefault(a, []).append(name)
    return out


def matrix_reach(matrix: dict) -> dict:
    """{action: [roles]} — direct grants plus the ONE inherits hop matrix_grants honours."""
    direct: dict = {}
    for role, spec in (matrix or {}).items():
        for a in ((spec or {}).get("grant") or []):
            direct.setdefault(a, set()).add(role)
    for role, spec in (matrix or {}).items():
        parent = (spec or {}).get("inherits")
        if not parent:
            continue
        for a in ((matrix.get(parent) or {}).get("grant") or []):
            direct.setdefault(a, set()).add(role)
    return {a: sorted(r) for a, r in direct.items()}


def analyse(rego: str, rules: dict) -> tuple:
    excluded = derive_excluded_actions(rego)
    reach = matrix_reach((rules.get("authz") or {}).get("role_action_matrix") or {})
    declared = {
        d["action"]: d
        for d in ((rules.get("matrix_sa_excluded_action_grants") or {}).get("declared") or [])
        if isinstance(d, dict) and d.get("action")
    }
    violations, baselined = {}, {}
    for action, rule_names in sorted(excluded.items()):
        roles = reach.get(action)
        if not roles:
            continue
        (baselined if action in declared else violations)[action] = (roles, rule_names)
    stale = sorted(a for a in declared if a not in baselined)
    return violations, baselined, stale, excluded


def report(violations: dict, baselined: dict, stale: list, excluded: dict) -> None:
    gatelib.subjects(len(excluded), "action(s) carrying a service-account exclusion in rest.rego")
    for action, (roles, rule_names) in violations.items():
        print(
            f"::error title=Matrix defeats a service-account exclusion::`{action}` is granted by "
            f"role_action_matrix to {', '.join(roles)}, while `{', '.join(rule_names)}` excludes "
            f"service-account principals from it. `matrix-allows` gates on principal.type == "
            f'"HUMAN" plus the role only, and a client_credentials JWT is classified HUMAN — so any '
            f"service-account holding one of those roles reaches `{action}` and the exclusion is "
            f"defeated. Remove the matrix grant, or declare it in "
            f"rules.yaml: matrix_sa_excluded_action_grants.declared with a reason."
        )
    for action in stale:
        print(
            f"::error title=Stale declaration::`{action}` is declared in "
            f"matrix_sa_excluded_action_grants but the matrix no longer grants it. Remove the "
            f"declaration — a register that outlives what it describes stops being reviewable."
        )
    for action, (roles, _) in baselined.items():
        print(f"::notice::baselined: `{action}` granted to {', '.join(roles)} (declared exposure)")


def run(root: pathlib.Path, enforce: bool) -> int:
    rego = (root / "openbank-libs/governance/policies/rest.rego").read_text()
    rules = yaml.safe_load((root / "openbank-libs/governance/rules.yaml").read_text())
    violations, baselined, stale, excluded = analyse(rego, rules)
    if not excluded:
        print(
            "::error::no service-account exclusion found anywhere in rest.rego — the derivation "
            "is broken, not the policy. A subject of zero would make this gate vacuously green."
        )
        return 1
    report(violations, baselined, stale, excluded)
    print(f"FAIL: {len(violations)} violation(s), {len(stale)} stale declaration(s)."
          if (violations or stale) else
          f"PASS: no undeclared matrix grant on an SA-excluded action "
          f"({len(baselined)} declared, {len(excluded)} action(s) in subject).")
    return 1 if (enforce and (violations or stale)) else 0


REGO_CLEAN = """
allowed_reasons contains "commstyle-publish" if {
\tinput.principal.type == "HUMAN"
\tnot startswith(input.principal.id, "service-account-")
\tinput.action == "commstyle.publish"
}

allowed_reasons contains "m2m-sanctions-screening" if {
\tstartswith(input.principal.id, "service-account-")
\tinput.action == "sanctions.create"
}
"""

REGO_SETFORM = """
allowed_reasons contains "ops-pair" if {
\tnot startswith(input.principal.id, "service-account-")
\tinput.action in {"ops.a", "ops.b"}
}
"""


def self_test() -> int:
    ok = True

    def check(label, cond):
        nonlocal ok
        if not cond:
            print(f"::error::self-test: {label}")
            ok = False

    # 1. the exclusion must be read as a NEGATED term — the sanctions trap
    ex = derive_excluded_actions(REGO_CLEAN)
    check("a negated exclusion was not detected", "commstyle.publish" in ex)
    check("a POSITIVE startswith was misread as an exclusion", "sanctions.create" not in ex)

    # 2. the set form must be seen
    exs = derive_excluded_actions(REGO_SETFORM)
    check("the `input.action in {...}` set form was not parsed", exs.keys() == {"ops.a", "ops.b"})

    # 3. known-NEGATIVE: excluded action absent from the matrix -> no finding
    rules_clean = {"authz": {"role_action_matrix": {"ROLE_OPERATOR": {"grant": ["account.read"]}}}}
    v, b, s, _ = analyse(REGO_CLEAN, rules_clean)
    check(f"clean case reported a finding ({v=} {s=})", not v and not s and not b)

    # 4. known-POSITIVE: the same action granted -> MUST be flagged
    rules_bad = {"authz": {"role_action_matrix": {"ROLE_OPERATOR": {"grant": ["commstyle.publish"]}}}}
    v, _, _, _ = analyse(REGO_CLEAN, rules_bad)
    check("a matrix grant on an SA-excluded action was NOT flagged", "commstyle.publish" in v)

    # 5. reached only through `inherits` -> MUST still be flagged
    rules_inherit = {"authz": {"role_action_matrix": {
        "ROLE_OPERATOR": {"grant": ["commstyle.publish"]},
        "ROLE_ADMIN": {"inherits": "ROLE_OPERATOR", "grant": []},
    }}}
    v, _, _, _ = analyse(REGO_CLEAN, rules_inherit)
    check("an inherited grant was not flagged", "ROLE_ADMIN" in v.get("commstyle.publish", ([],))[0])

    # 6. a declared grant is baselined, not a violation
    rules_declared = dict(rules_bad)
    rules_declared["matrix_sa_excluded_action_grants"] = {
        "declared": [{"action": "commstyle.publish", "roles": ["ROLE_OPERATOR"], "reason": "test"}]
    }
    v, b, s, _ = analyse(REGO_CLEAN, rules_declared)
    check("a declared grant was still reported as a violation", not v and "commstyle.publish" in b)

    # 7. a declaration for something no longer granted is STALE
    v, b, s, _ = analyse(REGO_CLEAN, {
        "authz": {"role_action_matrix": {}},
        "matrix_sa_excluded_action_grants": {"declared": [{"action": "commstyle.publish"}]},
    })
    check("a stale declaration was not flagged", s == ["commstyle.publish"])

    print("check-matrix-excluded-actions --self-test: " + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    return run(pathlib.Path(a.root), a.enforce)


if __name__ == "__main__":
    sys.exit(main())
