# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# REST endpoint authorization (ADR-0034 D1). This is the policy enforcement point that
# AuthorizeInterceptor in openbank-libs queries before letting a @Authorize-annotated
# JAX-RS method proceed:
#   data.openbank.rest.allow
# with input { principal, action, resource?, attributes }. Deny is the default; an
# allow requires a matching rule below.
#
# Co-located with openbank.agents (MCP /tools/call gate, ADR-0031) so a SINGLE OPA
# sidecar serves both — the whole point of ADR-0034. Shared predicates (money_path,
# value_band, four_eyes_required) live in openbank.shared so we never fork between
# the human-actor and AI-actor planes.
#
# Charters / rules are loaded from openbank-libs/governance/ as data:
#   - data.agents.*  ← agents.yaml (ADR-0031)
#   - data.rules.*   ← rules.yaml  (money_path_services, value_bands, four_eyes)

package openbank.rest

import rego.v1

# ---------------------------------------------------------------------------------------
# Default deny (ADR-0034 D1: deny-by-default mirrors the agents.rego stance).
# Every allow below must be triggered by an explicit rule; absence of a matching
# rule yields {} which the Kotlin client maps to AuthzDecision(allow=false).
# ---------------------------------------------------------------------------------------
default allow := false

# ---------------------------------------------------------------------------------------
# allow as a structured object — the OpaSidecarPolicyDecisionPoint accepts either a
# bare boolean (legacy) or this richer shape (preferred). reason and policy_version
# are forwarded into AuditEvent so a post-incident SQL query like
#   SELECT count(*) WHERE decision_reason ILIKE 'four-eyes%'
# is trivially answerable.
# ---------------------------------------------------------------------------------------
allow := {
	"allow": true,
	"reason": reason,
	"policy_version": policy_version,
	"attributes": response_attributes,
} if {
	count(allowed_reasons) > 0

	# Defense-in-depth: a prohibited action (e.g. flipping off SCA/sanctions via a feature
	# flag, issue #419) can never be granted by ANY reason — not even operator-on-own-tenant
	# with a tenant-matched resource. Gating the allow head, not each reason, makes this
	# impossible to bypass by enriching the input (the prohibition is not just surfaced).
	not prohibited

	# Pick the lexicographically smallest reason so the complete rule produces a single
	# deterministic output even when multiple allowed_reasons rules fire simultaneously
	# (e.g. operator-on-own-tenant + operator-read-any for a tenant-scoped read).
	reason := min(allowed_reasons)
}

# Surfaced on the allow object so OpaSidecarPolicyDecisionPoint (which reads
# `result.attributes` generically) actually delivers it to AuthzDecision.attributes.
# A fleet audit (issue #395) found four_eyes_required was computed below but never
# reached this object — "attributes" was simply absent from the allow head — so no
# caller anywhere could ever have acted on it, independent of any money_path_scopes
# naming mismatch. Sparse on purpose: omitted (not `false`) when not required, matching
# this file's existing audit-attribute style (cf. default policy_version above).
default response_attributes := {}

response_attributes := {"four_eyes_required": true} if four_eyes_required

# policy_version is audit METADATA, never a gate. Resolve it via a defaulted rule so
# a bundle that omits openbank.bundle.version cannot turn a legitimate allow into an
# (undefined object head → default) DENY: referencing an undefined value inside the
# allow object literal would make the whole head undefined and silently fail closed.
# (object.get(data.openbank, ...) would self-reference this package → rego_recursion;
# the narrow path below does not.)
default policy_version := "unknown"

policy_version := data.openbank.bundle.version

# ---------------------------------------------------------------------------------------
# Reasons — each rule that grants access publishes its name here so the audit trail
# tells us WHICH rule fired, not just "allow=true".
# ---------------------------------------------------------------------------------------
allowed_reasons contains "operator-on-own-tenant" if {
	input.principal.type == "HUMAN"
	"ROLE_OPERATOR" in input.principal.roles

	# Resource-scoped actions must target the operator's tenant; non-scoped
	# (system-wide) actions are not granted by this rule.
	input.resource
	input.principal.attributes.tenant == input.resource.attributes.tenant
}

