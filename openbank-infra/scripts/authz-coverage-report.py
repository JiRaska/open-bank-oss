#!/usr/bin/env python3
"""authz coverage report — static @Authorize vs rest.rego analysis (ADR-0034 Phase 5, #266).

Before a service's AUTHZ_ENFORCE flips from advisory to enforce, every action its
@Authorize-annotated endpoints declare must have at least one plausible allow path
in openbank-libs/governance/policies/rest.rego for every legitimate caller type.
Advisory mode hides the gap: the interceptor logs the would-be deny and lets the
request through; enforce turns each uncovered (action, caller) pair into a 403.

This tool statically inventories every @Authorize(action, resource) in the fleet
and classifies each action against the rest.rego reason rules:

    *.read / *.list / *.search    operator-read-any (HUMAN OPERATOR/ADMIN);
                                  compliance-read-any (.read); party-self-service
                                  (resource-scoped read/list where id == JWT sub)
    customer.*                    customer-self-action (any authenticated HUMAN)
    notification.* / device.*     edge-service-notification (customer-edge's
                                  client_credentials identity, HUMAN principal.id ==
                                  "service-account-openbank-edge" — NOT a SERVICE
                                  principal.type; see M2M caveat below)
    other verbs WITH resource     operator-on-own-tenant ONLY (HUMAN OPERATOR whose
                                  tenant matches resource.attributes.tenant)
    other verbs WITHOUT resource  NO allow path — operator-on-own-tenant requires
                                  input.resource; nothing else matches a non-read,
                                  non-customer, non-notification action

M2M caveat (the Phase-5 blocker, resolved for customer-edge, open elsewhere): OPA's
input.principal.type is NEVER "SERVICE" in this fleet — AuthorizeInterceptor.
principalType() only ever emits ANONYMOUS/AI_AGENT/HUMAN (M2M calls carry a Keycloak
client_credentials JWT, which the interceptor classifies as HUMAN), and no Keycloak
client is ever granted ROLE_SERVICE. Any rego rule gated on `principal.type ==
"SERVICE"` is dead code that can never fire. edge-service-notification was fixed to
gate on the edge's verified principal.id instead (see rest.rego); any OTHER
service-to-service caller (e.g. a money-path write endpoint like
transaction-service -> ledger.create) still has NO allow path today and will 403
under enforce unless it is verified against actual caller code and given the same
identity-based treatment. Static analysis cannot see runtime callers — check the
OPA advisory decision logs (AuditEvent decision_reason) before any flip.

Output: a Markdown report (stdout). Not a CI gate — an analysis tool for the
per-service flip checklist on #266.

Usage:  python3 openbank-infra/scripts/authz-coverage-report.py [--money-path-only]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

AUTHORIZE_RE = re.compile(r'@Authorize\(\s*action\s*=\s*"([^"]+)"(?:\s*,\s*resource\s*=\s*"([^"]*)")?')
# rest.rego's read-family allow rules (operator-read-any, party-self-service) match ONLY
# {"list","read"}; compliance-read-any matches only ".read". NO rule matches ".search", so a
# *.search action has no allow path and must NOT be classified covered.
READ_VERBS = ("read", "list")


def money_path_services(root: Path) -> list[str]:
    rules = (root / "openbank-libs/governance/rules.yaml").read_text()
    # Entries may carry a trailing `# ADR-…` comment (fraud/billing do), so an anchored
    # `\S+\n` would silently drop those lines. Match the whole list line, extract the token.
    m = re.search(r"^money_path_services:\n((?:[ \t]+-[ \t]+\S.*\n)+)", rules, re.MULTILINE)
    if not m:
        sys.exit("cannot parse money_path_services from rules.yaml")
    return re.findall(r"-[ \t]+(\S+)", m.group(1))


def classify(action: str, resource: str) -> tuple[str, str]:
    """Return (coverage, note) for one @Authorize declaration."""
    verb = action.rsplit(".", 1)[-1]
    if action.startswith("customer."):
        return "covered", "customer-self-action (any authenticated HUMAN)"
    if action.split(".", 1)[0] in ("notification", "device"):
        return "covered", "edge-service-notification (edge identity) + read rules"
    if verb in READ_VERBS:
        scoped = " + party-self-service (resource-scoped)" if resource else ""
        return "covered", f"operator-read-any / compliance-read-any{scoped}"
    if resource:
        return "partial", (
            "operator-on-own-tenant ONLY — requires HUMAN OPERATOR with matching "
            "resource tenant attribute; NO M2M (SERVICE) path"
        )
    return "uncovered", (
        "NO allow path: non-read action without a resource — operator-on-own-tenant "
        "requires input.resource; no SERVICE rule matches"
    )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--money-path-only", action="store_true")
    args = ap.parse_args()

    root = Path(".")
    mp = set(money_path_services(root))

    inventory: dict[str, list[tuple[str, str, str]]] = {}
    for kt in root.glob("openbank-*/src/main/kotlin/**/*.kt"):
        service = kt.parts[0]
        for m in AUTHORIZE_RE.finditer(kt.read_text(encoding="utf-8", errors="replace")):
            inventory.setdefault(service, []).append((m.group(1), m.group(2) or "", str(kt)))

    print("# @Authorize coverage vs rest.rego (static) — ADR-0034 Phase 5 flip readiness\n")
    print("Caller legend: covered = an allow reason exists for the expected human caller; "
          "partial = human-operator-only path (no M2M); uncovered = no allow path at all.\n")

    totals = {"covered": 0, "partial": 0, "uncovered": 0}
    for service in sorted(inventory):
        if args.money_path_only and service not in mp:
            continue
        rows = inventory[service]
        flag = " (money-path)" if service in mp else ""
        print(f"## {service}{flag}\n")
        print("| action | resource | coverage | allow path |")
        print("|---|---|---|---|")
        seen: set[tuple[str, str]] = set()
        for action, resource, _ in sorted(rows):
            if (action, resource) in seen:
                continue
            seen.add((action, resource))
            cov, note = classify(action, resource)
            totals[cov] += 1
            marker = {"covered": "✅", "partial": "⚠️", "uncovered": "❌"}[cov]
            print(f"| `{action}` | `{resource or '—'}` | {marker} {cov} | {note} |")
        print()

    print(f"**Totals:** {totals['covered']} covered · {totals['partial']} partial · "
          f"{totals['uncovered']} uncovered\n")
    print("> Static approximation only. Before any AUTHZ_ENFORCE flip, confirm against the "
          "OPA advisory decision logs (AuditEvent decision_reason) that no legitimate caller "
          "hits a would-be deny — especially M2M callers, which this analysis cannot see and "
          "which never present principal.type == \"SERVICE\" (see the M2M caveat above).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
