#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Guards the naming convention rest.rego's shared-M2M write prohibition depends on.

GHSA-58jq-9hq3-66jr: `service-account-openbank-services` — the identity nearly every backend
service authenticates as — carries ROLE_OPERATOR in the realm. Every rego rule that grants a
write on `type == "HUMAN"` plus that role therefore admitted ANY backend service to ANY write
in that domain. rest.rego now blocks that at the `allow` head, but it identifies such rules by
their NAME: `operator-<domain>-write`.

That makes the naming convention load-bearing. A role-only write rule named anything else —
`operator-ledger-mutate`, `staff-party-write`, `operator-fx-convert` — is invisible to the
guard and silently reopens the hole. This script is what stops that: any `allowed_reasons`
rule whose body grants on ROLE_OPERATOR/ROLE_ADMIN without pinning `input.principal.id` must
either be named `operator-*-write` (so the prohibition covers it) or be a read/declared
exception.

Scope: rest.rego plus every per-service extension, whether it lives in a standalone
`*_rest_ext.rego` or inside a `gen-*-opa-bundle.sh` heredoc — both shapes exist in this repo,
and a check that only understood one would report a clean run over half the fleet.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]

# Reasons that grant reads only. Role-gated by design and deliberately NOT write-suffixed;
# real M2M callers depend on them (party-service's GDPR Art. 15 aggregation reads kyc-service
# and card-issuance-service with the shared identity via operator-read-any).
READ_REASONS = {
    "operator-read-any",
    "compliance-read-any",
    # Per-service oversight/telemetry reads. Role-gated by design; a read cannot be the
    # write this guard exists to catch.
    "aml-case-oversight-read",
    "auditor-audit-oversight-read",
    "statement-close-run-telemetry-read",
    "viewer-auditor-ledger-read",
    "viewer-balance-read",
}

# Role-gated non-write operator reasons that are NOT domain write families. Each entry needs a
# reason, and adding one is a deliberate act: it means "this role-only rule is exempt from the
# shared-M2M write prohibition", which is precisely the decision GHSA-58jq-9hq3-66jr was about.
DECLARED_EXCEPTIONS = {
    # Tenant-scoped: requires input.resource and matches principal tenant to resource tenant,
    # so it cannot grant a cross-tenant write the way a bare role check can.
    "operator-on-own-tenant": "tenant-matched, requires input.resource",
    # Operator message composition/approval: gated additionally by four_eyes and a closed
    # template catalogue (ADR-0176), not a free-form domain write.
    "operator-compose-message": "four-eyes + closed template catalogue (ADR-0176)",
    "operator-decide-message-approval": "four-eyes second-approver path (ADR-0155)",
}

# The 18 role-only WRITE rules that do not follow the operator-*-write convention, and so are
# NOT covered by rest.rego's prohibition. This is a DEBT LIST, not an exemption list: each of
# these is reachable today by the shared openbank-services service-account. Discovered while
# fixing GHSA-58jq-9hq3-66jr, whose scope was only the ~19 conforming operator-*-write rules.
#
# They are not fixed in the same change deliberately. The complete fix is to invert the
# default for the shared identity — deny everything except an explicit (identity, action)
# allow-list in rules.yaml — and that allow-list cannot be guessed: AUTHZ_ENFORCE is true on
# most of these services, so a missing entry is a production outage on a money path, not a
# test failure. Each entry needs its real callers confirmed first.
#
# Shrinking this set is the work. Adding to it is a regression and the guard says so.
BASELINE = {
    "operator-aml-case-update-decision",
    "dispute-staff-write",
    "operator-kyc-case-update-check",
    "operator-kyc-case-pep-rescreen",
    "operator-kyc-case-review-disposition",
    "operator-standing-order-pause",
    "operator-statement-close-run-trigger",
    "operator-statement-close",
    "operator-statement-export",
    "operator-tpp-registry-blacklist",
    "operator-anacredit-create",
    "supervisor-overdraft-limit",
    "operator-fx-trigger",
    "operator-fx-approval-decide",
    "operator-year-close-attest",
    "operator-vop-verify",
    "operator-pid-resolve",
    "operator-party-status",
}

REASON_RE = re.compile(r'allowed_reasons\s+contains\s+"([^"]+)"\s+if\s*\{')
ROLE_GATE_RE = re.compile(r'\brole\s+in\s+input\.principal\.roles\b|"ROLE_(OPERATOR|ADMIN)"')
IDENTITY_PIN_RE = re.compile(r"input\.principal\.id\s*==")


