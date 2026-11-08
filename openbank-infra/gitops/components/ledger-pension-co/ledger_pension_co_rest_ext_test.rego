# SPDX-License-Identifier: Apache-2.0
# Unit tests for ledger_pension_co_rest_ext.rego (ADR-0337).
#
# Run EXPLICITLY by file, with the bank ledger extension the bundle also mounts:
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/ledger/ledger_rest_ext.rego \
#            openbank-infra/gitops/components/ledger-pension-co/ledger_pension_co_rest_ext.rego \
#            openbank-infra/gitops/components/ledger-pension-co/ledger_pension_co_rest_ext_test.rego
# opa-test-with: openbank-infra/gitops/components/ledger/ledger_rest_ext.rego
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

staff := {"service_account": false, "type": "HUMAN", "id": "u-accountant", "roles": ["ROLE_OPERATOR"]}

test_tax_reporting_reads_frozen_trial_balance if {
	rest.allow.allow with input as {"principal": tax_reporting, "action": "ledger.close.inspect", "resource": "2025-01-31"}
		with data.rules as rules_mock
	"service-tax-reporting-company-books-read" in rest.allowed_reasons with input as {"principal": tax_reporting, "action": "ledger.close.inspect", "resource": "2025-01-31"}
		with data.rules as rules_mock
}

# The grant is the frozen trial balance ONLY: every other read and every write of ledger-service is
# denied to tax-reporting, including ledger.read (journals, live trial balance, control accounts).
test_tax_reporting_may_perform_no_other_ledger_action if {
	every action in {
		"ledger.read", "ledger.list", "ledger.create", "ledger.reverse", "ledger.trigger",
		"ledger.replay", "ledger.approve", "ledger.close.draft",
	} {
		not rest.allow.allow with input as {"principal": tax_reporting, "action": action, "resource": "2025-01-31"}
			with data.rules as rules_mock
	}
}

# The bank's grant of ledger.close.inspect to the shared client (finrep) is vetoed here.
test_shared_client_may_not_inspect_the_company_books if {
	not rest.allow.allow with input as {"principal": shared_client, "action": "ledger.close.inspect"}
		with data.rules as rules_mock
}

