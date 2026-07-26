#!/usr/bin/env bash
set -euo pipefail
REPO="$(git rev-parse --show-toplevel)"

REST_REGO=$REPO/openbank-libs/governance/policies/rest.rego
AGENTS_REGO=$REPO/openbank-infra/opa/policies/agents.rego
AGENTS_YAML=$REPO/openbank-libs/governance/agents.yaml
RULES_YAML=$REPO/openbank-libs/governance/rules.yaml
MANIFEST=$REPO/openbank-infra/opa/bundle.manifest

# Consent REST extension — consent lifecycle allow reasons (ADR-0126 D5, issue #263)
CONSENT_REST_EXT=$(cat << 'REGO'
# SPDX-License-Identifier: Apache-2.0
# Consent-service REST extension (ADR-0034 Phase 5, ADR-0126 D5, issue #263).
# Extends openbank.rest with consent-domain allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (ConsentResource):
#   consent.grant     — create (PENDING_SCA); operator console / onboarding flows (renamed from
#                       consent.create, issue #938 follow-up: "grant" is a distinctive four-eyes
#                       verb, so it cannot silently gate every OTHER money-path service's
#                       unrelated `.create` action fleet-wide); four-eyes gated. ALSO grantable by
#                       the shared M2M principal, but ONLY for grantee=party-service:marketing-comms
#                       (ADR-0206) — see service-consent-m2m-marketing below.
#   consent.read      — getById
#   consent.list      — listByParty (#partyId), listByGrantee (#granteeId)
#   consent.revoke    — revoke (DELETE); four-eyes gated (operator-initiated denial of a
#                       customer's active consent, rules.yaml four_eyes.verbs). Same M2M exception
#                       as consent.grant above (ADR-0206), same grantee restriction.
#   consent.activate  — activate after SCA challenge completes. Deliberately NOT four-eyes
#                       gated (issue #938 follow-up): the M2M grant below already reserves this
#                       action for a possible SCA-completion-callback caller — four_eyes_required
#                       has no awareness of caller identity, so gating it would risk pausing that
#                       automated flow too, not just a risky operator-console path.
#   consent.reject    — reject (customer cancelled SCA)
#   consent.validate  — validate scope/account coverage (resource servers)
#
# Actions gated (ApprovalResource, ADR-0155):
#   consent.approval.decide — a DIFFERENT operator decides a paused consent.grant /
#                             consent.revoke request (#id)
#
# Base rest.rego already grants: operator-read-any / compliance-read-any for
# consent.read + consent.list, and party-self-service for consent.list when the
# JWT sub equals the {partyId}/{granteeId} path parameter.

package openbank.rest

import rego.v1

# Operators and admins may perform ANY consent lifecycle operation — the ops
# console path (create on behalf of a party, revoke, resolve a stuck PENDING_SCA).
allowed_reasons contains "operator-consent-write" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	startswith(input.action, "consent.")
}

# M2M resource servers acting in the consent ceremony: psd2-service validates a
# consent before serving an AIS/PIS call (consent.read / consent.validate), and
# the SCA completion callback activates or rejects a PENDING_SCA consent
# (consent.activate / consent.reject). Deliberately narrow: consent.grant and
# consent.revoke are otherwise NOT granted to M2M clients — those originate from a human
# channel (customer or operator), mirroring edge-service-notification's stance
# that a blanket SERVICE allow would open every @Authorize endpoint to any
# M2M client (the narrow, grantee-scoped exception below is the sanctioned deviation —
# ADR-0206 — not a reopening of that blanket-allow question). This is also why
# consent.activate stays OUT of four_eyes.verbs (rules.yaml) despite being a
# risk-relevant action — see that file's guardrail note (issue #938 follow-up).
#
# NOTE (found post-merge, issue tracked separately): AuthorizeInterceptor never
# emits principal.type == "SERVICE" — M2M callers authenticate via Keycloak
# client_credentials JWTs, which the interceptor classifies as HUMAN. Nearly
# every backend service (psd2-service, sca-service included) shares ONE
# Keycloak client `openbank-services`, whose service-account identity is
# `service-account-openbank-services` — gate on that identity instead of a
# type/role that never fires. This means psd2-service and sca-service (and any
# other `openbank-services`-client caller) are NOT distinguishable from each
# other at this layer — this rule grants the listed actions to ANY backend
# service using that shared client, not just the two verified callers.
allowed_reasons contains "service-consent-m2m" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-services"
	input.action in {"consent.read", "consent.validate", "consent.activate", "consent.reject"}
}

