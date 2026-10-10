# SPDX-License-Identifier: Apache-2.0
# Unit tests for ledger_pension_co_rest_ext.rego (ADR-0337).
#
# Run EXPLICITLY by file, with the bank ledger extension the bundle also mounts:
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/ledger/ledger_rest_ext.rego \
#            openbank-infra/gitops/components/ledger-pension-co/ledger_pension_co_rest_ext.rego \
#            openbank-infra/gitops/components/ledger-pension-co/ledger_pension_co_rest_ext_test.rego
#
# The mock grants ROLE_OPERATOR the ledger writes via the matrix and keeps operator-read-any
# live, so the shared client has a FIRING reason on every case below — the veto must beat it.

package openbank.rest_test

import data.openbank.rest

rules_mock := {
	"authz": {"role_action_matrix": {"ROLE_OPERATOR": {"grant": ["ledger.create", "ledger.reverse"]}}},
	"money_path_services": [],
	"money_path_action_prefixes": {},
	"four_eyes": {"verbs": [], "actions": []},
	"feature_flags": {"prohibited_flag_combinations": [], "money_path_flags": []},
	"shared_m2m_write_prohibition": {"reasons": []},
}

tax_reporting := {"type": "HUMAN", "id": "service-account-openbank-tax-reporting", "roles": ["ROLE_API"]}

shared_client := {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR", "ROLE_API"]}

pension_client := {"type": "HUMAN", "id": "service-account-openbank-pension", "roles": ["ROLE_API"]}

staff := {"type": "HUMAN", "id": "u-accountant", "roles": ["ROLE_OPERATOR"]}

test_tax_reporting_reads_frozen_trial_balance if {
	rest.allow.allow with input as {"principal": tax_reporting, "action": "ledger.read", "resource": {"id": "2025-01-01"}}
		with data.rules as rules_mock
}

test_tax_reporting_may_not_post_a_journal if {
	not rest.allow.allow with input as {"principal": tax_reporting, "action": "ledger.create"}
		with data.rules as rules_mock
}

test_tax_reporting_may_not_reverse if {
	not rest.allow.allow with input as {"principal": tax_reporting, "action": "ledger.reverse"}
		with data.rules as rules_mock
}

test_tax_reporting_without_role_api_denied if {
	not rest.allow.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-tax-reporting", "roles": []},
		"action": "ledger.read",
	}
		with data.rules as rules_mock
}

test_shared_client_may_not_read_despite_operator_read_any if {
	not rest.allow.allow with input as {"principal": shared_client, "action": "ledger.read"}
		with data.rules as rules_mock
}

test_shared_client_may_not_post_despite_bank_m2m_rules if {
	not rest.allow.allow with input as {"principal": shared_client, "action": "ledger.create"}
		with data.rules as rules_mock
}

test_other_service_account_may_not_read if {
	not rest.allow.allow with input as {"principal": pension_client, "action": "ledger.read"}
		with data.rules as rules_mock
}

test_anonymous_denied if {
	not rest.allow.allow with input as {"principal": {"type": "ANONYMOUS", "roles": []}, "action": "ledger.read"}
		with data.rules as rules_mock
}

test_staff_still_read if {
	rest.allow.allow with input as {"principal": staff, "action": "ledger.read"}
		with data.rules as rules_mock
}
