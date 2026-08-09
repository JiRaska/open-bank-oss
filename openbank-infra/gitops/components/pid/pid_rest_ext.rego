# SPDX-License-Identifier: Apache-2.0
# Pid-service REST extension (ADR-0034, ADR-0094, ADR-0072).
# Extends openbank.rest with identity-domain allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated:
#   identity.eudi.issue   — EudiCredentialIssuerResource.issue
#   identity.eudi.revoke  — EudiCredentialIssuerResource.revoke
#   identity.eudi.request — EudiOpenId4VpResource.request
#   identity.eudi.poll    — EudiOpenId4VpResource.poll
#   identity.eudi.verify  — EudiPresentationResource.verify
#   identity.case.list    — VerificationCaseResource.list
#   identity.case.get     — VerificationCaseResource.get
#   identity.case.decide  — VerificationCaseResource.decide (four-eyes in handler)
#   identity.case.reopen  — VerificationCaseResource.reopen
#   identity.register     — PartyResource.register
#   identity.resolve      — PartyResource.resolve
#   identity.link         — PartyResource.link
#   pid.resolve           — PartyResource.resolvePid
#   party.changeStatus    — PartyResource.changeStatus

package openbank.rest

import rego.v1

# Operators and admins may perform any identity.* write operation on the pid-service.
# ROLE_OPERATOR covers onboarding cockpit staff; ROLE_ADMIN covers platform administrators.
# The specific four-eyes enforcement for identity.case.decide is inside the handler.
allowed_reasons contains "operator-identity-write" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	startswith(input.action, "identity.")
}

# Operators and admins may look up a party by a pre-computed RČ blind index (pid.resolve).
#
# RENAMED from `operator-pid-resolve` in #4228. It is a READ, and the old name hid that: it is
# `@GET /api/v1/parties/pid/resolve` (PartyResource.resolveByIndex), which takes an `index` query
# parameter and returns `{partyId}` or 404 — no state is written on any path. The
# check-operator-write-naming guard classifies by name, and `-read` is the one naming convention
# this fleet actually follows, so the honest fix here is the name, not an exclusion: the rule
# never was one of the role-only WRITES that guard exists to surface.
#
# Deliberately NOT given the `not startswith(input.principal.id, "service-account-")` treatment
# its two sibling entries got. It grants nothing a service-account could not already do — the
# endpoint is `@RolesAllowed(Roles.API)`, so the RBAC gate in front of OPA already requires
# ROLE_API, and no caller anywhere in the fleet invokes this path today (audited #4228).
allowed_reasons contains "operator-pid-resolve-read" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action == "pid.resolve"
}

# Operators and admins may change party status (party.changeStatus) — onboarding state machine.
#
# HUMANS ONLY (GHSA-58jq-9hq3-66jr, issue #4228). This IS a write: `@PATCH
# /api/v1/parties/{id}/status` (PartyResource.changeStatus). Without the exclusion the rule was
# role-only, and `service-account-openbank-services` — the identity nearly every backend service
# authenticates as — carries ROLE_OPERATOR in the docker and CI realms and is classified HUMAN by
# AuthorizeInterceptor, so it reached a party lifecycle write. pid-service runs
# AUTHZ_ENFORCE=true, so this was live, not latent.
#
# The exclusion strands no caller, established two ways rather than by the shape of the rule:
# (1) the endpoint is `@RolesAllowed(Roles.ADMIN)`, and no service-account holds ROLE_ADMIN in
# ANY of the three realm JSONs in this tree (deployed template: edge=ROLE_OPERATOR,
# services=ROLE_API; docker: services=ROLE_OPERATOR+ROLE_API; CI: services=ROLE_OPERATOR+
# ROLE_COMPLIANCE) — so the RBAC gate in front of OPA already refused every M2M caller; and
# (2) no service in the fleet calls this path — the only cross-service traffic to pid-service is
# POST /parties/{id}/external-ids (identity.link) from customer-edge.
allowed_reasons contains "operator-party-status" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action == "party.changeStatus"
}

# Authenticated customers may request and poll their own EUDI credential presentation
# (OpenID4VP relying-party flow, ADR-0094). The resource.id (partyId) is checked against
# principal.id in the handler; OPA grants the action class.
allowed_reasons contains "customer-eudi-request" if {
	input.principal.type == "HUMAN"
	input.principal.id != ""
	input.action in {"identity.eudi.request", "identity.eudi.poll", "identity.eudi.verify"}
}
