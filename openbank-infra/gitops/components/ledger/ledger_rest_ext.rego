# SPDX-License-Identifier: Apache-2.0
# Ledger REST extension — general-ledger allow reasons (ADR-0034 Phase 5, issue #266).
# ledger-service is the bank's double-entry book of record — the single most sensitive
# write target in the fleet. Every write action below is deny-by-default until an
# explicit reason below (or in base rest.rego) fires; no blanket SERVICE allow.
# Ledger-service REST extension (ADR-0034 Phase 5, issue #266).
# Extends openbank.rest with ledger-domain allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (LedgerResource / YearCloseResource / ControlAccountResource /
# FxRevaluationResource) — all @Authorize action strings verified against the real
# source, not guessed:
#   ledger.list     — list journal entries (LedgerResource)
#   ledger.read     — journal/trial-balance/sub-ledger-balance/control-account reads
#   ledger.create   — POST a balanced journal entry (LedgerResource.postJournal).
#   ledger.close.draft — create/refresh a period or fiscal-year close DRAFT; human-only
#                      because its recorded principal becomes maker evidence.
#   ledger.reverse  — reverse a posted journal entry (#journalId)
#   ledger.approve  — attest a DRAFT year-close (#fiscalYear) — a statutory close,
#                      not a routine posting; kept operator-only, no M2M path.
#   ledger.trigger  — run the daily FX revaluation (ops/backfill only)
#   ledger.replay   — re-emit historical AccountBookedChanged for a window (ops recovery,
#                      #860); posts no journal, re-drives the book of record's own events.
#
# Base rest.rego's operator-read-any / compliance-read-any only cover HUMAN
# ROLE_OPERATOR/ROLE_ADMIN/ROLE_COMPLIANCE on *.read + *.list — but every ledger read
# endpoint's own @RolesAllowed ALSO admits ROLE_VIEWER and ROLE_AUDITOR (financial-control
# evidence access, locked by LedgerSecurityContractTest). Neither role has a base rest.rego
# read rule (VIEWER's only base coverage is party-self-service, which needs a matching
# resource id — not applicable to ledger's non-party-scoped reads; AUDITOR has no base rule
# at all). Enforcing without an explicit grant here would silently 403 every currently-RBAC-
# permitted viewer/auditor read — the domestic-payment OPA-enforce work (ADR-0034 Phase 5,
# issue #266) hit the identical gap for ROLE_VIEWER and named it explicitly; ledger adds
# ROLE_AUDITOR to the same fix since its read roles include auditor too.


# ── #3734 (2026-08-05): service-account exclusion + edge write prohibition ──────────────
# The two operator rules above were role-only, and rules.yaml's role_action_matrix grants
# ledger.create/reverse/trigger/replay to ROLE_OPERATOR. Both realm M2M clients carry that
# role and are HUMAN-classified, so the customer-facing edge identity
# (service-account-openbank-edge) was admitted to the book-of-record's writes — post/reverse
# a journal, re-run an FX revaluation — via base matrix-allows, and to year-close attestation
# via operator-year-close-attest (whose own comment says no SERVICE principal must EVER
# reach it). Fleet caller audit: no ledgerServiceUrl exists anywhere in customer-edge — the
# edge has NO ledger caller at all; the legitimate M2M writers (transaction/lending/
# settlement via the shared client) keep their identity-scoped service-ledger-post /
# service-ledger-reverse rules below. The exclusion on the operator rules closes the
# role-only path for ALL service accounts; the prohibition below vetoes the edge on every
# ledger write at the allow head, beating the matrix grant the exclusion cannot reach.
# Edge-scoped rather than all-service-accounts because the shared client IS a legitimate
# writer here (interest #3698 could prohibit all service-accounts; ledger cannot).
#
# ledger.approve is in the prohibition even though the matrix does not grant it: the veto is
# cheap, and no future matrix edit must ever hand the edge a statutory close event.

package openbank.rest

import rego.v1

allowed_reasons contains "viewer-auditor-ledger-read" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_VIEWER", "ROLE_AUDITOR"}
	role in input.principal.roles
	some verb in {"list", "read"}
	endswith(input.action, sprintf(".%v", [verb]))
}

# Every ledger read endpoint's own @RolesAllowed also admits ROLE_SERVICE directly (not
# just via the money-path write grants below) — verified in-repo M2M read callers:
#   - balance-service (LedgerTrialBalanceClient): GET /api/v1/journals/trial-balance
#     (ADR-0039 Phase A deposit-control reconciliation) -> ledger.read.
#   - finrep-service (LedgerRestClient): calls GET /api/v1/ledger/trial-balance, which
#     does NOT match any @Path on this service today (LedgerResource's trial-balance is
#     under /api/v1/journals, YearCloseResource's is under /api/v1/ledger/close) — a
#     pre-existing routing defect, independent of authz, flagged in this PR's "Residual
#     risk" and NOT fixed here (out of scope for a security-only change). Granted anyway
#     since the intent is clearly ledger.read and fixing the route later must not also
#     require an OPA change.
#   - agent-service's AI_AGENT reads (listJournals/trialBalance) go through
#     agent-charter-allows in base rest.rego, not this identity-gated rule.
#   - security-scanner only probes /q/health/ready (unauthenticated, no @Authorize path).
#
# NOTE (found via PR #403 / rules.yaml: authz_policy): AuthorizeInterceptor never emits
# principal.type == "SERVICE" — M2M callers authenticate with a Keycloak client_credentials
# JWT, which the interceptor classifies as HUMAN, and no realm client is ever granted
# ROLE_SERVICE. A rule gated on `principal.type == "SERVICE"` is structurally unreachable
# dead code. Every verified M2M caller below (balance-service, transaction-service,
# lending-service, settlement-service) shares ONE Keycloak confidential client,
# `openbank-services` (ADR-0104 D3), so its client_credentials token's
# principal.id is deterministically "service-account-openbank-services" (Keycloak's
# service-account-<clientId> convention). Gating on HUMAN + ROLE_OPERATOR alone would be
# unsafe (real operator/admin staff also carry ROLE_OPERATOR); gate on identity instead.
allowed_reasons contains "service-ledger-read" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-services"
	some verb in {"list", "read"}
	endswith(input.action, sprintf(".%v", [verb]))
}

