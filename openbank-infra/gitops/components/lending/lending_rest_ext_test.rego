# SPDX-License-Identifier: Apache-2.0
# Unit tests for lending_rest_ext.rego (2026-08-05, #3734).
#
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/lending/lending_rest_ext.rego \
#            openbank-infra/gitops/components/lending/lending_rest_ext_test.rego

package openbank.rest_test

import rego.v1

import data.openbank.rest

operator := {"type": "HUMAN", "id": "u-op", "roles": ["ROLE_OPERATOR"]}

edge := {"type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]}

shared := {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]}

# The matrix grants ROLE_OPERATOR eight lending writes (+ reads) — this is what matrix-allows
# re-admits to the edge without the veto. Shape mirrors
# data.rules.authz.role_action_matrix[role].grant[_] (rules-opa-data.yaml).
rules_mock := {"authz": {"role_action_matrix": {"ROLE_OPERATOR": {"grant": [
	"lending.approve",
	"lending.collateralDecide",
	"lending.collateralRegister",
	"lending.create",
	"lending.disburse",
	"lending.list",
	"lending.read",
	"lending.repay",
	"lending.reschedule",
	"lending.writeoff",
]}}}}

# --- humans: unchanged ---

test_operator_disburses if {
	rest.allow with input as {"principal": operator, "action": "lending.disburse"}
		with data.rules as rules_mock
}

test_operator_marks_default if {
	rest.allow with input as {"principal": operator, "action": "lending.default.mark"}
		with data.rules as rules_mock
}

# --- edge: verified customer loan application preserved via the identity rule ---

test_edge_may_intake_via_identity_rule if {
	rest.allow with input as {"principal": edge, "action": "lending.intake"}
		with data.rules as rules_mock
}

# --- edge: all eight matrix-granted writes closed on BOTH paths ---

test_edge_denied_approve if {
	not rest.allow with input as {"principal": edge, "action": "lending.approve"}
		with data.rules as rules_mock
}

test_edge_denied_collateral_decide if {
	not rest.allow with input as {"principal": edge, "action": "lending.collateralDecide"}
		with data.rules as rules_mock
}

test_edge_denied_collateral_register if {
	not rest.allow with input as {"principal": edge, "action": "lending.collateralRegister"}
		with data.rules as rules_mock
}

test_edge_denied_create if {
	not rest.allow with input as {"principal": edge, "action": "lending.create"}
		with data.rules as rules_mock
}

test_edge_denied_disburse if {
	not rest.allow with input as {"principal": edge, "action": "lending.disburse"}
		with data.rules as rules_mock
}

test_edge_denied_repay if {
	not rest.allow with input as {"principal": edge, "action": "lending.repay"}
		with data.rules as rules_mock
}

test_edge_denied_reschedule if {
	not rest.allow with input as {"principal": edge, "action": "lending.reschedule"}
		with data.rules as rules_mock
}

test_edge_denied_writeoff if {
	not rest.allow with input as {"principal": edge, "action": "lending.writeoff"}
		with data.rules as rules_mock
}

# --- edge: non-matrix writes closed by the exclusion alone ---

test_edge_denied_acceleration_execute if {
	not rest.allow with input as {"principal": edge, "action": "lending.acceleration.execute"}
		with data.rules as rules_mock
}

test_edge_denied_approval_decide if {
	not rest.allow with input as {"principal": edge, "action": "lending.approval.decide"}
		with data.rules as rules_mock
}

test_edge_veto_fires_on_disburse if {
	rest.prohibited with input as {"principal": edge, "action": "lending.disburse"}
		with data.rules as rules_mock
}

test_edge_no_operator_rule_on_disburse if {
	not "operator-lending-write" in rest.allowed_reasons
		with input as {"principal": edge, "action": "lending.disburse"}
		with data.rules as rules_mock
}

test_shared_no_operator_rule_on_create if {
	not "operator-lending-write" in rest.allowed_reasons
		with input as {"principal": shared, "action": "lending.create"}
		with data.rules as rules_mock
}

# ── ADR-0269 rule 2: the credit-offer eligibility read (issue #8918) ─────────────────────────

test_shared_service_may_read_credit_offer_eligibility if {
	"service-credit-offer-eligibility" in rest.allowed_reasons
		with input as {"principal": shared, "action": "lending.creditOffer.eligibility"}
		with data.rules as rules_mock
}

