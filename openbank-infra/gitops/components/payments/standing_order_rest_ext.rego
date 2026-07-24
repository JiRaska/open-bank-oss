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
# X-Customer-Party-Id actor header) via the shared `openbank-services` Keycloak client
# (service-account-openbank-services, whose realmRoles include ROLE_OPERATOR). Per the documented
# fleet-wide limitation, AuthorizeInterceptor cannot distinguish this M2M caller from a real human
# operator holding ROLE_OPERATOR, so granting the role necessarily covers both — same stance as
# card-issuance-service's card.suspend/card.resume rule.

package openbank.rest

import rego.v1

# Operators/admins may pause a standing order — mirroring StandingOrderResource.pause()'s
# @RolesAllowed. Covers the customer-edge self-service pause too (it carries ROLE_OPERATOR via the
# shared services client, indistinguishable from a human operator at this layer).
allowed_reasons contains "operator-standing-order-pause" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action == "standingOrder.pause"
}
