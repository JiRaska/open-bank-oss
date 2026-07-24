# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Copilot tool-dispatch authorization (ADR-0031, ADR-0089).
# Queried by openbank-copilot-service before dispatching any tool call:
#   data.openbank.copilot.tool.allow
# with input { tool, customerId, amount } where amount may be null.
#
# Two tool classes:
#   read_only_tools   — no money movement; allowed for any authenticated customer.
#   proposal_tools    — create a ProposalToken requiring SCA confirmation; allowed when
#                       input.amount (if present) does not exceed max_proposal_amount_eur.
#
# The ceiling is hard-coded here; override via OPA bundle data if needed.

package openbank.copilot.tool

import rego.v1

# Default deny
default allow := false

# ---------------------------------------------------------------------------
# Read-only tools — always allowed for authenticated customer
# ---------------------------------------------------------------------------
allow if {
	input.tool in read_only_tools
	input.customerId != ""
}

# ---------------------------------------------------------------------------
# Proposal tools — allowed but amount must not exceed per-session limit
# ---------------------------------------------------------------------------
allow if {
	input.tool in proposal_tools
	input.customerId != ""
	amount_within_limit
}

amount_within_limit if {
	input.amount == null # no amount = structural proposal, always ok
}

amount_within_limit if {
	input.amount != null
	input.amount <= max_proposal_amount_eur
}

# ---------------------------------------------------------------------------
# Tool classifications
# ---------------------------------------------------------------------------

# Read-only tools (no money movement).
# Names MUST match the `override val name` in each CopilotTool implementation (ADR-0089 D3).
read_only_tools := {
	"get_my_accounts",
	"get_my_balances",
	"get_account_balance",
	"list_transactions",
	"get_account_statement",
	"get_card_status",
	"get_fx_rates",
	"get_scheduled_payments",
	"search_help",
	# design_theme (ADR-0191): no money, no server state — returns a ThemeSpec the app
	# persists via the edge and re-validates on-device; safe for any authenticated customer.
	"design_theme",
}

# Proposal tools (create ProposalToken, require SCA confirm).
# Names MUST match the `override val name` in each ActionProposalTool implementation (ADR-0089 D2).
proposal_tools := {
	"propose_payment",
	"propose_card_freeze",
	"propose_dispute",
	"propose_fx_conversion",
}

# EUR 5 000 per-proposal ceiling (hard-coded; override via OPA bundle data if needed)
max_proposal_amount_eur := 5000

# ---------------------------------------------------------------------------
# Decision object — always emitted so a DENY is auditable (ADR-0031 D5)
# ---------------------------------------------------------------------------
decision := {
	"allow": allow,
	"tool": input.tool,
	"customerId": input.customerId,
	"reason": reason,
}

reason := "unknown tool" if {
	not input.tool in read_only_tools
	not input.tool in proposal_tools
}

else := "unauthenticated" if input.customerId == ""

else := "amount exceeds limit" if {
	input.tool in proposal_tools
	not amount_within_limit
}

else := "allowed"
