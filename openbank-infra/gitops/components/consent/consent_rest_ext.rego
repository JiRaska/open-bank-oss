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
#
# EXCLUDES the shared M2M identity (found during ADR-0206 D5): every backend service on the
# `openbank-services` Keycloak client authenticates as `service-account-openbank-services`, and
# that service-account carries ROLE_OPERATOR in the realm (openbank-realm.json) — a role-only
# check here would let ANY such caller perform ANY consent.* write, unconditionally, the exact
# blanket-M2M-allow rules.yaml's own guardrail note (dependencies.principal_type_service_unreachable)
# already warns against ("ROLE_OPERATOR is shared with real human staff, so a role-only check
# over-grants"). This rule's header comment and the "service-consent-m2m" rule below both claimed
# consent.grant/consent.revoke were M2M-unreachable — false the whole time this exclusion was
# missing. M2M access to consent.grant/consent.revoke is scoped exclusively through
# "service-consent-m2m-marketing" below now.
#
# 2026-08-05 (#3734): the exclusion widened from the shared client to the `service-account-`
# prefix — ADR-0206 D5 closed the backend M2M identity but left `service-account-openbank-edge`
# (customer-facing, HUMAN-classified, ROLE_OPERATOR) admitted to EVERY consent.* write via this
# rule. The edge's legitimate consent access is exactly {consent.list, consent.revoke} via base
# rest.rego's edge-service-consent (the customer's PSD2 consent screen) — deliberate, scoped,
# and pinned by consent_rest_ext_test.rego. No prohibition clause: rules.yaml's matrix grants no
# consent.* write to ROLE_OPERATOR, so matrix-allows admits nothing this exclusion doesn't close.
allowed_reasons contains "operator-consent-write" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
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

# ADR-0269 rule 1: the customer switches CREDIT_OFFERS / CREDIT_PROFILE_USE / CREDIT_AI_AGENT
# on and off through the edge's own PUT /credit/consents — there is no operator or customer
# console step in that path, only the edge's M2M identity. Found live 2026-09-03: the switch
# had no OPA path at all, so every grant 403'd and the consent stayed permanently off
# regardless of what the customer chose — the exact silent-failure shape "operator-consent-write"
# above warns a role-only check would produce, except here it was under-grant instead of
# over-grant.
#
# Same resource-scoping discipline as service-consent-m2m-marketing: the edge's client
# (`service-account-openbank-edge`) is otherwise excluded from every consent.* write (see
# operator-consent-write's HUMAN-non-service-account guard above), so this rule is the only
# door, and it opens only for the edge's own first-party grantee — `BANK_GRANTEE = "openbank"`
# in CustomerEdgeResource — never for a TPP grantee a customer might one day consent into
# through the same client identity.
allowed_reasons contains "service-consent-m2m-credit" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-edge"
	input.action in {"consent.grant", "consent.revoke"}
	input.resource.id == "openbank"
}

# ADR-0219 D3 suppression administration (#3656 slice 2). Writes (suppression.manage) are
# HUMAN-only — operators on the preference-centre / complaints / RM surfaces; no M2M writer
# exists yet, and the shared-M2M exclusion mirrors operator-consent-write for the same reason
# (a role-only check would grant every backend service the write). Reads (suppression.read) are
# the contact-policy gate's shape: operators AND backend senders via the shared M2M client —
# same identity caveat as service-consent-m2m: indistinguishable from any other
# openbank-services caller at this layer, and acceptable for a low-sensitivity stop-list read.
allowed_reasons contains "operator-suppression-write" if {
	input.principal.type == "HUMAN"
	not input.principal.id == "service-account-openbank-services"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action == "suppression.manage"
}

allowed_reasons contains "operator-suppression-read" if {
	input.principal.type == "HUMAN"
	not input.principal.id == "service-account-openbank-services"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action == "suppression.read"
}

allowed_reasons contains "service-suppression-m2m-read" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-services"
	input.action == "suppression.read"
}
