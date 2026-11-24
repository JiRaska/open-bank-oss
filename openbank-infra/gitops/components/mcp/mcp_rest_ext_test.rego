# SPDX-License-Identifier: Apache-2.0
# Unit tests for mcp_rest_ext.rego (ADR-0383 D4.1). Run explicitly by file:
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/mcp/mcp_rest_ext.rego \
#            openbank-infra/gitops/components/mcp/mcp_rest_ext_test.rego

package openbank.rest

import rego.v1

customer := {"type": "HUMAN", "id": "c0ffee00-0000-0000-0000-000000000001", "service_account": false, "roles": []}

call(tool, action, caps) := {
	"principal": customer,
	"action": action,
	"attributes": {"channel": "mcp-customer", "client": "mcp-local-reference", "tool": tool, "grantedCapabilities": caps},
}

test_balance_read_with_balance_grant_allowed if {
	allow with input as call("get_balance", "query.balance.readonly", ["ACCOUNT_READ_BALANCES"])
}

test_transactions_with_transaction_grant_allowed if {
	allow with input as call("list_transactions", "query.transaction.readonly", ["ACCOUNT_READ_TRANSACTIONS"])
}

test_scope_escalation_balance_grant_cannot_list_transactions if {
	not allow with input as call("list_transactions", "query.transaction.readonly", ["ACCOUNT_READ_BALANCES"])
}

test_no_grant_capability_denied if {
	not allow with input as call("get_balance", "query.balance.readonly", [])
}

# ADR-0383 D6: the channel's one write is PROPOSE, and only with the grant's propose capability.
test_propose_with_propose_capability_allowed if {
	allow with input as call("propose_payment", "propose.payment", ["ACCOUNT_PROPOSE_PAYMENT"])
}

test_propose_without_propose_capability_denied if {
	not allow with input as call("propose_payment", "propose.payment", ["ACCOUNT_READ_BALANCES", "ACCOUNT_READ_TRANSACTIONS"])
}

test_propose_with_no_capability_denied if {
	not allow with input as call("propose_payment", "propose.payment", [])
}

# Approving or executing is never an MCP action, whatever the grant carries or the tool claims.
test_approve_denied_on_customer_channel if {
	not allow with input as call("propose_payment", "delegation.proposal.approve", ["ACCOUNT_PROPOSE_PAYMENT"])
}

test_execute_denied_on_customer_channel if {
	not allow with input as call("propose_payment", "delegation.proposal.execution", ["ACCOUNT_PROPOSE_PAYMENT"])
}

test_payment_execute_denied_on_customer_channel if {
	not allow with input as call("execute_payment", "payment.execute", ["ACCOUNT_PROPOSE_PAYMENT"])
}

test_propose_under_a_read_action_denied if {
	not allow with input as call("propose_payment", "query.balance.readonly", ["ACCOUNT_PROPOSE_PAYMENT", "ACCOUNT_READ_BALANCES"])
}

test_propose_by_staff_roles_on_customer_channel_denied if {
	not allow with input as object.union(call("propose_payment", "propose.payment", ["ACCOUNT_PROPOSE_PAYMENT"]), {"principal": object.union(customer, {"roles": ["ROLE_OPERATOR"]})})
}

test_tool_action_mismatch_denied if {
	not allow with input as call("get_balance", "query.transaction.readonly", ["ACCOUNT_READ_BALANCES", "ACCOUNT_READ_TRANSACTIONS"])
}

test_staff_roles_on_customer_channel_denied if {
	not allow with input as object.union(call("get_balance", "query.balance.readonly", ["ACCOUNT_READ_BALANCES"]), {"principal": object.union(customer, {"roles": ["ROLE_OPERATOR"]})})
}

test_service_account_on_customer_channel_denied if {
	not allow with input as object.union(call("get_balance", "query.balance.readonly", ["ACCOUNT_READ_BALANCES"]), {"principal": object.union(customer, {"service_account": true})})
}

# On this channel even an action base rest.rego admits (operator-read-any) is vetoed.
test_customer_channel_write_vetoed_for_operator if {
	not allow with input as {
		"principal": {"type": "HUMAN", "id": "u-op", "service_account": false, "roles": ["ROLE_OPERATOR"]},
		"action": "account.read",
		"attributes": {"channel": "mcp-customer", "tool": "get_balance", "grantedCapabilities": ["ACCOUNT_READ_BALANCES"]},
	}
}

# Control: the same operator read OFF the customer channel is still allowed by base rest.rego,
# so the test above is red only because of this file's veto.
test_control_operator_read_off_channel_allowed if {
	allow with input as {
		"principal": {"type": "HUMAN", "id": "u-op", "service_account": false, "roles": ["ROLE_OPERATOR"]},
		"action": "account.read",
	}
}
