# SPDX-License-Identifier: Apache-2.0
# opa-test-with: openbank-infra/gitops/components/ledger/ledger_rest_ext.rego
package openbank.rest_test

import data.openbank.rest
import rego.v1

fund_rules := {
	"authz": {"role_action_matrix": {"ROLE_OPERATOR": {"grant": ["ledger.create", "ledger.reverse"]}}},
	"money_path_services": [], "money_path_action_prefixes": {},
	"four_eyes": {"verbs": [], "actions": []},
	"feature_flags": {"prohibited_flag_combinations": [], "money_path_flags": []},
	"shared_m2m_write_prohibition": {"reasons": []},
}

fund_source := {
	"type": "HUMAN", "id": "service-account-openbank-pension-fund",
	"service_account": true, "client_id": "openbank-pension-fund", "roles": ["ROLE_API"],
}

fund_shared := {
	"type": "HUMAN", "id": "service-account-openbank-services",
	"service_account": true, "client_id": "openbank-services", "roles": ["ROLE_API", "ROLE_OPERATOR"],
}

test_only_own_purposes_are_admitted if {
	every action in {"ledger.pension.fee", "ledger.pension.inspect", "ledger.close.participate"} {
		rest.allow.allow with input as {"principal": fund_source, "action": action, "resource": "pension-fund"}
			with data.rules as fund_rules
	}
}

test_nav_source_cannot_post_arbitrary_journals_or_read_balances if {
	every action in {"ledger.create", "ledger.reverse", "ledger.read", "ledger.list", "ledger.approve", "ledger.pension.reconcile"} {
		not rest.allow.allow with input as {"principal": fund_source, "action": action, "resource": "pension-fund"}
			with data.rules as fund_rules
	}
	not rest.allow.allow with input as {"principal": fund_source, "action": "ledger.close.participate", "resource": "interest"}
		with data.rules as fund_rules
}

test_bank_shared_client_is_vetoed_even_with_staff_role if {
	every action in {"ledger.create", "ledger.read", "ledger.pension.fee", "ledger.pension.inspect", "ledger.pension.reconcile"} {
		not rest.allow.allow with input as {"principal": fund_shared, "action": action}
			with data.rules as fund_rules
	}
}

test_wrong_issuing_client_cannot_spoof_the_source_name if {
	spoof := object.union(fund_source, {"client_id": "another-client"})
	not rest.allow.allow with input as {"principal": spoof, "action": "ledger.pension.fee"}
		with data.rules as fund_rules
}

test_interactive_operator_can_reconcile if {
	staff := {"type": "HUMAN", "id": "accountant", "service_account": false, "roles": ["ROLE_OPERATOR"]}
	rest.allow.allow with input as {"principal": staff, "action": "ledger.pension.reconcile"}
		with data.rules as fund_rules
}
