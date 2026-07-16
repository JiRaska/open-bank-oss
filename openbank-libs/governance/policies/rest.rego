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
	data.openbank.agents.allow with input as {
		"agent": input.principal.id,
		"tool": input.action,
		"resource": input.resource,
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

# Operators and admins may list or read any resource on behalf of any party (support-desk
# and onboarding cockpit paths, ADR-0068).
allowed_reasons contains "operator-read-any" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	some verb in {"list", "read"}
	endswith(input.action, sprintf(".%v", [verb]))
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

# ADR-0176 D4: operator-initiated customer messaging (compose a catalogue-template message
# to a customer) gets its own action namespace, opsmessage.*, deliberately NOT under
# notification.* — the edge-service-notification rule above auto-grants every
# notification.*-prefixed action to the customer-edge M2M identity via startswith, so an
# action named notification.compose here would hand the send capability straight to that
# M2M identity. A distinct namespace sidesteps that without touching (or narrowing) the
# existing, correctly-scoped edge-service-notification rule.
#
# This rule does not grant on its own: opsmessage.compose is also listed in rules.yaml's
# four_eyes.actions (see the disjunct in four_eyes_required below), so the interceptor still
# pauses a maker's first call for a second approver before the handler body ever runs — same
# "augments allow, doesn't replace it" shape as the money-path four-eyes rule further down.
allowed_reasons contains "opsmessage-compose" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action == "opsmessage.compose"
}

# The maker's cheap, reversible first step: persist the draft's content (template, reference,
# purpose) BEFORE the four-eyes gate runs. Deliberately a SEPARATE action from
# opsmessage.compose, not a second endpoint reusing that name: four_eyes_required matches
# purely on input.action, so if drafting used "opsmessage.compose" too, creating a draft would
# itself get paused for a second approver — defeating the entire "draft has nothing to approve
# yet, only submitting it does" design. opsmessage.draft is intentionally absent from
# rules.yaml's four_eyes.actions.
allowed_reasons contains "opsmessage-draft" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action == "opsmessage.draft"
}

# The checker side of the same maker-checker pair (ADR-0176 D5). Deciding an approval is a
# single-operator action by design — it is what CONSTITUTES the second pair of eyes, so
# opsmessage.approve/reject are deliberately NOT themselves in four_eyes.actions (gating them
# would need a third approver to approve the approval). Self-approval (the same operator who
# composed the message deciding their own approval) is refused at the application layer
# (SelfApprovalNotAllowedException, mirroring ledger-service's ApprovalStore) — OPA has no
# notion of "who made the original request" to check that here.
allowed_reasons contains "opsmessage-approve" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action in {"opsmessage.approve", "opsmessage.reject"}
}

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
}

# Feature-flag flip (ADR-0067 / issue #419): flipping a money-path flag is four-eyes-gated.
# The target flag key is carried in input.attributes.flag.
four_eyes_required if {
	input.action == "featureflag.flip"
	input.attributes.flag in data.rules.feature_flags.money_path_flags
}

# ADR-0176 D5: extends four-eyes to a non-money-path action by exact name, since
# notification-service can never join money_path_scopes above without dragging the whole
# money_path_services registration fan-out (threat model, 2-approval review, mutation
# testing, coverage floor, SLO pair) onto a service to gate one action — and even then the
# verb-based match would sweep in M2M callers by verb name alone, not by caller identity.
# Disjunctive with the two rules above by ordinary Rego multi-body semantics; independently
# pinned in rest_test.rego per the rules.yaml comment on keeping `verbs` and `actions` disjoint.
four_eyes_required if {
	input.action in data.rules.four_eyes.actions
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
