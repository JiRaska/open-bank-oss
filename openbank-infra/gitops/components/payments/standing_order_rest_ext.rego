# SPDX-License-Identifier: Apache-2.0
# Standing-order-service REST extension (ADR-0034 Phase 5 bootstrap, issue #1797).
# Extends openbank.rest with the standing-order allow reason.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (StandingOrderResource):
#   standingOrder.pause — pause (#id): the ONLY @Authorize-annotated endpoint on the
#                         service. resume()/cancel()/record-execution carry @RolesAllowed
#                         but no @Authorize, so OPA is never consulted for them.
#
# @RolesAllowed on pause() is ("ROLE_SERVICE", "ROLE_OPERATOR", "ROLE_ADMIN"). ROLE_SERVICE is
# structurally unreachable (rules.yaml authz_policy.principal_type_service_unreachable —
# AuthorizeInterceptor only ever emits ANONYMOUS/AI_AGENT/HUMAN, and no realm client is granted
# ROLE_SERVICE), so the effective human grantees are ROLE_OPERATOR / ROLE_ADMIN.
#
# Verified caller: customer-edge pauses a customer's OWN standing order (self-service,
# X-Customer-Party-Id actor header) — CustomerEdgeResource.kt POSTs
# `$standingOrderServiceUrl/api/v1/standing-orders/{id}/pause` through UpstreamClient.
#
# CORRECTION (#4228): an earlier revision of this comment said that call carries the SHARED
# `openbank-services` client. It does not. `UpstreamClient.clientId` is
# `${openbank.upstream.client-id:openbank-edge}`, so the caller is
# `service-account-openbank-edge` — a DIFFERENT identity, and the distinction is the whole fix.
# In the deployed realm (gitops/components/keycloak/realm-template.json) the edge account holds
# ROLE_OPERATOR while `service-account-openbank-services` holds only ROLE_API; the docker and CI
# realms hand the shared account ROLE_OPERATOR too. Taking the union across realms, the old
# role-only rule therefore admitted EVERY backend service to this write, not just the edge.

package openbank.rest

import rego.v1

# Operators/admins may pause a standing order — mirroring StandingOrderResource.pause()'s
# @RolesAllowed.
#
# HUMANS ONLY (GHSA-58jq-9hq3-66jr, issue #4228). This is remediation path 3, not the
# vop/fx/ledger exclusion-only path: standingOrder.pause HAS a real M2M caller, so the
# identity-scoped grant below had to land first — an exclusion on its own would have broken
# customer self-service pause. Measured against standing-order-opa-bundle.yaml before the change,
# `service-account-openbank-services` + ROLE_OPERATOR resolved exactly
# ["operator-standing-order-pause"]: the shared identity's ONLY reason, i.e. a live over-grant
# with no fallback, which is why the order matters.
allowed_reasons contains "operator-standing-order-pause" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action == "standingOrder.pause"
}

# M2M: customer-edge's self-service pause. Gated on the Keycloak client_credentials convention
# (`service-account-*` preferred_username), which AuthorizeInterceptor classifies as HUMAN —
# there is no SERVICE principal type, and a rule gated on one is unreachable dead code
# (rules.yaml: authz_policy.principal_type_service_unreachable).
#
# Deliberately pinned to the ONE client id, unlike m2m-vop-verify's `startswith(...)` form.
# vop.verify is a pre-payment lookup shared by four rails and bounded by a rate limit; pausing a
# standing order mutates a customer's payment schedule and has exactly one legitimate caller, so
# any-service-account would re-create the exposure this rule exists to close.
#
# Deliberately scoped to standingOrder.pause ONLY, never a `standingOrder.` family prefix:
# pause() is the sole @Authorize-annotated endpoint on the service today, and a family grant
# would silently pre-authorise cancel/resume the moment either gains @Authorize.
#
# Ownership is NOT checked here: the edge passes the caller's own X-Customer-Party-Id and
# StandingOrderResource enforces the party match. OPA grants the action class only.
allowed_reasons contains "m2m-standing-order-pause" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-edge"
	input.action == "standingOrder.pause"
}