allowed_reasons contains "compliance-read-any" if {
	input.principal.type == "HUMAN"
	"ROLE_COMPLIANCE" in input.principal.roles
	endswith(input.action, ".read")
}

# AI agents go through this same query when the agent's tool wraps a REST call
# (ADR-0031 D5). The charter check is delegated to agents.rego so we don't
# duplicate the charter logic — call it through the unified package boundary.
allowed_reasons contains "agent-charter-allows" if {
	input.principal.type == "AI_AGENT"

	# Translate the REST input into the MCP input shape and reuse agents.allow --
	# NOT charter_allowed: agents.allow ALSO applies hard_denied / charter_denied /
	# skill_ok, none of which charter_allowed alone consults. Calling charter_allowed
	# directly would let a fleet-wide hard-denied tool tier, or an agent's own
	# tools.deny glob, silently reach a REST action anyway.
	# object.get (not input.resource): the PDP omits `resource` from the query entirely when
	# it is null (OpaSidecarPolicyDecisionPoint.toInput only puts it when non-null), and a bare
	# `input.resource` reference is then UNDEFINED, which makes this whole object — and the
	# agents.allow call — undefined, silently denying every resource-less AI_AGENT REST call.
	# The MCP server (openbank-mcp-service) is the first caller to route AI_AGENT through
	# rest.allow with no resource; agent-service queries agents.allow directly and never hit this.
	# attributes MUST be forwarded (ADR-0195 step 5, #3292): the MCP endpoint sends
	# {tool, consentId} on every tools/call, and agents.rego's skill_ok else-branch reads
	# input.attributes.skill — a bridge that drops them makes consent-state gating impossible
	# and denies a chartered run.skill that arrives via REST. Same object.get pattern as
	# resource: a caller that sends no attributes key must not make the query undefined.
	data.openbank.agents.allow with input as {
		"agent": input.principal.id,
		"tool": input.action,
		"resource": object.get(input, "resource", null),
		"attributes": object.get(input, "attributes", {}),
	}
}

# A party (HUMAN whose JWT `sub` is a partyId) may list or read its OWN resources.
# The resource.id carries the path-parameter value extracted by AuthorizeInterceptor
# (e.g. partyId for device.list); principal.id is the JWT `sub` claim — in the customer
# realm (ADR-0065/0066) the sub IS the partyId, making the equality check sufficient.
# Scoped to read-family verbs so self-service can never mutate another party's data.
allowed_reasons contains "party-self-service" if {
	input.principal.type == "HUMAN"
	input.resource
	input.principal.id == input.resource.id
	some verb in {"list", "read"}
	endswith(input.action, sprintf(".%v", [verb]))
}

# ---------------------------------------------------------------------------------------
# ADR-0223 D2 phase 1 (shadow): the role->action matrix as data (rules.yaml: authz.
# role_action_matrix), granting ALONGSIDE the legacy reasons. The seed is derived as a
# strict subset of today's effective grants (operator-read-any's and compliance-read-any's
# read verbs, verbatim), so this rule changes no decision; what it changes is the
# decision_reason mix, which the D2(b) retirement triage reads to find where the matrix
# already carries the call. A role absent from the matrix yields an undefined lookup and
# the rule simply does not fire — fail-closed by construction.
# ---------------------------------------------------------------------------------------
allowed_reasons contains "matrix-allows" if {
	input.principal.type == "HUMAN"
	some role in input.principal.roles
	matrix_grants(input.action, role)
}

# Grants for a role = its own grant list plus ONE level of inheritance (e.g. ROLE_ADMIN
# inherits ROLE_OPERATOR). Single level deliberately: rego forbids recursion, and one hop
# covers the ADMIN-as-operator-superset case without a walkable chain to reason about.
matrix_grants(action, role) if {
	data.rules.authz.role_action_matrix[role].grant[_] == action
}

