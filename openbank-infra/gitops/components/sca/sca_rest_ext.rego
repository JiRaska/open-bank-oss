# SPDX-License-Identifier: Apache-2.0
# Sca-service REST extension (ADR-0034 Phase 5, issue #266).
# Extends openbank.rest with SCA-domain allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (ScaResource):
#   scaChallenge.initiate — initiate a challenge (POST /challenges)
#   scaChallenge.read     — get challenge by id
#   scaChallenge.verify   — OTP fallback verification (no in-repo M2M caller today)
#   scaChallenge.decide   — record device-signed approval/denial
#   scaChallenge.consume  — spend an approved challenge (ADR-0021 settlement gate)
#   device.enroll         — enrol a device credential (#partyId)
#   device.list           — list a party's device credentials (#partyId)
#
# Base rest.rego already grants: operator-read-any / compliance-read-any for
# *.read + *.list, party-self-service for device.list when the JWT sub equals
# the {partyId} path parameter, and edge-service-notification for SERVICE
# principals on the whole device.* family (device.enroll / device.list).

package openbank.rest

import rego.v1

# Operators and admins may perform ANY SCA challenge lifecycle operation — the ops
# console path (resolve a stuck challenge, service-desk credential reset per the
# ADR-0021 enroll-on-behalf note). device.* writes for operators ride on this too.
#
# The `service-account-` exclusion (#3734): both realm M2M clients carry ROLE_OPERATOR
# and are HUMAN-classified, so the role-only shape admitted them to EVERY
# scaChallenge.*/device.* write — including scaChallenge.verify (the OTP fallback,
# documented below as human-channel-only) and any future action in those families.
# The legitimate M2M callers keep their identity-scoped rules below; the edge's
# device.* access rides base edge-service-notification. No prohibition clause here,
# unlike interest/balance/ledger/fraud: rules.yaml's role_action_matrix grants NO
# scaChallenge.*/device.* write to ROLE_OPERATOR, so base matrix-allows admits
# nothing the exclusion doesn't already close (verified 2026-08-05).
allowed_reasons contains "operator-sca-write" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	not startswith(input.principal.id, "service-account-")
	some family in {"scaChallenge.", "device."}
	startswith(input.action, family)
}

# M2M callers in the SCA ceremony (both verified in-repo).
#
# NOTE (found post-merge, issue tracked separately): AuthorizeInterceptor never
# emits principal.type == "SERVICE" — M2M callers authenticate via Keycloak
# client_credentials JWTs, which the interceptor classifies as HUMAN. Gate on
# the verified client identity instead:
#   - customer-edge uses its own Keycloak client, identity
#     `service-account-openbank-edge` — initiate a challenge for the
#     authenticated customer, poll it (read), record the device decision, and
#     consume the approved challenge as the payment settlement gate (ADR-0021 —
#     scaGate runs before every money-path forward).
#   - consent-service shares the `openbank-services` client (like nearly every
#     other backend service), identity `service-account-openbank-services` —
#     reads a challenge to activate/reject a PENDING_SCA consent
#     (scaChallenge.read only). This identity is NOT unique to consent-service:
#     any other backend service using the shared client would also match this
#     rule — documented limitation, not fixable without a per-service client or
#     audience claim.
#   - scaChallenge.consume on the shared client was added in #3734: delegation-service
#     (ScaChallengeClient.consumeChallenge — the grant-accept ceremony) and
#     document-service (ScaChallengeClient.consume — the DOCUMENT_SIGNING ceremony,
#     ADR-0169 D2) both POST /api/v1/sca/challenges/{id}/consume via the shared
#     client, and until then rode the role-only operator-sca-write hole. Excluding
#     service-accounts from that rule without granting consume here first would have
#     403'd both ceremonies — the "identity-scoped rule FIRST" ordering from the
#     #3734 remediation pattern.
# Deliberately narrow: scaChallenge.verify (the OTP fallback) has NO in-repo M2M
# caller and stays human-channel-only — a blanket allow would open every
# @Authorize endpoint to any M2M client (edge-service-notification's stance).
# device.enroll for the edge is already granted by edge-service-notification in
# base rest.rego — not duplicated here.
allowed_reasons contains "service-sca-edge-m2m" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-edge"
	input.action in {
		"scaChallenge.initiate",
		"scaChallenge.read",
		"scaChallenge.decide",
		"scaChallenge.consume",
	}
}

allowed_reasons contains "service-sca-shared-client-m2m" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-services"
	input.action in {"scaChallenge.read", "scaChallenge.consume"}
}