# Operators and admins may perform routine ledger writes. Close DRAFT creation has a
# separate human-only reason below because the recorded principal is maker evidence.
allowed_reasons contains "operator-ledger-write" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	not startswith(input.principal.id, "service-account-")
	input.action in {"ledger.create", "ledger.reverse", "ledger.trigger", "ledger.replay"}
}

# Period and fiscal-year close DRAFT creation is a maker action. It must never inherit the
# service-ledger-post exception for ledger.create: a service-account maker makes the later
# four-eyes comparison technically distinct but evidentially meaningless.
allowed_reasons contains "operator-ledger-close-draft" if {
    input.principal.type == "HUMAN"
    some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
    role in input.principal.roles
    not startswith(input.principal.id, "service-account-")
    input.action == "ledger.close.draft"
}

# Year-close attestation (ledger.approve) is deliberately its OWN rule, not folded into
# operator-ledger-write above: it is a statutory close event (ADR-0078 D5), not a routine
# posting, and unlike create/reverse it must NEVER be reachable by any SERVICE principal —
# no in-repo caller invokes it (it's an operator/admin console-only action) and none ever
# should, since attesting is a human sign-off by design (four-eyes on the maker/attestor
# pair is enforced in-service, see YearCloseResource's draftedBy/attestedBy check).
allowed_reasons contains "operator-year-close-attest" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	not startswith(input.principal.id, "service-account-")
	input.action == "ledger.approve"
}

# M2M callers that post a balanced journal entry (ledger.create) — verified against
# each caller's actual RestClient interface, not assumed from the money-path shape:
#   - transaction-service (LedgerRestClient): postJournal + reverseJournal — the payment
#     saga posts on settlement and reverses on a failed/compensated leg.
#   - lending-service (LedgerRestClient): postJournal only — loan disbursal/repayment/
#     write-off journals; lending never reverses a ledger entry itself (a lending
#     correction is a new offsetting journal, not a ledger.reverse call — no reverseJournal
#     method exists on its client).
#   - settlement-service (LedgerRestClient, configKey "ledger-api"): postJournal only —
#     the Temporal settlement workflow's ledger-booking activity; no reverse call exists
#     on its client either.
# Deliberately narrow: only these three services have a verified in-repo RestClient call
# to a ledger write endpoint. Gated on identity (see service-ledger-read's note above,
# PR #403 / rules.yaml: authz_policy) — principal.type == "SERVICE" never fires.
# transaction-service, lending-service and settlement-service ALL share the single
# `openbank-services` Keycloak client (verified: each service's application.yaml /
# ADR-0104 D3 pilot note in oidc-externalsecrets.yaml), so OPA cannot distinguish WHICH
# of the three is calling — a single identity-gated allow-rule per action is therefore
# the finest grain achievable today, not a deliberately loose grant (see PR "Residual
# risk").
allowed_reasons contains "service-ledger-post" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-services"
	input.action == "ledger.create"
}

# Only transaction-service's client exposes a reverseJournal call. lending-service and
# settlement-service have NO reverseJournal method on their ledger RestClients — granting
# ledger.reverse to every caller sharing the openbank-services identity would open the one
# truly destructive ledger write (undoing a posted, book-of-record journal entry) beyond
# what's verified. Kept as its own reason (not merged into service-ledger-post) so the
# audit trail (`decision_reason`) distinguishes a posted-journal M2M call from a reversal
# one, even though both currently require the same shared identity (OPA cannot narrow
# further than "some caller holding the openbank-services service-account token" — see
# PR "Residual risk").
allowed_reasons contains "service-ledger-reverse" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-services"
	input.action == "ledger.reverse"
}

# ledger.trigger (FX revaluation) and ledger.approve (year-close attest) have NO in-repo
# M2M caller — the FX revaluation is scheduled in-process (FxRevaluationScheduler) and the
# ops/backfill re-run endpoint is operator-only; year-close attestation is a human sign-off
# by design (see operator-year-close-attest above). Neither gets a service-* rule: an
# un-mapped SERVICE call to either 403s, by design (deny-by-default, no rule added).

prohibited if {
	input.principal.id == "service-account-openbank-edge"
    input.action in {"ledger.create", "ledger.reverse", "ledger.trigger", "ledger.replay", "ledger.approve", "ledger.close.draft"}
}
