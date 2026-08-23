# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Case-workflow collaboration authorization (ADR-0271). This policy is embedded only in
# the case-coordinator OPA bundle. It deliberately does not extend the shared agents.rego
# MCP/REST policy, so a case-policy edit cannot roll every policy-bearing service.

package openbank.case_collaboration

import rego.v1

default allow := false

normalized_agent := trim_prefix(input.agent, "agent:")

charter_capability if {
	some charter in data.case_collaboration.agents
	charter.id == normalized_agent
	input.capability in charter.case_capabilities
}

matching_grants contains grant if {
	some grant in data.case_collaboration.grants
	grant.enabled == true
	grant.agent_id == normalized_agent
	input.capability in grant.capabilities
	grant.case_class == input.case_class
	grant.delivery_mode == input.delivery_mode
}

allow if {
	charter_capability
	count(matching_grants) == 1
}

reason := "agent capability absent from charter" if not charter_capability

else := "no unique enabled rules matrix grant" if count(matching_grants) != 1

else := "allowed by charter and rules matrix"

selected_grant := grant if {
	allow
	some grant in matching_grants
}

else := {}

decision := {
	"allow": allow,
	"agent": normalized_agent,
	"capability": input.capability,
	"case_class": input.case_class,
	"delivery_mode": input.delivery_mode,
	"reason": reason,
	"rollout_id": object.get(selected_grant, "rollout_id", ""),
	"max_signals_per_case": object.get(selected_grant, "max_signals_per_case", 0),
}
