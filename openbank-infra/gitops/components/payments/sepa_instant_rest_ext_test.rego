# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# Unit tests for sepa_instant_rest_ext.rego (#10486 batch 7). Run as CI's per-service trio:
#   opa test openbank-libs/governance/policies/rest.rego sepa_instant_rest_ext.rego sepa_instant_rest_ext_test.rego

package openbank.rest_test

import rego.v1

import data.openbank.rest

rules_mock := {
	"authz": {"role_action_matrix": {}},
	"money_path_services": [],
	"money_path_action_prefixes": {},
	"four_eyes": {"verbs": [], "actions": []},
	"feature_flags": {"prohibited_flag_combinations": [], "money_path_flags": []},
	"shared_m2m_write_prohibition": {"reasons": []},
}

sa(name) := {"type": "HUMAN", "id": sprintf("service-account-openbank-%v", [name]), "roles": ["ROLE_API"]}

all_actions := {"sctInstPayment.create", "sctInstPayment.read", "sctInstPayment.list", "sctInstPayment.recall"}

test_agent_may_list_and_read if {
	every action in {"sctInstPayment.list", "sctInstPayment.read"} {
		d := rest.allow with input as {"principal": sa("agent"), "action": action}
			with data.rules as rules_mock
		d.allow == true
		"service-agent-sct-inst-read" in rest.allowed_reasons with input as {"principal": sa("agent"), "action": action}
			with data.rules as rules_mock
	}
}

test_agent_may_not_create_or_recall if {
	every action in {"sctInstPayment.create", "sctInstPayment.recall"} {
		rest.allow == false with input as {"principal": sa("agent"), "action": action}
			with data.rules as rules_mock
	}
}

# Another ROLE_API service account (mcp-service's own identity, kyb) and the shared client once it
# holds ROLE_API only: denied every action. Widen the agent rule to a prefix and this goes red.
test_other_role_api_sa_denied if {
	every p in ["mcp", "services", "kyb"] {
		every action in all_actions {
			rest.allow == false with input as {"principal": sa(p), "action": action}
				with data.rules as rules_mock
		}
	}
}

test_operator_still_reads if {
	d := rest.allow with input as {"principal": {"type": "HUMAN", "id": "u-op", "roles": ["ROLE_OPERATOR"]}, "action": "sctInstPayment.read"}
		with data.rules as rules_mock
	d.allow == true
}