# ADR-0206: narrow, resource-scoped exception to the "M2M never grants/revokes" stance above.
# party-service forwards the mobile app's marketing-consent toggle here (ADR-0198/ADR-0205) —
# it authenticates via the SAME shared M2M client as every other backend service, so this rule
# cannot distinguish party-service from any other `openbank-services` caller by identity alone.
# It instead scopes by RESOURCE: AuthorizeInterceptor's dotted-path extraction
# (ADR-0206 D1, `#request.granteeId` on create / `#granteeId` on revoke) binds
# input.resource.id to the request's own granteeId field, and this rule only fires when that
# equals the one fixed grantee party-service's forwarder uses. Any other `openbank-services`
# caller — or party-service itself, for any OTHER granteeId — still falls through to deny,
# same as before this rule existed. ConsentService.revokeConsent additionally cross-checks the
# passed granteeId against the loaded consent's actual granteeId before revoking (defense in
# depth: the OPA decision alone can't see the DB row on this action, and — see rules.yaml's
# openbank-consent-service guardrail note — AUTHZ_FOUR_EYES_ENFORCE is false for this service
# today, so this M2M path isn't paused pending a second human approver; revisit before ever
# flipping that flag here).
allowed_reasons contains "service-consent-m2m-marketing" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-services"
	input.action in {"consent.grant", "consent.revoke"}
	input.resource.id == "party-service:marketing-comms"
}
REGO
)

CHECKSUM=$(printf '%s\n' \
    "$(cat "$REST_REGO")" \
    "$(echo "$CONSENT_REST_EXT")" \
    "$(cat "$AGENTS_REGO")" \
    "$(cat "$AGENTS_YAML")" \
    "$(cat "$RULES_YAML")" \
    "$(cat "$MANIFEST")" | \
  (command -v sha256sum >/dev/null 2>&1 && sha256sum || shasum -a 256) | cut -c1-16)

OUT=$REPO/openbank-infra/gitops/components/consent/consent-opa-bundle.yaml

{
  echo "# GENERATED by gen-consent-opa-bundle.sh — do not hand-edit."
  echo "# Source: rest.rego + consent_rest_ext.rego + agents.rego + agents.yaml + rules.yaml + bundle.manifest"
  echo "apiVersion: v1"
  echo "kind: ConfigMap"
  echo "metadata:"
  echo "  name: consent-opa-bundle"
  echo "  namespace: consent"
  echo "  labels:"
  echo "    app.kubernetes.io/name: consent-service"
  echo "    app.kubernetes.io/part-of: consent"
  echo "  annotations:"
  echo "    openbank.tech/policy-checksum: \"$CHECKSUM\""
  echo "data:"
  echo "  rest.rego: |"
  sed 's/^/    /' "$REST_REGO" | sed 's/[[:space:]]*$//'
  echo "  consent_rest_ext.rego: |"
  echo "$CONSENT_REST_EXT" | sed 's/^/    /' | sed 's/[[:space:]]*$//'
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
# (subPath mounts do NOT hot-reload — same pattern as gen-customer-edge-opa-bundle.sh).
ROLLOUT=$REPO/openbank-infra/gitops/components/consent/consent-service.yaml
if [ -f "$ROLLOUT" ]; then
  sed -i.bak "s|openbank.tech/policy-checksum: \"[^\"]*\"|openbank.tech/policy-checksum: \"$CHECKSUM\"|" "$ROLLOUT"
  rm -f "${ROLLOUT}.bak"
  echo "patched $ROLLOUT annotation → $CHECKSUM"
fi
