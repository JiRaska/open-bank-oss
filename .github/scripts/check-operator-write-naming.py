#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Guards the naming convention rest.rego's shared-M2M write prohibition depends on.

GHSA-58jq-9hq3-66jr: `service-account-openbank-services` — the identity nearly every backend
service authenticates as — carries ROLE_OPERATOR in the realm. Every rego rule that grants a
write on `type == "HUMAN"` plus that role therefore admitted ANY backend service to ANY write
in that domain. rest.rego blocks that at the `allow` head for the reasons registered in
`rules.yaml: shared_m2m_write_prohibition.reasons` — an OPT-IN set, because matching every
`operator-*-write` by name would have 403'd transaction.create and settlement.create on
AUTHZ_ENFORCE=true money paths whose services have no identity-scoped fallback.

This script is the discovery half. The register can only ever list rules someone has FOUND, so
its coverage depends on every role-only write rule being findable — and the convention that
makes them findable, `operator-<domain>-write`, was never actually followed: 18 such rules use
other names. Any `allowed_reasons` rule granting on ROLE_OPERATOR/ROLE_ADMIN without pinning
`input.principal.id` must therefore either be named `operator-*-write` (so it is visible as a
candidate for the register) or be a read/declared exception.

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

# Role-only WRITE rules that do not follow the operator-*-write convention, and so are invisible
# as candidates for rules.yaml: shared_m2m_write_prohibition.reasons. A DEBT LIST, not an
# exemption list: every entry was reachable by the shared openbank-services service-account.
# Discovered while fixing GHSA-58jq-9hq3-66jr, whose scope was only the ~19 conforming
# operator-*-write rules.
#
# EMPTY as of #4228 (2026-08-09). It held 18 at its widest, then 5, then 4; the per-entry history
# is kept below because the reasoning is the reusable part, not the names.
#
# Shrinking this set was the work. Adding to it is a regression and the guard says so — and with
# the set empty there is no longer a precedent line to append to, which is the state this list
# was always meant to reach. A future role-only write is a hard failure at PR time, and the
# correct response is one of the three remediation paths #4228 worked through (rename if it is
# not a write; `not startswith(input.principal.id, "service-account-")` if no M2M caller exists;
# an identity-pinned m2m-<action> rule FIRST if one does), never a new baseline entry.
BASELINE: set[str] = set()
# Verified individually, not assembled by hand: derived by emptying this set and reading what
# the check reports. An earlier revision of this list named 18 rules; 10 of those were NOT
# reachable by the shared identity at all — they either bar service-accounts outright
# (`not startswith(input.principal.id, "service-account-")`, as statement-close/-export do),
# gate on a role the shared account does not hold (ROLE_COMPLIANCE / ROLE_CREDIT_RISK /
# ROLE_LENDING_OFFICER), or are reads. Overstating a debt list is not a safe error: it hides
# the real entries among noise and invites the whole thing being ignored.
# 2026-08-07: operator-fx-trigger and operator-fx-approval-decide removed. #3734 gave both
# the `not startswith(input.principal.id, "service-account-")` guard on 2026-08-05
# (fx_rest_ext.rego:50 and :96, recorded in docs/threat-models/openbank-fx-service.md), so
# the debt was paid and only the list lagged. The gate had been reporting both as stale on
# main ever since, in advisory mode, which is why nobody acted.
# 2026-08-08: operator-vop-verify removed (#4228). vop_rest_ext.rego now carries the
# `not startswith(input.principal.id, "service-account-")` guard. It needed no caller audit —
# unlike its siblings here, the legitimate M2M caller already had its OWN identity-pinned
# reason (`m2m-vop-verify`) in the same file, so `opa eval` against vop-opa-bundle.yaml showed
# the shared account resolving BOTH reasons and the role-only one was pure over-grant. The
# extension moved out of the generator heredoc into a real .rego in the same change, which is
# what let opa-policy.yml's file-pair discovery cover it (vop_rest_ext_test.rego).
# 2026-08-09: the last four removed (#4228), and this set is now EMPTY. Each was resolved on
# its own evidence, not as a batch — the whole point of the issue was that "role-only write"
# was three different situations wearing one label:
#   operator-pid-resolve      -> RENAMED operator-pid-resolve-read. Never a write at all:
#                                @GET /api/v1/parties/pid/resolve behind @RolesAllowed(API),
#                                returns {partyId} or 404. The name was the whole defect.
#   operator-party-status     -> exclusion. A real write (@PATCH .../{id}/status) and LIVE
#                                (pid runs AUTHZ_ENFORCE=true), but no M2M caller: the
#                                endpoint is @RolesAllowed(ADMIN) and no service-account holds
#                                ROLE_ADMIN in any of the three realm JSONs here.
#   operator-anacredit-create -> exclusion. A real write, latent (AUTHZ_ENFORCE=false), and
#                                the bundle's own author had already audited "no M2M caller".
#   operator-standing-order-pause -> path 3, NOT an exclusion alone. It has a real M2M caller
#                                (customer-edge self-service pause), so an identity-pinned
#                                m2m-standing-order-pause landed first. The rule's comment had
#                                named the WRONG client — UpstreamClient authenticates as
#                                `openbank-edge`, not the shared `openbank-services`.
#
# An empty set is not a finished job, it is a claim with a ratchet under it: the stale-entry
# check below now has nothing to forgive, so the next role-only write rule anyone writes is a
# hard failure at PR time rather than a line quietly appended here.
# `operator-year-close-attest` was here and is GONE (#3765): the ledger ext rule now carries
# `not startswith(input.principal.id, "service-account-")`, so it is no longer role-only and
# this list must not name it. The removal was surfaced by this script's own stale-entry
# ratchet, not by anyone remembering — which is the argument for keeping that ratchet.
# (end of the retired BASELINE commentary — the set above is deliberately empty)

