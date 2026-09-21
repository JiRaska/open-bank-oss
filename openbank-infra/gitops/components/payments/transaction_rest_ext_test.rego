# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# Unit tests for transaction_rest_ext.rego (#10486 batch 1). Run as CI's per-service trio:
#   opa test openbank-libs/governance/policies/rest.rego transaction_rest_ext.rego transaction_rest_ext_test.rego
#
# The per-service identities carry ROLE_API and nothing else, exactly as the realm template grants
# them. The matrix mock grants ROLE_OPERATOR the transaction writes (as production does) and
# ROLE_API nothing, so no role-based reason can admit these principals: each identity rule is the
# whole grant, and everything else must deny.

package openbank.rest_test

import rego.v1

import data.openbank.rest

rules_mock := {
	"authz": {"role_action_matrix": {"ROLE_OPERATOR": {"grant": [
		"transaction.create",
		"transaction.reverse",
	]}}},
	"money_path_services": [],
	"money_path_action_prefixes": {},
	"four_eyes": {"verbs": [], "actions": []},
	"feature_flags": {"prohibited_flag_combinations": [], "money_path_flags": []},
	"shared_m2m_write_prohibition": {"reasons": []},
}

sa(name) := {"type": "HUMAN", "id": sprintf("service-account-openbank-%v", [name]), "roles": ["ROLE_API"]}

# caller -> (reason, actions it may perform). The ONLY grants the batch-1 identities hold here.
grants := {
	"account": {"reason": "service-account-transaction-create", "actions": {"transaction.create"}},
	"sdd": {"reason": "service-sdd-transaction-create", "actions": {"transaction.create"}},
	"standing-order": {"reason": "service-standing-order-transaction-create", "actions": {"transaction.create"}},
	"lending": {"reason": "service-lending-transaction-create", "actions": {"transaction.create"}},
	"sepa-payment": {
		"reason": "service-sepa-payment-transaction-write",
		"actions": {"transaction.create", "transaction.reverse"},
	},
}

all_actions := {
	"transaction.create", "transaction.reverse", "transaction.sweep", "transaction.search",
	"transaction.approval.decide", "transaction.approval.read",
}

# --- must-ALLOW: each identity, exactly its own actions, via its own reason ---
test_each_identity_may_perform_its_granted_actions if {
	every name, g in grants {
		every action in g.actions {
			decision := rest.allow with input as {"principal": sa(name), "action": action}
				with data.rules as rules_mock
			decision.allow == true
			g.reason in rest.allowed_reasons with input as {"principal": sa(name), "action": action}
				with data.rules as rules_mock
		}
	}
}

# --- must-DENY: each identity, every action it was not granted ---
test_each_identity_denied_everything_else if {
	every name, g in grants {
		every action in all_actions - g.actions {
			rest.allow == false with input as {"principal": sa(name), "action": action}
				with data.rules as rules_mock
		}
	}
}

# --- must-DENY: another service account holding ROLE_API. initiateTransaction admits ROLE_API at
# the RBAC layer now, so OPA is the only control for it. Remove a principal.id line above and this
# goes red.
test_other_role_api_sa_may_not_create if {
	every action in {"transaction.create", "transaction.reverse"} {
		rest.allow == false with input as {"principal": sa("mcp-service"), "action": action}
			with data.rules as rules_mock
	}
}

test_no_role_principal_may_not_create if {
	rest.allow == false with input as {"principal": {"type": "HUMAN", "id": "u-nobody", "roles": []}, "action": "transaction.create"}
		with data.rules as rules_mock
}

# No identity rule admits a principal other than its own (incl. the shared client).
test_identity_rules_do_not_cross_admit if {
	every name, g in grants {
		every other in {"account", "sdd", "standing-order", "lending", "sepa-payment", "services", "interest"} - {name} {
			not g.reason in rest.allowed_reasons with input as {"principal": sa(other), "action": "transaction.create"}
				with data.rules as rules_mock
		}
	}
}

# The unchanged operator path still admits a staff operator and the shared client (ROLE_OPERATOR)
# — batch 1 narrows nothing yet; it only adds per-service grants.
test_operator_path_unchanged if {
	decision := rest.allow with input as {"principal": {"type": "HUMAN", "id": "u-op", "roles": ["ROLE_OPERATOR"]}, "action": "transaction.create"}
		with data.rules as rules_mock
	decision.allow == true
	"operator-transaction-write" in rest.allowed_reasons with input as {"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]}, "action": "transaction.create"}
		with data.rules as rules_mock
}
