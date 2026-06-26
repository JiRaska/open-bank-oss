# SPDX-License-Identifier: MPL-2.0
# Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
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
charter contains a if {
	some a in data.agents.agents
	a.id == input.agent
}

# The hard-forbidden tier is forbidden for ALL agents, regardless of charter (agents.yaml: tool_tiers.deny).
hard_denied if input.tool in data.agents.tool_tiers.deny

# A tool is explicitly denied by the agent's own charter (supports glob like "money.*", "*.write").
charter_denied if {
	some a in charter
	some pattern in a.tools.deny
	glob_match(pattern, input.tool)
}

# A tool is explicitly allowed by the agent's charter allowlist.
charter_allowed if {
	some a in charter
	some pattern in a.tools.allow
	glob_match(pattern, input.tool)
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
