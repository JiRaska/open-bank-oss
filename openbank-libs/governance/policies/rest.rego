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

# The customer-edge's M2M identity (ROLE_SERVICE) calling notification-service on a
# customer's behalf. The edge authenticates the CUSTOMER itself (customer-self-action
# above + per-handler IDOR guards) and injects the authoritative partyId query param the
# downstream handlers scope by — so this check only needs to recognise the edge principal.
# Deliberately narrow: just the notification/device families notification-service exposes;
# a blanket SERVICE allow would open every @Authorize endpoint to any M2M client.
allowed_reasons contains "edge-service-notification" if {
	input.principal.type == "SERVICE"
	"ROLE_SERVICE" in input.principal.roles
	some family in {"notification.", "device."}
	startswith(input.action, family)
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
