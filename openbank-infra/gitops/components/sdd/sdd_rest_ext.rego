# SPDX-License-Identifier: Apache-2.0
# sdd-service REST extension (ADR-0036, ADR-0034 Phase 5 bootstrap — issue #3679).
# Extends openbank.rest with SDD-domain allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# WHY THIS FILE EXISTS. sdd-service is in rules.yaml: money_path_services (it posts a real
# debtor debit through TransactionServiceClient for every authorised collection, #1478), yet it
# shipped with NO OPA policy-decision sidecar and NO bundle of any kind, and its manifest carried
# AUTHZ_ENFORCE=false with the comment "non-money-path services run authz advisory" — a statement
# that was never true of this service. So its ten @Authorize annotations were decorative twice
# over: advisory mode never denies, and with no PDP to ask there was not even a decision to log.
#
# Measured before writing these rules (against the LIVE bundle of a sibling service, since sdd had
# none): of sdd's seven distinct actions, base rest.rego covers exactly two — sdd.list and
# sdd.read, via operator-read-any and the ADR-0223 role_action_matrix. The other five —
# sdd.create, sdd.approve, sdd.update, sdd.delete, sdd.authorise — evaluated to DENY for every
# principal shape (operator, admin, edge M2M, shared backend M2M). Flipping AUTHZ_ENFORCE without
# this file would 403 every mandate write; flipping it without the sidecar would fail closed on
# every endpoint including the reads.
#
# WHY THIS FILE IS NARROW — the trap consent_rest_ext.rego and delegation_rest_ext.rego both
# document. Every backend service authenticates on the shared `openbank-services` Keycloak client
# whose service-account carries ROLE_OPERATOR, and AuthorizeInterceptor classifies every
# client_credentials JWT as HUMAN. A role-only write rule here would therefore hand every service
# in the fleet the ability to register, amend or cancel a direct-debit mandate on any account, and
# to authorise a collection that debits it. So the operator write rule excludes service-account-*
# identities outright, and the customer path is granted to the edge principal only.
#
# Do NOT gate on input.principal.type == "SERVICE": AuthorizeInterceptor never emits it
# (rules.yaml: authz_policy, issue #266) — such a rule is unreachable dead code that would
# silently deny its intended caller the moment enforcement flips.

package openbank.rest

import rego.v1

# Real staff performing a bank-side mandate act through the back office: confirm a B2B mandate,
# suspend/resume/amend one, cancel one, or run a collection authorisation by hand. Excludes every
# service account — see the header note; without that exclusion this rule is a fleet-wide
# mandate-write primitive over the money path.
#
# admin-ui reaches these endpoints through its BFF, which forwards the operator's OWN Keycloak
# session token (not a service account), so this rule is the one that carries the console.
allowed_reasons contains "operator-sdd-write" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	startswith(input.action, "sdd.")
}

# The customer-edge proxying the customer's own direct-debit screens. UpstreamClient authenticates
# with a client_credentials token for the `openbank-edge` client, so principal.id is
# deterministically "service-account-openbank-edge" (not forgeable by a human session, which
# authenticates through a different client).
#
# The edge is the component that enforces ownership, and it does so before every one of these
# calls: CustomerEdgeResource resolves the mandate's accountId from sdd-service and refuses the
# request unless the authenticated party owns that account (`ownsAccount`), so this grant cannot
# reach another customer's mandate even with a guessed id.
#
# Scoped to the exact actions the app has a route for — NOT the `sdd.` family:
#   - sdd.approve is the B2B mandate CONFIRMATION, a bank-side verification act, and the edge
#     exposes no route for it;
#   - sdd.authorise decides whether an inbound collection debits the account. It must never be
#     reachable on a customer-facing principal.
allowed_reasons contains "edge-service-sdd" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-edge"
	input.action in {
		"sdd.create",
		"sdd.read",
		"sdd.list",
		"sdd.update",
		"sdd.delete",
	}
}

# NO RULE FOR sdd.authorise ON ANY M2M CALLER, DELIBERATELY. It is the fail-closed authorisation
# of an inbound collection — the decision that books the debit — and an audit of this repo found
# no caller for it: nothing outside sdd-service references POST /api/v1/sdd/collections/authorise,
# there is no inbound clearing path wired to it yet, and admin-ui's SDD page reads the mandate
# queue only. The same shape as interest-service's accrueAll at its own bootstrap. When the
# clearing-side caller is built, it gets its own identity-scoped rule naming that caller — not a
# family widening here, and not a role-only grant that the shared backend client would inherit.

# Read-only oversight personas, added as the precondition of the AUTHZ_ENFORCE flip (#3679).
#
# WHY THIS IS NOT AN OVER-GRANT, AND WHY THE FLIP NEEDED IT. Both read resources declare
# @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS", "ROLE_API"), so a
# ROLE_VIEWER holder can reach GET /api/v1/sdd/mandates, /mandates/{id} and
# /mandates/{id}/refund-assessment today. Base rest.rego grants that persona nothing:
# operator-read-any needs ROLE_OPERATOR, and compliance-read-any matches only actions ending in
# ".read", so a ROLE_COMPLIANCE analyst is granted sdd.read but NOT sdd.list. Measured with
# `opa eval` against the DEPLOYED sdd-opa-bundle ConfigMap before this rule existed:
#
#   sdd.list  ROLE_VIEWER      -> false      sdd.read  ROLE_VIEWER      -> false
#   sdd.list  ROLE_COMPLIANCE  -> false      sdd.read  ROLE_COMPLIANCE  -> compliance-read-any
#
# The deployed realm (gitops/components/keycloak/realm-template.json) seeds demo@openbank.local
# with ROLE_VIEWER and nothing else, and compliance@/compliance2@ with ROLE_COMPLIANCE+ROLE_VIEWER;
# admin-ui's /sdd page is not role-gated, so it is reachable by all three. Flipping AUTHZ_ENFORCE
# without this rule would 403 every one of them on a read they are entitled to — a regression
# introduced by the security change itself.
#
# READ-ONLY BY CONSTRUCTION, which is what makes a role-only grant acceptable here where the write
# rule above had to exclude service accounts: the action set is a closed literal of the two read
# actions, so no widening of sdd.* can leak through it, and neither role appears on any sdd write
# path. It also grants the shared backend client nothing new — service-account-openbank-services
# holds ROLE_API in the deployed realm (and ROLE_OPERATOR in the docker/CI realms, where base
# operator-read-any already covers both actions), so this rule changes no M2M exposure.
allowed_reasons contains "sdd-oversight-read" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_VIEWER", "ROLE_COMPLIANCE"}
	role in input.principal.roles
	input.action in {"sdd.read", "sdd.list"}
}