matrix_grants(action, role) if {
	parent := data.rules.authz.role_action_matrix[role].inherits
	data.rules.authz.role_action_matrix[parent].grant[_] == action
}

# Operators and admins may list or read any resource on behalf of any party (support-desk
# and onboarding cockpit paths, ADR-0068).
allowed_reasons contains "operator-read-any" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	some verb in {"list", "read"}
	endswith(input.action, sprintf(".%v", [verb]))
}

# ADR-0224 D2: staff agent-session lifecycle (issue/read/revoke). Operator/admin only — sessions
# are issued to the authenticated operator themselves (subject = principal), and revocation is
# live-checked by the OBO resolver, so this grant only ever reaches the caller's own sessions.
#
# The `service-account-` exclusion is what makes "operator only" true (issue #3765). Without it the
# rule reads as staff-only and is not: Keycloak client_credentials tokens never produce
# principal.type == SERVICE (rules.yaml: authz_policy.principal_type_service_unreachable), and the
# shared backend client's service-account-openbank-services carries ROLE_OPERATOR in the realm — so
# HUMAN + operator-role alone admits the fleet's M2M identity to minting, binding and revoking MCP
# sessions. Same idiom and same reason as operator-compose-message directly below.
#
# Nothing legitimate is cut: the only consumer of these endpoints is admin-ui's OBO relay
# (src/app/api/agent/obo-mcp/route.ts), which exchanges a token for a signed-in operator, so
# "mcp-service sees a HUMAN principal whose realm roles were bounded at issuance" — a real person,
# never a service account.
allowed_reasons contains "operator-mcp-session" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	not startswith(input.principal.id, "service-account-")
	startswith(input.action, "mcp.session.")
}

# ADR-0176 D4: operator-initiated customer messaging is its own action namespace
# (opsmessage.*), deliberately NOT notification.* — the edge-service-notification rule below
# grants every notification.* action to customer-edge's M2M identity via a plain prefix match,
# and opsmessage.compose must never be reachable that way. A namespace split alone is not
# sufficient, though: service-account-openbank-edge is classified HUMAN (Keycloak
# client_credentials tokens never produce principal.type == SERVICE — see
# authz_policy.principal_type_service_unreachable in rules.yaml) and carries ROLE_OPERATOR in
# the realm (openbank-infra/gitops/components/keycloak/realm-template.json), so a rule gated on
# HUMAN + ROLE_OPERATOR alone would silently re-admit that exact M2M identity. The explicit
# `service-account-` exclusion below is what actually keeps it out — same idiom as
# m2m-sanctions-screening further down, used there for the opposite purpose (identifying M2M,
# not excluding it).
allowed_reasons contains "operator-compose-message" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	not startswith(input.principal.id, "service-account-")
	input.action == "opsmessage.compose"
}

# Checker side of the same four-eyes flow (ADR-0155 mechanism, ADR-0176 D5 trigger). Deciding
# an approval is exactly as sensitive as issuing the request it gates, so it gets the identical
# HUMAN + operator-role + non-service-account shape, not the broader operator-write-any this
# service otherwise has no equivalent of.
allowed_reasons contains "operator-decide-message-approval" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	not startswith(input.principal.id, "service-account-")
	input.action == "opsmessage.approval.decide"
}

# Authenticated customers may perform any `customer.*` action (initiate payments, enroll
# devices, register, etc.). The JWT `sub` equals the partyId in the customer realm
# (ADR-0065/0066). Per-handler IDOR guards (e.g. debtorAccountId ownership in
# customer-edge) enforce resource-level isolation; OPA does not re-derive it here because
# the resource parameter is not present on every customer action (many are non-scoped).
# This rule intentionally does NOT cover operator or admin actions — those go through
# operator-write or operator-read-any above.
allowed_reasons contains "customer-self-action" if {
	input.principal.type == "HUMAN"
	startswith(input.action, "customer.")
}

