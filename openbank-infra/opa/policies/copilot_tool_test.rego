# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Unit tests for openbank.copilot.tool (copilot_tool.rego).
# Run with: opa test openbank-infra/opa/policies/ -v

package openbank.copilot.tool_test

import data.openbank.copilot.tool
import rego.v1

# ---------------------------------------------------------------------------
# Read-only tool tests
# ---------------------------------------------------------------------------

test_read_only_allowed if {
	tool.allow with input as {"tool": "get_balance", "customerId": "cust-1", "amount": null}
}

test_read_only_no_customer_denied if {
	not tool.allow with input as {"tool": "get_balance", "customerId": "", "amount": null}
}

test_get_transactions_allowed if {
	tool.allow with input as {"tool": "get_transactions", "customerId": "cust-42", "amount": null}
}

test_get_fx_rates_allowed if {
	tool.allow with input as {"tool": "get_fx_rates", "customerId": "cust-42", "amount": null}
}

# ---------------------------------------------------------------------------
# Proposal tool tests
# ---------------------------------------------------------------------------

test_proposal_within_limit if {
	tool.allow with input as {"tool": "initiate_transfer", "customerId": "cust-1", "amount": 1000}
}

test_proposal_at_exact_limit if {
	tool.allow with input as {"tool": "initiate_transfer", "customerId": "cust-1", "amount": 5000}
}

test_proposal_over_limit_denied if {
	not tool.allow with input as {"tool": "initiate_transfer", "customerId": "cust-1", "amount": 6000}
}

test_proposal_null_amount_allowed if {
	tool.allow with input as {"tool": "card_freeze_proposal", "customerId": "cust-1", "amount": null}
}

test_proposal_no_customer_denied if {
	not tool.allow with input as {"tool": "payment_proposal", "customerId": "", "amount": 100}
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

# ---------------------------------------------------------------------------
# Decision object tests
# ---------------------------------------------------------------------------

test_decision_has_reason_on_deny if {
	d := tool.decision with input as {"tool": "drop_table", "customerId": "cust-1", "amount": null}
	d.reason == "unknown tool"
	d.allow == false
}

test_decision_allowed_reason if {
	d := tool.decision with input as {"tool": "get_balance", "customerId": "cust-1", "amount": null}
	d.reason == "allowed"
	d.allow == true
}

test_decision_amount_exceeds_limit_reason if {
	d := tool.decision with input as {"tool": "initiate_transfer", "customerId": "cust-1", "amount": 9999}
	d.reason == "amount exceeds limit"
	d.allow == false
}

test_decision_unauthenticated_reason if {
	d := tool.decision with input as {"tool": "get_balance", "customerId": "", "amount": null}
	d.reason == "unauthenticated"
	d.allow == false
}
