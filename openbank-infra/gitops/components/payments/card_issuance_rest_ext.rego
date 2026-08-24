# SPDX-License-Identifier: Apache-2.0
# Card-issuance-service REST extension (ADR-0034 Phase 5 bootstrap, issue #938).
# Extends openbank.rest with card-domain allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (CardResource):
#   card.create    — issueCard
#   card.list      — listAll, listByAccount (#accountId), listByParty (#partyId)
#   card.read      — getCard (#id)
#   card.activate  — activate (#id)
#   card.block     — block (#id) — ROLE_OPERATOR/ROLE_ADMIN/ROLE_COMPLIANCE (@RolesAllowed is a
#                    disjunction: ROLE_COMPLIANCE widens the caller set, it does not narrow it)
#   card.suspend   — suspend (#id) — customer-edge calls this on a customer's OWN card
#                    (self-service freeze, /customer/v1/cards/{id}/freeze)
#   card.resume    — resume (#id) — same, customer self-service unfreeze
#
# Actions gated (CardOutboxAdminResource, #4005):
#   card.outbox.requeue — requeueDead — ROLE_ADMIN ONLY (not ROLE_OPERATOR)
#
# Base rest.rego already grants operator-read-any (ROLE_OPERATOR/ROLE_ADMIN) for card.list/.read
# — no extension needed for those. The remaining actions have no generic base-rego grant.
#
# Verified caller: customer-edge calls card.list/card.suspend/card.resume on the customer's own
# card via the shared `openbank-services` Keycloak client (service-account-openbank-services,
# whose realmRoles include ROLE_OPERATOR) — see CustomerEdgeResource.kt cardAction(). Per the
# documented fleet-wide limitation (rules.yaml authz_policy.principal_type_service_unreachable),
# AuthorizeInterceptor cannot distinguish this M2M caller from a real human operator holding
# ROLE_OPERATOR, so granting the role necessarily covers both — same stance as consent-service's
# service-consent-m2m rule.

package openbank.rest

import rego.v1

# Operators/admins may create, activate, block, suspend, or resume a card — mirroring
# CardResource's @RolesAllowed on each method. block() is included: its
# @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE") is a disjunction (any one role
# admits the caller), so ROLE_COMPLIANCE is an ADDITIONAL grantee, not an extra requirement.
# Omitting card.block here made the policy strictly narrower than the resource it guards: every
# card.block by ROLE_OPERATOR/ROLE_ADMIN would 403 the moment AUTHZ_ENFORCE flips to true,
# disabling the fraud response for a lost/stolen card for the only admin identity in the realm
# (admin@openbank.local holds OPERATOR/ADMIN/VIEWER, not COMPLIANCE).
allowed_reasons contains "operator-card-write" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action in {"card.create", "card.activate", "card.block", "card.suspend", "card.resume"}
}

# Requeueing dead-lettered outbox rows (CardOutboxAdminResource, #4005) republishes events that
# may already have been delivered — openbank-audit-service appends a second, permanently
# undeletable audit record for each — so it is granted to ROLE_ADMIN **only**, exactly matching
# that method's @RolesAllowed("ROLE_ADMIN"). Deliberately NOT folded into the
# operator-card-write set above: adding it there would widen the grant to ROLE_OPERATOR and make
# the policy broader than the resource, the mirror image of the card.block bug this file already
# documents. Keeping the two in lockstep in either direction is the point.
allowed_reasons contains "admin-card-outbox-requeue" if {
	input.principal.type == "HUMAN"
	"ROLE_ADMIN" in input.principal.roles
	input.action == "card.outbox.requeue"
}

# Blocking a card (fraud/compliance hold) is also grantable to ROLE_COMPLIANCE alone — a
# compliance officer who holds neither ROLE_OPERATOR nor ROLE_ADMIN can still block, matching the
# third role in CardResource's @RolesAllowed on block().
allowed_reasons contains "compliance-card-block" if {
	input.principal.type == "HUMAN"
	"ROLE_COMPLIANCE" in input.principal.roles
	input.action == "card.block"
}