# The customer-edge's M2M identity calling notification-, sca- or document-service on a
# customer's behalf. The edge authenticates the CUSTOMER itself (customer-self-action above
# + per-handler IDOR guards) and injects the authoritative partyId the downstream handlers
# scope by — so this check only needs to recognise the edge principal.
#
# The `document.` family covers the onboarding framework agreement (ADR-0169/0170): the
# app reads the PDF bytes and signs them through CustomerDocumentResource, which fetches
# the metadata first and 404s a non-owner before ever streaming content — the per-handler
# IDOR guard this rule relies on. `document.read` (metadata) previously slipped through
# operator-read-any by accident of its name while `document.readContent` (the bytes) fell
# outside that rule's {list, read} verb set and was denied, so the sign screen rendered
# "getDocumentContent failed: 403" with no way to complete onboarding. Granting the family
# to the edge identity — rather than widening operator-read-any's verb taxonomy — keeps the
# reach on the edge principal instead of handing every ROLE_OPERATOR staff session the
# contents of any customer's signed agreement.
#
# NOTE (found during ADR-0034 Phase 5 rollout, issue #266): AuthorizeInterceptor never
# produces principal.type == "SERVICE" — M2M calls authenticate with a Keycloak
# client_credentials JWT (openbank-edge), which the interceptor's principalType()
# classifies as HUMAN (see AuthorizeInterceptor.kt), and the realm never issues a
# ROLE_SERVICE role to any client — only ROLE_OPERATOR. A SERVICE-gated rule is therefore
# structurally unreachable dead code.
#
# Gating on HUMAN + ROLE_OPERATOR alone is NOT safe here: real operator/admin staff also
# carry ROLE_OPERATOR, and this rule's action families include device.enroll (SCA-service's
# WebAuthn device registration) — granting that to any staff member is an account-takeover
# primitive (an operator could enrol their own authenticator against a victim's account).
# test_deny_operator_read_any_does_not_cover_write encodes exactly this invariant.
#
# Instead, match the edge's client_credentials principal precisely by identity:
# AuthorizeInterceptor sets principal.id from the JWT's preferred_username, which for a
# Keycloak service-account token is deterministically "service-account-<clientId>"
# (verified against a live token from the openbank-edge client, ADR-0065/0034 issue #266)
# — not forgeable by a human session, which authenticates through a different client.
#
# `signatureCeremony.` is here for the same reason `document.` is (#1249): the onboarding
# agreement is read AND signed through the edge, and recordDecision's verb sits outside
# operator-read-any's {list, read} set, so signing 403'd while reading the ceremony passed.
# It is not an account-takeover primitive the way device.enroll is: document-service verifies
# the signer's evidenceRef against sca-service (SignerVerificationPort, ADR-0021), the edge
# forces partyRef from the caller's token and rejects a caller who is not one of the
# ceremony's signers, so this grant cannot sign on someone else's behalf.
allowed_reasons contains "edge-service-notification" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-edge"
	some family in {"notification.", "device.", "document.", "signatureCeremony."}
	startswith(input.action, family)
}

# The customer-edge proxying the customer's own PSD2 consent screen (ADR-0126): the app
# lists who may access its account data and revokes a consent. Same edge principal, same
# guard as edge-service-notification — the edge injects the caller's authoritative partyId
# from the JWT (consent-service keys listByParty by that path partyId), and revoke is
# ownership-enforced downstream (ConsentService throws ConsentNotOwnedByPartyException when
# consent.partyId != command.partyId), so the edge can never read or revoke another party's
# consent even with a guessed id. Scoped to the two exact actions the app uses — NOT the
# `consent.` family — so this grant cannot create, activate, or validate consents on the
# edge principal (least privilege; the edge exposes no route for those anyway).
allowed_reasons contains "edge-service-consent" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-edge"
	input.action in {"consent.list", "consent.revoke"}
}

