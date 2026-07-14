# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Agent tool-call authorization (ADR-0031 D2). This is the policy enforcement point in front
# of the MCP /tools/call endpoint: before dispatching a tool, the endpoint queries
#   data.openbank.agents.allow
# with input { agent, tool, resource, plane, attributes }. Deny is the default; an allow
# requires a matching rule below.
#
# Charters are loaded from openbank-libs/governance/agents.yaml as data (data.agents.agents[...]);
# money_path / review come from rules.yaml (data.rules.*). The decision LOGIC here is verified by
# agents_test.rego; what remains for ADR-0031 D8 phase 1 is wiring those YAML bundles into the live
# OPA sidecar (the bundle build), not the policy itself.

package openbank.agents

import rego.v1

# ---------------------------------------------------------------------------------------
# Default deny (ADR-0031: deny-by-default). Everything below must explicitly allow.
# ---------------------------------------------------------------------------------------
default allow := false

# Look up the calling agent's charter (from agents.yaml, loaded as data.agents.agents).
# input.agent is bare on the MCP path (openbank-agent-service sets it directly from its own
# config, e.g. "ui-assistant" -- AgentChatService.ASSISTANT_IDENTITY), but rest.rego's REST
# bridge sources it from the JWT `sub` via AuthorizeInterceptor.principal.id, which for an
# AI_AGENT is prefixed "agent:" (AuthorizeInterceptor.principalType()'s own convention, e.g.
# "agent:onboarding" per its test). trim_prefix is a no-op when the prefix isn't present, so
# this normalises both callers to the bare charter id without needing two lookup rules.
charter contains a if {
	some a in data.agents.agents
	a.id == trim_prefix(input.agent, "agent:")
}

# The hard-forbidden tier is forbidden for ALL agents, regardless of charter (agents.yaml: tool_tiers.deny).
hard_denied if input.tool in data.agents.tool_tiers.deny

# A tool is explicitly denied by the agent's own charter (supports glob like "money.*", "*.write").
charter_denied if {
	some pattern in allowed_or_denied_patterns.deny
	glob_match(pattern, input.tool)
}

# Every tools.allow / tools.deny pattern across the agent's charter(s) -- factored out so
# charter_allowed and rest_action_allowed don't each re-walk `some a in charter` themselves.
#
# SCHEMA NOTE: this only matches charters whose tools.allow/deny are a flat list of pattern
# STRINGS (compliance-officer, ledger-domain-engineer, ui-assistant, rca-investigator,
# customer-copilot). The standalone background/control-plane agents (finops-agent, devops-agent,
# control-liveness-sentinel, governance-auditor, release-steward, docs-truth-agent,
# authz-policy-auditor, flaky-test-hunter) instead declare tools.allow as a list of
# `{tier, resources}` objects -- `some pattern in a.tools.allow` binds `pattern` to the whole
# object for those charters, which glob_match can never match against a string tool name, so
# charter_allowed is always false for them here. This is intentionally NOT a security gap
# (default-deny fails closed, not open) -- it is simply inert: none of those services call the
# MCP /tools/call endpoint this package gates at all (confirmed: no reference to it in any of
# their source trees). Their real, live authorization is the standard `@RolesAllowed` REST
# security on each service's own endpoints. Their `agents.yaml` charter today documents intent
# for governance/audit purposes, not a runtime-enforced grant -- same as every charter's
# `requires_human` block, which no code path reads either. Wiring the tier+resources shape into
# a live decision here is future work if/when these services are ever fronted by the MCP gateway.
allowed_or_denied_patterns := {
	"allow": {pattern |
		some a in charter
		some pattern in a.tools.allow
	},
	"deny": {pattern |
		some a in charter
		some pattern in a.tools.deny
	},
}

# A tool is explicitly allowed by the agent's charter allowlist.
charter_allowed if {
	some pattern in allowed_or_denied_patterns.allow
	glob_match(pattern, input.tool)
}

