# SPDX-License-Identifier: Apache-2.0
# mcp-service REST extension: the customer MCP channel (ADR-0383 D4.1).
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# A customer-channel tool call reaches the PDP as principal type HUMAN, id = the customer `sub`,
# service_account = false, with attributes channel = "mcp-customer", client = azp, tool, and
# grantedCapabilities = the delegation capabilities of the customer's ACTIVE MCP_CLIENT grant for
# the account the call names (resolved server-side by mcp-service from delegation-service; the
# client supplies none of it). The channel may invoke ONLY the tools below, ONLY under the
# capability action that tool maps to, and ONLY when the grant carries the matching capability.
# Every other action on this channel is vetoed, whatever the principal's roles, so no base
# rest.rego reason can widen it.
#
# ADR-0383 D6: propose_payment is the channel's one write, and it is PROPOSE only — it records a
# PENDING proposal in delegation-service's store. There is deliberately no approve, reject or
# execute action here: the customer authorises in the bank app with SCA, never through MCP, so any
# such action on this channel falls to the veto below.
#
# Do NOT gate on input.principal.type == "SERVICE" (rules.yaml: authz_policy, #266).

package openbank.rest

import rego.v1

mcp_customer_tools := {
	"list_accounts": {"action": "query.account.readonly", "capability": "ACCOUNT_VIEW_DETAILS"},
	"get_balance": {"action": "query.balance.readonly", "capability": "ACCOUNT_READ_BALANCES"},
	"list_transactions": {"action": "query.transaction.readonly", "capability": "ACCOUNT_READ_TRANSACTIONS"},
	"propose_payment": {"action": "propose.payment", "capability": "ACCOUNT_PROPOSE_PAYMENT"},
}

mcp_customer_channel if object.get(input, ["attributes", "channel"], "") == "mcp-customer"

mcp_customer_tool_permitted if {
	mcp_customer_channel
	input.principal.type == "HUMAN"
	input.principal.service_account == false
	count(object.get(input.principal, "roles", [])) == 0
	t := mcp_customer_tools[input.attributes.tool]
	input.action == t.action
	t.capability in object.get(input.attributes, "grantedCapabilities", [])
}

allowed_reasons contains "mcp-customer-tool" if mcp_customer_tool_permitted

prohibited if {
	mcp_customer_channel
	not mcp_customer_tool_permitted
}