# The customer-edge proxying the app's in-app engagement surfaces (ADR-0220 D1): the app
# resolves what to show in a slot and records the customer's reaction to it. Same edge
# principal and same guard as edge-service-consent — the edge injects the caller's
# authoritative partyId from the JWT, so a client-supplied partyId never reaches
# engagement-service on its own authority. Scoped to the two exact actions the app uses —
# NOT an `engagement.` family — least privilege, matching edge-service-consent's shape.
allowed_reasons contains "edge-service-engagement" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-edge"
	input.action in {"engagement.surface.read", "engagement.surface.recordEvent"}
}

# The customer-edge proxying the app's privacy centre: a customer reads their OWN access log
# (P2-27). Same edge principal and same guard as edge-service-consent — the edge injects the
# caller's authoritative partyId from the JWT, so a client-supplied id never reaches
# audit-service.
#
# Deliberately a DISTINCT action from `audit.read`, not the `audit.` family: `audit.read` is the
# auditor/compliance surface over the whole trail (GET /entries/{aggregateId},
# /entries/by-actor/{actorId}) and granting it to the edge principal would put regulated evidence
# behind a service account. `audit.customerRead` reaches only the metadata projection.
#
# This rule is what actually narrows the endpoint. Its @RolesAllowed had to widen to
# ROLE_API/ROLE_OPERATOR/ROLE_ADMIN — the edge's service account carries ROLE_OPERATOR, so
# @RolesAllowed(ROLE_API) alone 403'd every call before the PDP was consulted (same shape as
# document-service's SignatureCeremonyResource) — and ROLE_OPERATOR is held by real staff too.
# The identity match below is therefore the load-bearing half: staff sessions authenticate
# through a different client, so their preferred_username is never "service-account-*".
allowed_reasons contains "edge-service-audit-customer" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-edge"
	input.action == "audit.customerRead"
}

# Any M2M service-account caller (e.g. account-service) may trigger a sanctions screen
# (issue #746, found via issue #669's load benchmark). sanctions.create is called with
# resource = "" — a resourceless system action — so operator-on-own-tenant (which requires
# input.resource) can never fire for it, and no other rule covers a resourceless write.
# Confirmed live: a client_credentials token from openbank-services (ROLE_OPERATOR, the same
# identity account-service uses to call ledger/balance) got 403 policy-denied calling
# sanctions.create directly, once AUTHZ_ENFORCE=true.
#
# Deliberately action-scoped to sanctions.create ONLY, not a "sanctions." family prefix:
# sanctions.clear (renamed from sanctions.review, issue #938 follow-up — dismiss/confirm a
# hit) and the list-refresh actions are real operator judgment calls, not a service-to-service
# compliance check, and must stay behind a human session — widening this to the family would
# let any M2M caller self-clear a sanctions hit.
#
# Deliberately NOT scoped to one hardcoded client id (unlike edge-service-notification):
# sanctions.create is "run a compliance check" — not an account-takeover primitive like
# device.enroll — and more than one service legitimately screens entities (account-service
# today; kyc/onboarding/party are plausible future callers). Gating on the
# "service-account-*" preferred_username prefix (deterministic for any Keycloak
# client_credentials token, per the edge-service-notification note above — not forgeable by
# a human password-grant session, whose preferred_username is the human's own username)
# covers any M2M caller without a per-caller rule, while still excluding real operator/admin
# staff sessions from this specific carve-out.
allowed_reasons contains "m2m-sanctions-screening" if {
	input.principal.type == "HUMAN"
	startswith(input.principal.id, "service-account-")
	input.action == "sanctions.create"
}

# NOTE: sanctions.create is not the only resourceless M2M `*.create`-shaped action with
# this gap — ledger.create, fx.convert (renamed from fx.create, issue #938 follow-up),
# lending.create, settlement.create, and others share the same shape and have the same
# live "advisory: would DENY" masked-by-AUTHZ_ENFORCE=false exposure. Each needs its own
# case-by-case rule (some may not be safe for any M2M caller the way sanctions.create is)
# — tracked separately as issue #750, not folded in here.