# rest.rego's "agent-charter-allows" rule reuses this package for a REST call from an
# AI_AGENT principal by setting input.tool := input.action -- the raw REST action string
# an @Authorize-annotated endpoint declares (e.g. "ledger.list", "account.read"). Charters
# declare tools.allow in the MCP tool-tier vocabulary instead (agents.yaml tool_tiers.read,
# e.g. "query.ledger.readonly") -- a different string space. glob_match("query.ledger.readonly",
# "ledger.list") never matches (they're unrelated strings, not even a prefix/suffix
# relationship), so charter_allowed alone could never grant a REST read no matter how
# clearly the charter's tools.allow / data_scope intended it -- the moment ANY service a
# charter reads flips OPA to enforce mode, every AI_AGENT read against it 403s. IMPORTANT:
# rest.rego MUST delegate to this package's `allow` (which also applies hard_denied /
# charter_denied / skill_ok), never to charter_allowed directly -- charter_allowed alone
# skips those checks.
charter_allowed if rest_action_allowed

# rest_domains mirrors openbank-agent-service's own McpToolRegistry.capabilities map -- the
# single place that already decided which REST-action service scopes each `query.*.readonly`
# MCP capability is meant to cover (e.g. query.ledger.readonly also backs the
# account/transaction/balance read tools, not just ledger). Keep the two in sync: a new
# query.<x>.readonly tool, or a domain added to an existing one, needs an entry here too, or
# the fleet regains this exact gap. dispute + complaint reads are now @Authorize-gated
# (dispute.read/.list, complaint.read/.list) so query.disputes.readonly is live; catalog and
# aml still have no @Authorize-gated read endpoint yet -- their entries are forward-looking and
# inert until that service adopts @Authorize on its reads (tracked in issue #401). Where a
# service's only real @Authorize action today is a WRITE (e.g. aml-service's
# amlCase.updateDecision), the verified write-action prefix is listed alongside the bare
# service name, since a future read endpoint may reuse either convention and both are inert
# until then anyway.
rest_domains := {
	"query.ledger.readonly": {"ledger", "account", "transaction", "balance"},
	"query.gl.readonly": {"ledger", "gl"},
	"query.catalog.readonly": {"catalog"},
	"query.compliance.readonly": {"aml", "amlCase", "sanctions"},
	"query.payments.readonly": {"fx", "clearing", "clearingBatch", "sepa-instant", "sctInstPayment"},
	"query.interest.readonly": {"interest"},
	"query.disputes.readonly": {"dispute", "complaint"},
	"query.balance.readonly": {"balance"},
	"query.transaction.readonly": {"transaction"},
}

# Scoped to read verbs only -- a `*.readonly` tool must never bridge into a write action,
# however permissive matching the domain prefix alone would otherwise be.
rest_read_verbs := {"list", "read", "search"}

# A charter tool pattern authorizes a REST action iff the action's service-scope prefix is
# in that tool's mapped rest_domains set AND the verb is read-only.
rest_action_allowed if {
	some pattern in allowed_or_denied_patterns.allow
	some scope in rest_domains[pattern]
	startswith(input.tool, sprintf("%s.", [scope]))
	some verb in rest_read_verbs
	endswith(input.tool, sprintf(".%s", [verb]))
}

# run.skill is further constrained to the agent's `skills` allowlist (development plane).
skill_ok if {
	input.tool != "run.skill"
} else if {
	some a in charter
	input.attributes.skill in a.skills
}

# ---------------------------------------------------------------------------------------
# The decision. Allow only when nothing forbids AND the charter permits AND skill is in scope.
# ---------------------------------------------------------------------------------------
allow if {
	not hard_denied
	not charter_denied
	charter_allowed
	skill_ok
}

# ---------------------------------------------------------------------------------------
# Decision object the MCP endpoint records into AuditEvent.payload.policy_decision (ADR-0031 D5).
# Always emitted so a DENY is auditable, not silent.
# ---------------------------------------------------------------------------------------
decision := {
	"allow": allow,
	"agent": input.agent,
	"tool": input.tool,
	"resource": input.resource,
	"reason": reason,
}

reason := "hard-denied tool tier" if hard_denied

else := "denied by charter" if charter_denied

else := "skill not in agent allowlist" if not skill_ok

else := "no matching allow rule" if not charter_allowed

else := "allowed by charter"

# ---------------------------------------------------------------------------------------
# Helper: simple glob match supporting a single trailing/embedded "*" (e.g. "money.*", "*.write").
# Replace with the OPA built-in `glob.match` when the bundle build is wired.
# ---------------------------------------------------------------------------------------
glob_match(pattern, value) if pattern == value
glob_match(pattern, value) if glob.match(pattern, ["."], value)
