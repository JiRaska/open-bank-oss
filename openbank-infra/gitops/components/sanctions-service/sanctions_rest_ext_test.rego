# SPDX-License-Identifier: Apache-2.0
# Unit tests for sanctions_rest_ext.rego (#16455).
#
# Run EXPLICITLY by file, WITH the base policy (the pair the sidecar mounts):
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/sanctions-service/sanctions_rest_ext.rego \
#            openbank-infra/gitops/components/sanctions-service/sanctions_rest_ext_test.rego \
#            <dir with rules/data.yaml and agents/data.yaml>
# The four-eyes attribute reads data.rules, so the real governance data is mounted:
# opa-test-data: real

package openbank.rest

import rego.v1

agent := {"service_account": true, "client_id": "openbank-agent", "type": "HUMAN", "id": "service-account-openbank-agent", "roles": ["ROLE_API"]}

# Another machine on the same shape, holding the oversight role the shared client carries in the
# docker/CI realms. Must get nothing from the agent rules.
other_machine := {"service_account": true, "client_id": "openbank-services", "type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_API"]}

# A human whose NAME impersonates the agent but who carries no machine marker.
impostor := {"service_account": false, "type": "HUMAN", "id": "service-account-openbank-agent", "roles": ["ROLE_API"]}

operator := {"service_account": false, "type": "HUMAN", "id": "officer-1", "roles": ["ROLE_OPERATOR"]}

# --- must ALLOW: the agent as four-eyes maker of a CLEAR, and its two reads ---

test_agent_may_make_a_clear if {
	"service-agent-sanctions-review-maker" in allowed_reasons with input as {"principal": agent, "action": "sanctions.clear"}
}

test_agent_may_read if {
	"service-agent-sanctions-read" in allowed_reasons with input as {"principal": agent, "action": "sanctions.read"}
}

test_operator_still_decides if {
	"operator-sanctions-write" in allowed_reasons with input as {"principal": operator, "action": "sanctions.approval.decide"}
}

# The decision itself, not just the reason set: the agent's CLEAR is ALLOWED and comes back
# four-eyes gated, so it pauses for a human checker instead of executing. Also the positive control
# for the `not allow` cases below, which would pass vacuously if `allow` were never true.
test_agent_clear_is_allowed_and_four_eyes_gated if {
	d := allow with input as {"principal": agent, "action": "sanctions.clear"}
	d.allow == true
	d.attributes.four_eyes_required == true
}

# --- must DENY: the agent is never its own checker and has no other write ---

test_agent_never_decides_an_approval if {
	not allow with input as {"principal": agent, "action": "sanctions.approval.decide"}
}

test_agent_never_edits_a_watchlist if {
	not allow with input as {"principal": agent, "action": "sanctions.update"}
}

test_agent_never_refreshes_a_list if {
	not allow with input as {"principal": agent, "action": "sanctions.trigger"}
}

test_another_machine_gets_no_maker_grant if {
	not "service-agent-sanctions-review-maker" in allowed_reasons with input as {"principal": other_machine, "action": "sanctions.clear"}
}

test_a_name_alone_is_not_the_agent if {
	not "service-agent-sanctions-review-maker" in allowed_reasons with input as {"principal": impostor, "action": "sanctions.clear"}
}

# --- staff-only operator write: a machine holding ROLE_OPERATOR is never the checker ---

# The shared client as the docker/CI realms provision it (ROLE_OPERATOR).
operator_machine := {"service_account": true, "client_id": "openbank-services", "type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_API", "ROLE_OPERATOR"]}

# Marker absent (PEP not yet on phase-1 libs-runtime): still treated as a machine for staff rules.
operator_legacy_machine := {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]}

test_operator_machine_never_decides_an_approval if {
	not allow with input as {"principal": operator_machine, "action": "sanctions.approval.decide"}
}

test_legacy_operator_machine_never_decides_an_approval if {
	not allow with input as {"principal": operator_legacy_machine, "action": "sanctions.approval.decide"}
}

test_operator_machine_gets_no_staff_write if {
	every a in {"sanctions.clear", "sanctions.update", "sanctions.trigger", "sanctions.approval.decide"} {
		not "operator-sanctions-write" in allowed_reasons with input as {"principal": operator_machine, "action": a}
	}
}

# Positive controls: the human operator decides; machine screening still allowed.
test_human_operator_decide_is_allowed if {
	d := allow with input as {"principal": operator, "action": "sanctions.approval.decide"}
	d.allow == true
}

test_operator_machine_may_still_screen if {
	"m2m-sanctions-screening" in allowed_reasons with input as {"principal": operator_machine, "action": "sanctions.create"}
}