# ---------------------------------------------------------------------------------------
# Money-path actions get the four-eyes gate from rules.yaml. This rule does NOT grant
# access on its own — it AUGMENTS allow with a flag the interceptor surfaces back to
# the caller via AuthzDecision.attributes, so the handler can require a second approver.
# ---------------------------------------------------------------------------------------
# Money-path service scopes, normalised to the action namespace: money_path_services in
# rules.yaml uses the module name (openbank-ledger-service) but an action prefix is the
# commit scope (ledger). Strip the `openbank-` prefix and a trailing `-service` so the
# two align (e.g. openbank-ledger-service -> ledger).
#
# Five services' real @Authorize action prefix does NOT match that derived name (a
# fleet audit, issue #395, found this silently disabled four-eyes for every one of
# them): sepa-payment -> sepaPayment, sepa-instant -> sctInstPayment, domestic-payment
# -> domesticPayment, clearing -> clearingBatch, sca -> device / scaChallenge. Two of
# those aren't even a casing variant of the derived name, so this uses an explicit
# override table (rules.yaml: money_path_action_prefixes) rather than a camelCase
# guess — self-documenting, and rest_test.rego pins it so a future rename can't
# silently drift back out of sync.
money_path_scopes contains scope if {
	some svc in data.rules.money_path_services
	derived := trim_suffix(trim_prefix(svc, "openbank-"), "-service")
	not data.rules.money_path_action_prefixes[derived]
	scope := derived
}

money_path_scopes contains scope if {
	some svc in data.rules.money_path_services
	derived := trim_suffix(trim_prefix(svc, "openbank-"), "-service")
	some scope in data.rules.money_path_action_prefixes[derived]
}

four_eyes_required if {
	some scope in money_path_scopes
	startswith(input.action, sprintf("%s.", [scope]))
	some verb in data.rules.four_eyes.verbs
	endswith(input.action, sprintf(".%s", [verb]))
	not four_eyes_exempt
}

# Feature-flag flip (ADR-0067 / issue #419): flipping a money-path flag is four-eyes-gated.
# The target flag key is carried in input.attributes.flag.
four_eyes_required if {
	input.action == "featureflag.flip"
	input.attributes.flag in data.rules.feature_flags.money_path_flags
}

# Exact-action four-eyes, independent of money-path scope (ADR-0176 D5). Generalises the
# featureflag.flip clause above into a reusable list (data.rules.four_eyes.actions) rather than
# a one-off inline check — for an action whose service will never appear in
# money_path_services (opsmessage.compose today), the verb-based clause above can never match,
# since it requires deriving a scope FROM money_path_services first. Undefined, not an error,
# for any bundle whose rules.yaml predates this key — Rego membership over an undefined
# collection simply does not fire.
four_eyes_required if {
	input.action in data.rules.four_eyes.actions
	not four_eyes_exempt
}

# Caller-aware exemption (ADR-0280, issue #8360): four_eyes_required is computed by action name
# alone, so an action with a verified M2M caller used to be UNGATEABLE without pausing that
# automation — the sca-service stalemate. data.rules.four_eyes.exemptions maps an exact action
# name to the principal ids of its verified automation callers; for those identities the flag
# does not fire, while every other caller (the human ops-console path) is still flagged.
# Undefined — not an error — for any bundle whose rules.yaml predates the key, and for any
# action with no entry: membership over an undefined collection does not fire, so `not
# four_eyes_exempt` holds and the clauses above behave exactly as before.
four_eyes_exempt if {
	input.principal.id in data.rules.four_eyes.exemptions[input.action]
}