# The rule opens ONE action. The shared client is every backend service at once, so a rule that
# leaked past its action would hand all of them a lending endpoint they have no business in.
test_shared_service_gets_nothing_else_from_that_rule if {
	not "service-credit-offer-eligibility" in rest.allowed_reasons
		with input as {"principal": shared, "action": "lending.disburse"}
		with data.rules as rules_mock
}

test_shared_service_gets_no_read_of_loans_from_that_rule if {
	not "service-credit-offer-eligibility" in rest.allowed_reasons
		with input as {"principal": shared, "action": "lending.read"}
		with data.rules as rules_mock
}

# And it is scoped to the identity, not to the action alone: the edge does not inherit it.
test_edge_does_not_inherit_the_eligibility_rule if {
	not "service-credit-offer-eligibility" in rest.allowed_reasons
		with input as {"principal": edge, "action": "lending.creditOffer.eligibility"}
		with data.rules as rules_mock
}

# --- ADR-0314 D4: the risk engine's loan-book read ---

risk_engine := {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_API"]}

other_api_client := {"type": "HUMAN", "id": "service-account-openbank-interest", "roles": ["ROLE_API"]}

credit_risk := {"type": "HUMAN", "id": "u-cr", "roles": ["ROLE_CREDIT_RISK"]}

test_risk_engine_may_read_loan_book if {
	rest.allow with input as {"principal": risk_engine, "action": "lending.book.read"}
		with data.rules as rules_mock
}

test_risk_engine_may_not_write_via_the_book_rule if {
	not rest.allow with input as {"principal": risk_engine, "action": "lending.disburse"}
		with data.rules as rules_mock
}

test_other_role_api_client_may_not_read_loan_book if {
	not rest.allow with input as {"principal": other_api_client, "action": "lending.book.read"}
		with data.rules as rules_mock
}

test_credit_risk_may_read_loan_book if {
	rest.allow with input as {"principal": credit_risk, "action": "lending.book.read"}
		with data.rules as rules_mock
}

test_edge_denied_loan_book if {
	not rest.allow with input as {"principal": edge, "action": "lending.book.read"}
		with data.rules as rules_mock
}

# ── #10746: ledger backfill — ROLE_ADMIN humans only, every service account vetoed ───────────

admin := {"type": "HUMAN", "id": "u-admin", "roles": ["ROLE_ADMIN"]}

shared_admin := {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_ADMIN", "ROLE_OPERATOR"]}

backfill_actions := [
	"lending.ledgerBackfill.read",
	"lending.ledgerBackfill.propose",
	"lending.ledgerBackfill.decide",
	"lending.ledgerBackfill.execute",
]

test_admin_may_run_every_backfill_action if {
	every action in backfill_actions {
		rest.allow with input as {"principal": admin, "action": action}
			with data.rules as rules_mock
	}
}

test_operator_denied_every_backfill_action if {
	every action in backfill_actions {
		not rest.allow with input as {"principal": operator, "action": action}
			with data.rules as rules_mock
	}
}

test_service_account_with_admin_role_denied_every_backfill_action if {
	every action in backfill_actions {
		not rest.allow with input as {"principal": shared_admin, "action": action}
			with data.rules as rules_mock
	}
}

test_edge_denied_backfill_execute if {
	not rest.allow with input as {"principal": edge, "action": "lending.ledgerBackfill.execute"}
		with data.rules as rules_mock
}

test_credit_risk_denied_backfill_decide if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "u-risk", "roles": ["ROLE_CREDIT_RISK"]},
		"action": "lending.ledgerBackfill.decide",
	}
		with data.rules as rules_mock
}

# Control: the veto is scoped to the backfill; an admin's other lending writes are untouched.
test_backfill_veto_does_not_touch_other_admin_writes if {
	not rest.prohibited with input as {"principal": admin, "action": "lending.disburse"}
		with data.rules as rules_mock
}

test_graph_source_read_allows_human_risk_or_admin if {
    every role in ["ROLE_CREDIT_RISK", "ROLE_ADMIN"] {
        decision := rest.allow with input as {
            "principal": {"type": "HUMAN", "id": "loan-reviewer", "roles": [role]},
            "action": "lending.graph.read",
        } with data.rules as rules_mock
        decision.allow
    }
}

test_graph_source_read_denies_operator_compliance_and_service_account if {
    every principal in [
        {"type": "HUMAN", "id": "operator", "roles": ["ROLE_OPERATOR"]},
        {"type": "HUMAN", "id": "compliance", "roles": ["ROLE_COMPLIANCE"]},
        {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_ADMIN"]},
    ] {
        not rest.allow with input as {
            "principal": principal,
            "action": "lending.graph.read",
        } with data.rules as rules_mock
    }
}
