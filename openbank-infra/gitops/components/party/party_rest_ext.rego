# SPDX-License-Identifier: Apache-2.0
# Party-service REST extension (ADR-0034 Phase 5).
# Extends openbank.rest with party-domain allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (PartyResource):
#   party.update          — PATCH /parties/{id}, operator edit of contact details
#   party.consent.update  — PATCH /parties/{id}/consent, post-onboarding marketing consent
#   party.merge           — POST  /parties/{id}/merge, retire a duplicate identity (ADR-0179)
#   party:resolve         — POST  /parties/resolve (deliberately NOT granted, see below)
#
# WHY THIS FILE EXISTS AT ALL: party-service's application.yaml sets
# `authz.enforce: "${AUTHZ_ENFORCE:true}"` and the gitops manifest never declared an OPA
# sidecar, so AuthorizeInterceptor had no PDP to call and failed closed on EVERY action
# above ("policy decision point unavailable: OPA call failed"). The endpoints were bricked,
# invisibly, because none of them was exercised in sandbox until party.merge landed.
# This bundle is the missing PDP; it does not relax anything.
#
# Base rest.rego already grants operator-read-any / compliance-read-any for *.read + *.list.
# party-service annotates NO read action with @Authorize, so nothing here rides on that.

package openbank.rest

import rego.v1

# Human operator/admin writes on the party aggregate. Editing contact details, toggling
# marketing consent from the operator console, and retiring a duplicate identity are all
# support-desk / back-office actions.
#
# The `service-account-` exclusion is load-bearing, not defensive boilerplate — same idiom
# as operator-compose-message in base rest.rego. AuthorizeInterceptor NEVER emits
# principal.type == "SERVICE" (rules.yaml: authz_policy.principal_type_service_unreachable);
# a Keycloak client_credentials token is classified HUMAN, and the customer-edge M2M client
# carries ROLE_OPERATOR in the realm. Without this exclusion, this single rule would hand
# service-account-openbank-edge the power to merge and retire arbitrary party identities —
# an identity-takeover primitive reachable from the public customer edge. The edge gets only
# the one narrow grant it has a real caller for, below.
allowed_reasons contains "operator-party-write" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	not startswith(input.principal.id, "service-account-")
	input.action in {
		"party.update",
		"party.consent.update",
		"party.merge",
	}
}

# customer-edge's M2M identity, for the mobile app's Profile-screen marketing-consent toggle.
#
# Evidence (required before adding any caller here — see the account-service rego's note):
# CustomerEdgeResource.updateConsent (PATCH /profile/consent) calls
# UpstreamClient.patch("$partyServiceUrl/api/v1/parties/{partyId}/consent"), which attaches
# a client_credentials bearer token, NOT the customer's own token — the customer identity
# travels in the PARTY_HEADER and the edge forces partyId from the authenticated session, so
# this grant cannot be steered at another customer's record. principal.id is the JWT's
# preferred_username, deterministically "service-account-<clientId>" for a service-account
# token; the edge's client id is openbank-edge (application.yaml openbank.upstream.client-id).
#
# Deliberately action-scoped to party.consent.update ONLY, not a "party." family prefix:
#   - party.update  has NO in-repo caller. Every customer-edge call to /api/v1/parties/{id}
#     is upstream.get (CustomerEdgeResource.kt:1066, :1137, :2095); registration is a POST to
#     the collection, which carries no @Authorize. Granting it would be speculative.
#   - party.merge   is an operator-console action that retires an identity. It must never be
#     reachable from the customer edge.
allowed_reasons contains "service-edge-party-consent-m2m" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-edge"
	input.action == "party.consent.update"
}

# NOT GRANTED — party:resolve (POST /parties/resolve, ADR-0072 blind-index dedup gate).
#
# Three independent reasons, any one of which is sufficient:
#   1. No caller. The live dedup gate is pid-service's own /api/v1/parties/resolve
#      (CustomerEdgeResource.kt:3100 targets $pidServiceUrl). party-service's copy has zero
#      in-repo callers.
#   2. Unreachable anyway. Its @RolesAllowed is ("ROLE_SERVICE", "ROLE_ADMIN"), and no realm
#      client is ever granted ROLE_SERVICE — the JAX-RS role check rejects M2M callers before
#      AuthorizeInterceptor ever runs.
#   3. Note the SEPARATOR: the action is "party:resolve" with a COLON, not a dot. Any future
#      rule written as startswith(input.action, "party.") will silently NOT match it. Whoever
#      revives this endpoint must grant it explicitly and should first normalise the action to
#      "party.resolve" so it stops being an outlier in the fleet's action taxonomy.
#
# Leaving it to default-deny keeps the grant surface minimal. Removing the dead endpoint is
# tracked separately — it is an API-surface change (openapi.yaml + contract test), not a
# security fix.
