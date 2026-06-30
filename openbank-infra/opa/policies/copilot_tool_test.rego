# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Unit tests for openbank.copilot.tool (copilot_tool.rego).
# Run with: opa test openbank-infra/opa/policies/ -v
#
# Tool names MUST match the `override val name` values in the Kotlin tool implementations
# (CopilotTool / ActionProposalTool). Any mismatch causes a silent deny-by-default.

package openbank.copilot.tool_test

import data.openbank.copilot.tool
import rego.v1

# ---------------------------------------------------------------------------
# Read-only tool tests — actual tool names from Kotlin implementations
# ---------------------------------------------------------------------------

test_get_my_accounts_allowed if {
	tool.allow with input as {"tool": "get_my_accounts", "customerId": "cust-1", "amount": null}
}

test_get_my_balances_allowed if {
	tool.allow with input as {"tool": "get_my_balances", "customerId": "cust-1", "amount": null}
}

test_get_account_balance_allowed if {
	tool.allow with input as {"tool": "get_account_balance", "customerId": "cust-1", "amount": null}
}

test_list_transactions_allowed if {
	tool.allow with input as {"tool": "list_transactions", "customerId": "cust-42", "amount": null}
}

test_get_account_statement_allowed if {
	tool.allow with input as {"tool": "get_account_statement", "customerId": "cust-42", "amount": null}
}

test_get_card_status_allowed if {
	tool.allow with input as {"tool": "get_card_status", "customerId": "cust-42", "amount": null}
}

test_get_fx_rates_allowed if {
	tool.allow with input as {"tool": "get_fx_rates", "customerId": "cust-42", "amount": null}
}

test_get_scheduled_payments_allowed if {
	tool.allow with input as {"tool": "get_scheduled_payments", "customerId": "cust-42", "amount": null}
}

test_search_help_allowed if {
	tool.allow with input as {"tool": "search_help", "customerId": "cust-42", "amount": null}
}

test_read_only_no_customer_denied if {
	not tool.allow with input as {"tool": "get_my_balances", "customerId": "", "amount": null}
}

# ---------------------------------------------------------------------------
# Proposal tool tests — actual tool names from Kotlin implementations
# ---------------------------------------------------------------------------

test_propose_payment_within_limit if {
	tool.allow with input as {"tool": "propose_payment", "customerId": "cust-1", "amount": 1000}
}

test_propose_payment_at_exact_limit if {
	tool.allow with input as {"tool": "propose_payment", "customerId": "cust-1", "amount": 5000}
}

test_propose_payment_over_limit_denied if {
	not tool.allow with input as {"tool": "propose_payment", "customerId": "cust-1", "amount": 6000}
}

test_propose_card_freeze_null_amount_allowed if {
	tool.allow with input as {"tool": "propose_card_freeze", "customerId": "cust-1", "amount": null}
}

test_propose_dispute_null_amount_allowed if {
	tool.allow with input as {"tool": "propose_dispute", "customerId": "cust-1", "amount": null}
}

test_propose_fx_conversion_within_limit if {
	tool.allow with input as {"tool": "propose_fx_conversion", "customerId": "cust-1", "amount": 500}
}

test_propose_payment_no_customer_denied if {
	not tool.allow with input as {"tool": "propose_payment", "customerId": "", "amount": 100}
}

# ---------------------------------------------------------------------------
# Unknown / forbidden tool tests
# ---------------------------------------------------------------------------

test_unknown_tool_denied if {
	not tool.allow with input as {"tool": "drop_table", "customerId": "cust-1", "amount": null}
}

test_admin_tool_denied if {
	not tool.allow with input as {"tool": "admin_override", "customerId": "cust-1", "amount": null}
}

# Old stale names must NOT be allowed (regression guard — tool name sync)
test_stale_name_get_balance_denied if {
	not tool.allow with input as {"tool": "get_balance", "customerId": "cust-1", "amount": null}
}

test_stale_name_initiate_transfer_denied if {
	not tool.allow with input as {"tool": "initiate_transfer", "customerId": "cust-1", "amount": 100}
}

test_stale_name_payment_proposal_denied if {
	not tool.allow with input as {"tool": "payment_proposal", "customerId": "cust-1", "amount": 100}
}

# ---------------------------------------------------------------------------
# Decision object tests
# ---------------------------------------------------------------------------

test_decision_has_reason_on_deny if {
	d := tool.decision with input as {"tool": "drop_table", "customerId": "cust-1", "amount": null}
	d.reason == "unknown tool"
	d.allow == false
}

test_decision_allowed_reason if {
	d := tool.decision with input as {"tool": "get_my_balances", "customerId": "cust-1", "amount": null}
	d.reason == "allowed"
	d.allow == true
}

test_decision_amount_exceeds_limit_reason if {
	d := tool.decision with input as {"tool": "propose_payment", "customerId": "cust-1", "amount": 9999}
	d.reason == "amount exceeds limit"
	d.allow == false
}

test_decision_unauthenticated_reason if {
	d := tool.decision with input as {"tool": "get_my_balances", "customerId": "", "amount": null}
	d.reason == "unauthenticated"
	d.allow == false
}