def rego_sources() -> list[tuple[Path, str]]:
    """Every rego body that can define an allowed_reasons rule, from both authoring shapes."""
    out: list[tuple[Path, str]] = []
    shared = REPO / "openbank-libs/governance/policies/rest.rego"
    if shared.exists():
        out.append((shared, shared.read_text()))
    components = REPO / "openbank-infra/gitops/components"
    for p in sorted(components.glob("*/*_rest_ext.rego")):
        out.append((p, p.read_text()))
    # Heredoc form: `X_REST_EXT=$(cat << 'REGO' ... REGO`. Extract the body so a rule authored
    # inline is checked identically to one in a standalone file.
    for p in sorted(components.glob("*/gen-*-opa-bundle.sh")):
        text = p.read_text()
        for body in re.findall(r"<<\s*'REGO'\n(.*?)\nREGO\n", text, re.DOTALL):
            out.append((p, body))
    return out


def rule_bodies(src: str):
    """Yields (reason, body) for each allowed_reasons rule, body = text up to its closing brace."""
    for m in REASON_RE.finditer(src):
        start = m.end()
        depth = 1
        i = start
        while i < len(src) and depth:
            if src[i] == "{":
                depth += 1
            elif src[i] == "}":
                depth -= 1
            i += 1
        yield m.group(1), src[start : i - 1]


def main() -> int:
    enforce = "--enforce" in sys.argv
    violations: list[str] = []
    baselined: list[str] = []
    checked = 0

    for path, src in rego_sources():
        # Strip comments so prose ABOUT a rule cannot be mistaken for the rule (the
        # code-about-code trap this repo has hit repeatedly).
        clean = re.sub(r"^\s*#.*$", "", src, flags=re.MULTILINE)
        for reason, body in rule_bodies(clean):
            checked += 1
            if reason in READ_REASONS or reason in DECLARED_EXCEPTIONS:
                continue
            if not ROLE_GATE_RE.search(body):
                continue  # not role-gated: identity- or type-scoped, out of scope here
            if IDENTITY_PIN_RE.search(body):
                continue  # names a specific caller — the sanctioned way to grant an M2M write
            if reason.startswith("operator-") and reason.endswith("-write"):
                continue  # covered by rest.rego's shared-M2M write prohibition
            if reason in BASELINE:
                baselined.append(reason)
                continue
            violations.append(
                f"{path.relative_to(REPO)}: rule '{reason}' grants on ROLE_OPERATOR/ROLE_ADMIN "
                f"without pinning input.principal.id, and is not named 'operator-*-write'. "
                f"rest.rego's shared-M2M write prohibition (GHSA-58jq-9hq3-66jr) identifies "
                f"role-only write rules BY NAME, so this rule escapes it — the shared "
                f"openbank-services service-account (which carries ROLE_OPERATOR) would reach "
                f"this action. Either rename it 'operator-<domain>-write', pin the caller with "
                f"input.principal.id, or add it to DECLARED_EXCEPTIONS with a reason."
            )

    # Ratchet in the other direction too: a baseline entry that no longer exists must be
    # deleted, or the list quietly becomes permanent and stops describing reality.
    stale = BASELINE - set(baselined)
    for s_ in sorted(stale):
        print(f"::error::BASELINE names '{s_}', which no longer matches any role-only write "
              f"rule. Delete it from BASELINE — a stale debt list overstates the exposure and "
              f"hides the next real one.")

    for v in violations:
        print(f"::error::{v}")

    if stale or violations:
        print(f"::error::check-operator-write-naming: {len(violations)} new rule(s) escape the "
              f"shared-M2M write prohibition, {len(stale)} stale baseline entr(y/ies).")
        return 1

    level = "error" if enforce else "warning"
    if baselined:
        print(f"::{level}::check-operator-write-naming: {len(baselined)} role-only WRITE rule(s) "
              f"are NOT named operator-*-write and so escape rest.rego's shared-M2M prohibition "
              f"(GHSA-58jq-9hq3-66jr follow-up). They are baselined, not fixed: "
              f"{', '.join(sorted(baselined))}")
    print(f"check-operator-write-naming: {checked} allowed_reasons rule(s) checked, "
          f"{len(baselined)} baselined, 0 new.")
    return 1 if (enforce and baselined) else 0


if __name__ == "__main__":
    sys.exit(main())