# ---------------------------------------------------------------------------------------
# Prohibited feature-flag flips (ADR-0067 / issue #419). A flip that would DISABLE a
# regulatory safety control (SCA, sanctions/AML screening, the fail-closed payment gate)
# is forbidden outright — no four-eyes approval can switch these off via a flag. Surfaced
# to the interceptor (like four_eyes_required); no allow reason ever fires for such an
# action, so default-deny also blocks it.
# ---------------------------------------------------------------------------------------
prohibited if {
	input.action == "featureflag.flip"
	input.attributes.flag in data.rules.feature_flags.prohibited_flag_combinations
}

# ---------------------------------------------------------------------------------------
# The shared M2M identity may never reach a WRITE through a role-only operator reason.
#
# Nearly every backend service authenticates with one Keycloak client, `openbank-services`,
# whose service-account carries ROLE_OPERATOR in the realm (openbank-realm.json). Combined
# with AuthorizeInterceptor classifying every client_credentials JWT as HUMAN (see
# rules.yaml: dependencies.principal_type_service_unreachable), that means every
# `operator-<domain>-write` rule — which checks only type == HUMAN plus the role — admitted
# ANY service holding those credentials to ANY write action in that domain, for any
# resource. Found on consent-service, where the rule's own comment claimed
# consent.grant/consent.revoke were unreachable by M2M callers while the role check made
# them reachable; the same shape exists on ~18 other services.
#
# Gated at the `allow` head rather than fixed in each service's rule, deliberately and for
# the reason the `prohibited` block above already states: a per-rule exclusion has to be
# remembered 19 times and again by whoever writes the 20th, and forgetting it fails OPEN.
# Here it cannot be bypassed by adding a new reason.
#
# What this does NOT block, by design:
#  - READS. `operator-read-any` / `compliance-read-any` are how real M2M callers fetch
#    cross-service data today (party-service's GDPR Art. 15 aggregation against kyc-service
#    and card-issuance-service depends on exactly that), so they are untouched.
#  - Writes granted by a reason that names the caller. A rule that identifies a specific
#    verified caller by `input.principal.id` and scopes the action set — e.g.
#    `service-consent-m2m-marketing`, `m2m-sanctions-screening`,
#    `service-sca-shared-client-m2m` — still fires, because the check below requires that
#    EVERY reason admitting this principal be a role-only write reason. One identity-scoped
#    reason is enough to allow the call. That is the sanctioned way to grant an M2M write:
#    name the caller and enumerate the actions.
#  - Human operators and admins. This is keyed on one service-account identity string, and
#    no human user can hold it.
#
# The structural fix is a per-service Keycloak client so `principal.id` alone identifies the
# caller and this guard becomes unnecessary; that is a standalone workload-identity project.
# Until then this is the fleet-wide floor.
# ---------------------------------------------------------------------------------------
shared_m2m_identity if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-services"
}

# Which role-only write reasons this prohibition applies to — DATA, not a name pattern.
#
# An earlier revision matched every reason named `operator-*-write`. Review found that would
# have 403'd `transaction.create` from six live callers (account-service's welcome-bonus
# credit, sepa-instant, domestic-payment, swift, interest, sdd) and `settlement.create` —
# both on AUTHZ_ENFORCE=true money paths, because those services have NO identity-scoped rule
# for the shared client to fall back on. transaction-service's own rego says so: "RESIDUAL
# RISK — shared identity, no per-caller narrowing".
#
# So the set is opt-in and evidence-based: a reason is listed only once its service carries a
# rule that names the caller and enumerates the actions, so denying the role-only path removes
# an over-grant instead of removing the only path. Deny-where-an-alternative-exists, never
# deny-and-hope. rules.yaml: shared_m2m_write_prohibition.reasons is the register; the rest
# are tracked there with the work each needs.
#
# Membership over an undefined collection simply does not fire, so a bundle whose rules.yaml
# predates this key sees no behaviour change.
role_only_write_reason(r) if {
	r in data.rules.shared_m2m_write_prohibition.reasons
}

prohibited if {
	shared_m2m_identity
	count(allowed_reasons) > 0
	every r in allowed_reasons {
		role_only_write_reason(r)
	}
}