REASON_RE = re.compile(r'allowed_reasons\s+contains\s+"([^"]+)"\s+if\s*\{')
# Role gating, in every idiom this fleet actually uses. Review of PR #2571 found the first
# version missed three: a membership test whose variable is not literally `role`
# (`some r in {...}; r in input.principal.roles`), a bare role literal with no `some` binding
# (`"ROLE_PAYMENTS" in input.principal.roles`), and a role set sourced from `data.rules`.
# A missed idiom here is a role-only write rule that never gets discovered, which is the one
# failure mode this script exists to prevent.
# Only the roles the SHARED IDENTITY ACTUALLY HOLDS make a rule reachable by it.
#
# CORRECTED 2026-08-09 (#4228): an earlier revision of this comment said openbank-realm.json
# gives `service-account-openbank-services` exactly `["ROLE_OPERATOR"]` — "nothing else". There
# are THREE realm JSONs in this tree and they disagree, so no single-realm statement is true:
#   openbank-infra/docker/keycloak/realm/openbank-realm.json  -> ROLE_OPERATOR, ROLE_API
#   openbank-infra/gitops/components/keycloak/realm-template.json -> ROLE_API   (the DEPLOYED one)
#   .github/workflows/keycloak/openbank-realm.json            -> ROLE_OPERATOR, ROLE_COMPLIANCE
# Reason about exposure from the UNION, which is what the tuple below encodes for the purpose
# this guard serves. ROLE_API and ROLE_COMPLIANCE are deliberately NOT added: this check is about
# the operator-* write families, ROLE_COMPLIANCE rules are covered by compliance-read-any and the
# ROLE_COMPLIANCE oversight reads already listed above, and widening the tuple here changes the
# guard's SCOPE — which needs its own falsification run, not a drive-by edit. ROLE_CREDIT_RISK
# and ROLE_LENDING_OFFICER are held by no service-account in any of the three realms, and
# flagging those was the second over-broad version of this check.
#
# Note what this means for the deployed cluster specifically: the shared account holds only
# ROLE_API there today, so the role-only rules were latent for IT and live for whichever identity
# does hold ROLE_OPERATOR (in the deployed realm, `service-account-openbank-edge`). "Latent in one
# realm" is not "closed" — a realm-template edit is one PR away from making it live everywhere.
#
# Keep this list in step with the realm. If the shared service-account is ever granted another
# role, add it here — otherwise this guard silently stops covering the rules that role opens.
SHARED_IDENTITY_ROLES = ("ROLE_OPERATOR",)

ROLE_GATE_RE = re.compile(
    r"|".join(
        # `some role in {"ROLE_OPERATOR", …}` / `"ROLE_OPERATOR" in input.principal.roles` /
        # any body mentioning the role literal at all — the variable name is irrelevant.
        [rf'"{r}"' for r in SHARED_IDENTITY_ROLES]
        # A role set sourced from rules.yaml could contain it; treat as reachable and let the
        # author narrow it explicitly rather than guessing what the data holds.
        + [r"data\.rules\.[A-Za-z0-9_.]*roles?\b"]
    )
)

# Reads are out of scope regardless of role: this guard is about WRITES. The fleet names them
# `*-read` (viewer-*-read, *-oversight-read, *-telemetry-read), so the convention is the check —
# and unlike the write convention, this one IS followed.
READ_NAME_RE = re.compile(r"-read$")

# Ways a rule can bar or pin the caller identity, so the shared service-account cannot reach it.
# `not startswith(input.principal.id, "service-account-")` is STRONGER than an identity pin — it
# excludes every service-account at once — and the first version of this script did not recognise
# it, which is why it wrongly reported operator-statement-close/-export as exposed.
IDENTITY_PIN_RE = re.compile(
    r"input\.principal\.id\s*=="
    r"|not\s+startswith\(\s*input\.principal\.id\s*,\s*\"service-account-\""
)


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
            if READ_NAME_RE.search(reason):
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
                f"so it is invisible as a candidate for rules.yaml: "
                f"shared_m2m_write_prohibition.reasons (GHSA-58jq-9hq3-66jr) — the shared "
                f"openbank-services service-account, which carries ROLE_OPERATOR, reaches this "
                f"action and nothing will ever propose closing it. Either rename it "
                f"'operator-<domain>-write', pin the caller with input.principal.id, or add it "
                f"to DECLARED_EXCEPTIONS with a reason."
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
