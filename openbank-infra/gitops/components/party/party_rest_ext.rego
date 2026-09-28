# SPDX-License-Identifier: Apache-2.0
# Party-service REST extension (ADR-0034 Phase 5; ADR-0179 identity merge).
# Extends openbank.rest with party-domain allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (PartyResource @Authorize):
#   party.update          — edit contact details (PATCH /{id})
#   party.consent.update  — post-onboarding marketing-consent toggle (PATCH /{id}/consent)
#   party.merge           — retire a duplicate identity into a survivor (POST /{id}/merge, ADR-0179)
#   party:resolve         — RC blind-index dedup lookup (POST /resolve) — NOTE the colon, so it is
#                           NOT matched by the `party.` prefix rule below; granted explicitly.
#
# Base rest.rego already grants operator-read-any / compliance-read-any for *.read + *.list, so
# GET /{id}, /documents, list and search ride on that unchanged.
#
# party is NOT a money_path_services scope and none of update/merge/consent.update is a
# four_eyes.verbs verb (rules.yaml), so no four-eyes flag is raised here — deliberately. Do NOT
# add four-eyes logic in this file; that is rest.rego's job.

package openbank.rest

import rego.v1

# Human operator/admin writes across the party lifecycle. `startswith(input.action, "party.")`
# covers party.update, party.consent.update and party.merge in one rule (dot-namespaced actions);
# party:resolve uses a colon and is granted separately below.
#
# KNOWN OVER-GRANT (same shape as account-service's operator-account-write): a role-only check
# also matches the M2M service accounts, which carry ROLE_OPERATOR — so this rule grants
# service-account-openbank-edge every party.* action, including party.merge, not just the narrow
# {consent.update, resolve} set enumerated below. There is no clean "is a human" signal to gate on
# (AuthorizeInterceptor classifies a client_credentials JWT as HUMAN). Resolving it means a
# distinct service realm-role, a fleet-wide change (ADR-0065-adjacent) out of scope here. This is
# precisely why AUTHZ_ENFORCE stays false (advisory) for party-service: the enforce flip must wait
# until that separation lands, so party.merge cannot actually be reached by the edge M2M identity.
allowed_reasons contains "operator-party-write" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	startswith(input.action, "party.")
}

# M2M callers (verified in-repo).
#
# IMPORTANT (issue #266 fleet audit): AuthorizeInterceptor.principalType() NEVER emits "SERVICE" —
# it only ever produces ANONYMOUS/AI_AGENT/HUMAN. An M2M caller authenticates with a Keycloak
# client_credentials JWT, classified as HUMAN, and no realm client is granted ROLE_SERVICE. So a
# rule gated on `principal.type == "SERVICE"` is unreachable dead code. Identify each caller by its
# client_credentials identity: principal.id is "service-account-<clientId>". A role-only check
# (HUMAN + ROLE_OPERATOR) is NOT a safe substitute — real staff also carry ROLE_OPERATOR, which
# would over-grant these actions to any operator session.
#
#   - customer-edge (client-id "openbank-edge" -> principal.id "service-account-openbank-edge"):
#       party.consent.update — the app's Profile-screen marketing-consent toggle is forwarded to
#         party-service through the edge's own M2M token (CustomerEdgeResource, PATCH
#         /api/v1/parties/{id}/consent).
#       party:resolve — the ADR-0072 dedup lookup during onboarding (POST /api/v1/parties/resolve).
#
# Deliberately narrow: party.update and party.merge have NO in-repo M2M caller (party.merge is an
# operator-console-only identity-remediation action, ADR-0179; contact-detail edits by a customer
# go through the edge's own customer.* action namespace, a SEPARATE principal/action surface). Add
# a caller here only with matching evidence (a real client method + call site), never speculatively.
allowed_reasons contains "service-edge-party-m2m" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-edge"
	input.action in {
		"party.consent.update",
		"party:resolve",
	}
}

# party.create (POST /api/v1/parties) by a KYC analyst, #10486 batch 3. The endpoint has always
# admitted ROLE_KYC; operator-party-write above covers OPERATOR/ADMIN but not this persona, so
# without this rule the new @Authorize would log a "would DENY" for every KYC-created party.
allowed_reasons contains "kyc-party-create" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	"ROLE_KYC" in input.principal.roles
	input.action == "party.create"
}

# kyb-service's OWN machine principal (client `openbank-kyb`, ROLE_API only), #10486 batch 3:
#   party.create        — the entity (legal-person) party for a business onboarding case
#                         (PartyServiceGateway.createEntityParty -> POST /api/v1/parties).
#   party.mandate.grant — binds each verified representative to that entity
#                         (PartyServiceGateway.grantMandate -> POST /api/v1/parties/{id}/mandates).
# Identity-gated, never a role: ROLE_API is held by every service account. The shared
# `service-account-openbank-services` is deliberately absent. While AUTHZ_ENFORCE=false the
# enforcing half for party.create is requireNamedPartyCreateCaller in PartyCreateCallerGuard.kt
# (PartyCreateCallerGuardTest pins that the two agree).
allowed_reasons contains "service-kyb-party-m2m" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-kyb"
	input.action in {
		"party.create",
		"party.mandate.grant",
	}
}
