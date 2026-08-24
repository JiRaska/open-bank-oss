# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package openbank.case_collaboration_test

import data.openbank.case_collaboration
import rego.v1

pilot_data := {
	"agents": [{"id": "rca-investigator", "case_capabilities": ["case.join", "case.contribute"]}],
	"grants": [{
		"agent_id": "rca-investigator",
		"capabilities": ["case.join", "case.contribute"],
		"case_class": "INCIDENT_RESPONSE",
		"delivery_mode": "SHADOW",
		"enabled": true,
		"rollout_id": "test-shadow-rollout",
		"max_signals_per_case": 8,
	}],
}

pilot_input := {
	"agent": "rca-investigator",
	"capability": "case.contribute",
	"case_class": "INCIDENT_RESPONSE",
	"delivery_mode": "SHADOW",
}

test_allow_requires_charter_and_unique_matrix_grant if {
	d := case_collaboration.decision with data.case_collaboration as pilot_data
		with input as pilot_input
	d.allow == true
	d.reason == "allowed by charter and rules matrix"
	d.rollout_id == "test-shadow-rollout"
	d.max_signals_per_case == 8
}

test_agent_prefix_is_normalized if {
	case_collaboration.allow with data.case_collaboration as pilot_data
		with input as object.union(pilot_input, {"agent": "agent:rca-investigator"})
}

test_deny_without_matrix_grant if {
	d := case_collaboration.decision with data.case_collaboration as object.union(pilot_data, {"grants": []})
		with input as pilot_input
	d.allow == false
	d.reason == "no unique enabled rules matrix grant"
}

test_deny_without_charter_capability if {
	d := case_collaboration.decision with data.case_collaboration as object.union(pilot_data, {"agents": []})
		with input as pilot_input
	d.allow == false
	d.reason == "agent capability absent from charter"
}

test_deny_wrong_case_class if {
	not case_collaboration.allow with data.case_collaboration as pilot_data
		with input as object.union(pilot_input, {"case_class": "AML_REVIEW"})
}

test_deny_hitl_mode if {
	not case_collaboration.allow with data.case_collaboration as pilot_data
		with input as object.union(pilot_input, {"delivery_mode": "HITL"})
}

test_deny_disabled_grant if {
	disabled := object.union(pilot_data.grants[0], {"enabled": false})
	not case_collaboration.allow with data.case_collaboration as object.union(pilot_data, {"grants": [disabled]})
		with input as pilot_input
}

test_deny_ambiguous_duplicate_grants if {
	second := object.union(pilot_data.grants[0], {"rollout_id": "another-rollout"})
	not case_collaboration.allow with data.case_collaboration as object.union(pilot_data, {"grants": array.concat(pilot_data.grants, [second])})
		with input as pilot_input
}
