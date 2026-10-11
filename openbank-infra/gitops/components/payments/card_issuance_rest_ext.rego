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
#   card.cancel    — cancel (#id) — staff operator/admin/compliance, or named customer-edge
#   card.suspend   — suspend (#id) — customer-edge calls this on a customer's OWN card
#                    (self-service freeze, /customer/v1/cards/{id}/freeze)
#   card.resume    — resume (#id) — same, customer self-service unfreeze
#   card.limits.update / card.controls.update — staff or named customer-edge after ownership checks
#   card.category-limits.update — staff only (no customer-edge route currently)
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

# #12328: these additional writes are admitted by the REST resource for staff, but the
# existing operator-card-write rule also serves machine identities with ROLE_OPERATOR.
# Extending that rule would grant every such backend card cancellation and limit changes.
# A Keycloak client_credentials principal is HUMAN in the interceptor, so exclude its
# service-account username explicitly (the same pattern as operator-mcp-session in rest.rego).
allowed_reasons contains "staff-card-controls-write" if {
    input.principal.type == "HUMAN"
    some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
    role in input.principal.roles
    not startswith(input.principal.id, "service-account-")
    input.action in {"card.cancel", "card.limits.update", "card.controls.update", "card.category-limits.update"}
}

# Compliance may cancel as well as block a card per CardResource's @RolesAllowed. A
# shared M2M principal with ROLE_COMPLIANCE in the CI realm must not inherit this write.
allowed_reasons contains "staff-compliance-card-cancel" if {
    input.principal.type == "HUMAN"
    "ROLE_COMPLIANCE" in input.principal.roles
    not startswith(input.principal.id, "service-account-")
    input.action == "card.cancel"
}

# Customer-edge already checks card ownership/mandate and step-up where required before
# forwarding these three self-service writes. Its dedicated machine identity alone can
# reach them; the shared openbank-services principal does not gain these actions.
allowed_reasons contains "edge-card-self-service-write" if {
    input.principal.type == "HUMAN"
    input.principal.id == "service-account-openbank-edge"
    "ROLE_OPERATOR" in input.principal.roles
    input.action in {"card.cancel", "card.limits.update", "card.controls.update"}
}

# CardResource admits ROLE_VIEWER to list/read, including the staff demo account. Keep
# that read role scoped to human users; a service account classified HUMAN by the
# interceptor must not acquire access just by holding ROLE_VIEWER.
allowed_reasons contains "staff-card-viewer-read" if {
    input.principal.type == "HUMAN"
    "ROLE_VIEWER" in input.principal.roles
    not startswith(input.principal.id, "service-account-")
    input.action in {"card.list", "card.read"}
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

# #10486 batch 6 — per-service machine identities for the two card READS that used to ride the
# shared openbank-services principal's ROLE_OPERATOR. Each principal holds ROLE_API only; CardResource
# admits ROLE_API on exactly these two endpoints, and because card-issuance still runs
# AUTHZ_ENFORCE=false a Kotlin named-caller check (CardReadCallerGuard.kt) enforces the same lists.
# CardReadCallerGuardTest pins the Kotlin sets to these rules.
#   delegation-service CardIssuanceRestClient.getCard  GET /cards/{id}             card.read
#   party-service      CardServiceRestClient.listByParty GET /cards/party/{partyId} card.list
allowed_reasons contains "service-delegation-card-read" if {
	input.principal.type == "HUMAN"
	input.principal.id in {"service-account-openbank-delegation"}
	input.action == "card.read"
}

allowed_reasons contains "service-party-card-list" if {
	input.principal.type == "HUMAN"
	input.principal.id in {"service-account-openbank-party"}
	input.action == "card.list"
}
