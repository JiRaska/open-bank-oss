#!/usr/bin/env bash
set -euo pipefail
REPO="$(git rev-parse --show-toplevel)"

REST_REGO=$REPO/openbank-libs/governance/policies/rest.rego
AGENTS_REGO=$REPO/openbank-infra/opa/policies/agents.rego
AGENTS_YAML=$REPO/openbank-libs/governance/agents.yaml
RULES_YAML=$REPO/openbank-libs/governance/rules-opa-data.yaml
MANIFEST=$REPO/openbank-infra/opa/bundle.manifest

# Ledger REST extension — general-ledger allow reasons (ADR-0034 Phase 5, issue #266).
# ledger-service is the bank's double-entry book of record — the single most sensitive
# write target in the fleet. Every write action below is deny-by-default until an
# explicit reason below (or in base rest.rego) fires; no blanket SERVICE allow.
LEDGER_REST_EXT=$(cat << 'REGO'
# SPDX-License-Identifier: Apache-2.0
# Ledger-service REST extension (ADR-0034 Phase 5, issue #266).
# Extends openbank.rest with ledger-domain allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (LedgerResource / YearCloseResource / ControlAccountResource /
# FxRevaluationResource) — all @Authorize action strings verified against the real
# source, not guessed:
#   ledger.list     — list journal entries (LedgerResource)
#   ledger.read     — journal/trial-balance/sub-ledger-balance/control-account reads
#   ledger.create   — POST a balanced journal entry (LedgerResource.postJournal);
#                      ALSO used by YearCloseResource.createDraft (#fiscalYear resource) —
#                      same action string, two different resources/use cases, both
#                      operator-only writes on the book of record.
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

# Operators and admins may perform any ledger write: post/reverse a journal, run an
# FX revaluation, or create/refresh a year-close DRAFT. Attestation (ledger.approve)
# rides on this too — it is still an operator action, but see the note below on why
# it has NO M2M path at all (unlike create/reverse).
allowed_reasons contains "operator-ledger-write" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action in {"ledger.create", "ledger.reverse", "ledger.trigger", "ledger.replay"}
}

# Year-close attestation (ledger.approve) is deliberately its OWN rule, not folded into
# operator-ledger-write above: it is a statutory close event (ADR-0078 D5), not a routine
# posting, and unlike create/reverse it must NEVER be reachable by any SERVICE principal —
# no in-repo caller invokes it (it's an operator/admin console-only action) and none ever
# should, since attesting is a human sign-off by design (four-eyes on the maker/attestor
# pair is enforced in-service, see YearCloseResource's draftedBy/attestedBy check).
#
# The `service-account-` exclusion is what makes the sentence above TRUE. Without it the
# rule was role-only, and `service-account-openbank-services` — the identity nearly every
# backend service authenticates as — carries ROLE_OPERATOR in the realm while
# AuthorizeInterceptor classifies its client_credentials JWT as HUMAN. Measured against
# this very bundle with `opa eval` (issue #3765): ledger.approve resolved
# allow=true, reason="operator-year-close-attest" for that principal, so the "NEVER
# reachable by any SERVICE principal" claim above, and the closing note at the bottom of
# this file that an un-mapped SERVICE call "403s, by design", were both wrong — three
# @Authorize sites use this action (YearCloseResource.attest, AccountingDayResource,
# ClosedPeriodResource), all statutory sign-offs. Excluding, not identity-pinning: there is
# no legitimate M2M attestor to name — no module outside openbank-ledger-service declares a
# client for these routes (every fleet Ledger*Client was read; none exposes attest /
# accounting-day / closed-period). rest.rego's `shared_m2m_write_prohibition` cannot cover
# this: that register is keyed by reason name, and its data key is not even emitted into
# the bundles (see gen-rules-opa-data.py's own note), so the veto has never fired anywhere.
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
# by design (see operator-year-close-attest above). Neither gets a service-* rule.
#
# CORRECTION (#3765): "no rule added" was never sufficient for either. Deny-by-default only
# holds for an action NO rule reaches, and both of these are reached by a role-only rule the
# shared M2M identity satisfies — ledger.approve via operator-year-close-attest (now closed
# by the exclusion above) and ledger.trigger / ledger.replay via operator-ledger-write AND
# base rest.rego's `matrix-allows`, since rules.yaml grants both to ROLE_OPERATOR. Those two
# are deliberately NOT closed here: `matrix-allows` is a base-layer reason no per-service ext
# can veto, so excluding service-accounts from operator-ledger-write alone would remove one
# of two paths and change nothing. That is issue #3765's decision (a) vs (b), not a
# per-service edit.
REGO
)

CHECKSUM=$(printf '%s\n' \
    "$(cat "$REST_REGO")" \
    "$(echo "$LEDGER_REST_EXT")" \
    "$(cat "$AGENTS_REGO")" \
    "$(cat "$AGENTS_YAML")" \
    "$(cat "$RULES_YAML")" \
    "$(cat "$MANIFEST")" | \
  (command -v sha256sum >/dev/null 2>&1 && sha256sum || shasum -a 256) | cut -c1-16)

OUT=$REPO/openbank-infra/gitops/components/ledger/ledger-opa-bundle.yaml

{
  echo "# GENERATED by gen-ledger-opa-bundle.sh — do not hand-edit."
  echo "# Source: rest.rego + ledger_rest_ext.rego + agents.rego + agents.yaml + rules-opa-data.yaml + bundle.manifest"
  echo "apiVersion: v1"
  echo "kind: ConfigMap"
  echo "metadata:"
  echo "  name: ledger-opa-bundle"
  echo "  namespace: ledger"
  echo "  labels:"
  echo "    app.kubernetes.io/name: ledger-service"
  echo "    app.kubernetes.io/part-of: ledger"
  echo "  annotations:"
  echo "    openbank.tech/policy-checksum: \"$CHECKSUM\""
  echo "data:"
  echo "  rest.rego: |"
  sed 's/^/    /' "$REST_REGO" | sed 's/[[:space:]]*$//'
  echo "  ledger_rest_ext.rego: |"
  echo "$LEDGER_REST_EXT" | sed 's/^/    /' | sed 's/[[:space:]]*$//'
  echo "  agents.rego: |"
  sed 's/^/    /' "$AGENTS_REGO" | sed 's/[[:space:]]*$//'
  echo "  agents-data.yaml: |"
  sed 's/^/    /' "$AGENTS_YAML" | sed 's/[[:space:]]*$//'
  echo "  rules-data.yaml: |"
  sed 's/^/    /' "$RULES_YAML" | sed 's/[[:space:]]*$//'
  echo "  manifest.json: |"
  sed 's/^/    /' "$MANIFEST" | sed 's/[[:space:]]*$//'
  printf '\n'
} > "$OUT"

echo "wrote $OUT (checksum $CHECKSUM)"

# Sync the Rollout pod-roll annotation so a policy change always triggers a rollout
# (subPath mounts do NOT hot-reload — same pattern as gen-sca-opa-bundle.sh).
ROLLOUT=$REPO/openbank-infra/gitops/components/ledger/ledger-service.yaml
if [ -f "$ROLLOUT" ]; then
  sed -i.bak "s|openbank.tech/policy-checksum: \"[^\"]*\"|openbank.tech/policy-checksum: \"$CHECKSUM\"|" "$ROLLOUT"
  rm -f "${ROLLOUT}.bak"
  echo "patched $ROLLOUT annotation → $CHECKSUM"
fi