test_staff_still_inspect if {
	rest.allow.allow with input as {"principal": staff, "action": "ledger.close.inspect"}
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
		"action": "ledger.close.inspect",
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
	not rest.allow.allow with input as {"principal": pension_client, "action": "ledger.close.inspect"}
		with data.rules as rules_mock
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

# Named company grants bind a current verified marker to its exact client; the
# absent-marker compatibility path remains the shared helper's existing contract.
test_verified_tax_client_reads_only_frozen_trial_balance if {
	p := object.union(tax_reporting, {"service_account": true, "client_id": "openbank-tax-reporting"})
	rest.allow.allow with input as {"principal": p, "action": "ledger.close.inspect"} with data.rules as rules_mock
	every action in {"ledger.read", "ledger.list", "ledger.create", "ledger.reverse", "ledger.close.draft"} {
		not rest.allow.allow with input as {"principal": p, "action": action} with data.rules as rules_mock
	}
}

test_explicit_human_cannot_borrow_tax_named_grant if {
	p := object.union(tax_reporting, {"service_account": false})
	not rest.allow.allow with input as {"principal": p, "action": "ledger.close.inspect"} with data.rules as rules_mock
}

test_current_tax_marker_requires_matching_client if {
	every claims in [
		{"service_account": true, "client_id": "openbank-other"},
		{"service_account": true},
		{"service_account": "true", "client_id": "openbank-tax-reporting"},
	] {
		p := object.union(tax_reporting, claims)
		not rest.allow.allow with input as {"principal": p, "action": "ledger.close.inspect"} with data.rules as rules_mock
	}
}

test_renamed_machine_with_staff_roles_is_vetoed if {
	p := {"type": "HUMAN", "id": "renamed-machine", "roles": ["ROLE_OPERATOR"], "service_account": true, "client_id": "openbank-other"}
	every action in {"ledger.read", "ledger.create", "ledger.close.inspect"} {
		not rest.allow.allow with input as {"principal": p, "action": action} with data.rules as rules_mock
	}
}

test_unmarked_nonservice_identity_is_not_staff if {
	p := {"type": "HUMAN", "id": "unverified-user", "roles": ["ROLE_OPERATOR"]}
	not rest.allow.allow with input as {"principal": p, "action": "ledger.close.inspect"} with data.rules as rules_mock
}

test_false_marker_keeps_service_prefix_write_veto if {
	p := {"type": "HUMAN", "id": "service-account-openbank-other", "roles": ["ROLE_OPERATOR"], "service_account": false}
	not rest.allow.allow with input as {"principal": p, "action": "ledger.create"} with data.rules as rules_mock
}

# An explicit null is malformed current input, not an absent legacy field.
test_explicit_null_tax_marker_cannot_borrow_legacy_grant if {
	every client in {"", "openbank-tax-reporting", "openbank-other"} {
		p := object.union(tax_reporting, {"service_account": null, "client_id": client})
		not rest.allow.allow with input as {"principal": p, "action": "ledger.close.inspect"} with data.rules as rules_mock
	}
}

test_absent_tax_marker_keeps_narrow_legacy_compatibility if {
	not "service_account" in object.keys(tax_reporting)
	rest.allow.allow with input as {"principal": tax_reporting, "action": "ledger.close.inspect"} with data.rules as rules_mock
	every action in {"ledger.read", "ledger.create", "ledger.reverse"} {
		not rest.allow.allow with input as {"principal": tax_reporting, "action": action} with data.rules as rules_mock
	}
}

pension_fund_client := {
	"type": "HUMAN",
	"id": "service-account-openbank-pension-fund",
	"roles": ["ROLE_API"],
	"service_account": true,
	"client_id": "openbank-pension-fund",
}

test_verified_fee_source_may_accrue_and_inspect_own_accounting if {
	every action in {"ledger.pension.fee", "ledger.pension.inspect"} {
		rest.allow.allow with input as {"principal": pension_fund_client, "action": action}
			with data.rules as rules_mock
	}
}

test_verified_fee_source_may_report_only_its_participation if {
	rest.allow.allow with input as {
		"principal": pension_fund_client,
		"action": "ledger.close.participate",
		"resource": {"type": "ledger", "id": "pension-fund"},
	}
		with data.rules as rules_mock
	every participant in {"interest", "billing", "pension-company-cash", "pension-company-operations", ""} {
		not rest.allow.allow with input as {
			"principal": pension_fund_client,
			"action": "ledger.close.participate",
			"resource": {"type": "ledger", "id": participant},
		}
			with data.rules as rules_mock
	}
}

test_fee_source_cannot_borrow_other_ledger_or_reconciliation_actions if {
	every action in {
		"ledger.read", "ledger.list", "ledger.create", "ledger.reverse", "ledger.trigger",
		"ledger.replay", "ledger.approve", "ledger.close.inspect", "ledger.close.draft", "ledger.pension.reconcile",
	} {
		not rest.allow.allow with input as {"principal": pension_fund_client, "action": action}
			with data.rules as rules_mock
	}
}

test_fee_source_requires_verified_client_marker_and_role if {
	every claims in [
		{"client_id": "openbank-other"},
		{"service_account": false},
		{"service_account": null},
		{"service_account": "true"},
		{"roles": ["ROLE_OPERATOR"]},
	] {
		p := object.union(pension_fund_client, claims)
		every action in {"ledger.pension.fee", "ledger.pension.inspect", "ledger.close.participate"} {
			not rest.allow.allow with input as {"principal": p, "action": action, "resource": {"type": "ledger", "id": "pension-fund"}}
				with data.rules as rules_mock
		}
	}
	legacy := object.remove(pension_fund_client, {"service_account", "client_id"})
	not rest.allow.allow with input as {"principal": legacy, "action": "ledger.pension.fee"}
		with data.rules as rules_mock
}

test_other_machines_and_tax_cannot_enter_pension_accounting if {
	every machine in {tax_reporting, shared_client, pension_client} {
		every action in {"ledger.pension.fee", "ledger.pension.inspect", "ledger.pension.reconcile", "ledger.close.participate"} {
			not rest.allow.allow with input as {"principal": machine, "action": action, "resource": {"type": "ledger", "id": "pension-fund"}}
				with data.rules as rules_mock
		}
	}
}

test_verified_human_operator_may_reconcile_company_books if {
	rest.allow.allow with input as {"principal": staff, "action": "ledger.pension.reconcile"}
		with data.rules as rules_mock
}

test_reconciliation_does_not_accept_machine_or_unmarked_staff if {
	every marker in [{"service_account": true, "client_id": "openbank-other"}, {"service_account": null}] {
		p := object.union(staff, marker)
		not rest.allow.allow with input as {"principal": p, "action": "ledger.pension.reconcile"}
			with data.rules as rules_mock
	}
	unmarked := object.remove(staff, {"service_account"})
	not rest.allow.allow with input as {"principal": unmarked, "action": "ledger.pension.reconcile"}
		with data.rules as rules_mock
}

# Actual OPA wire input is ResourceRef {type, id}; malformed or cross-purpose shapes fail closed.
test_participation_requires_the_typed_ledger_resource if {
	every resource in ["pension-fund", {"type": "fund", "id": "pension-fund"}, {"id": "pension-fund"}, {}] {
		not rest.allow.allow with input as {"principal": pension_fund_client, "action": "ledger.close.participate", "resource": resource}
			with data.rules as rules_mock
	}
}
