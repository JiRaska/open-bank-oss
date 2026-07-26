// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.mcp.application.port.out.AccountReadPort
import com.openbank.mcp.application.port.out.ConsentContext
import com.openbank.mcp.application.port.out.ProposalPort
import com.openbank.mcp.application.protocol.ToolCallResult
import com.openbank.mcp.application.protocol.ToolContent
import com.openbank.mcp.application.protocol.ToolDefinition
import jakarta.enterprise.context.ApplicationScoped

/**
 * The curated MCP tool set (ADR-0181): consent-scoped reads plus a single HITL payment proposal.
 * Mirrors agent-service's McpToolRegistry — the [capabilities] map is the deny-by-default gate: a
 * tool with no capability entry has no OPA action to authorize and is refused. The capability
 * strings are the `agents.yaml` charter actions the ADR-0034 PDP evaluates as `input.action` for an
 * `AI_AGENT` principal (see McpEndpoint's @Authorize bridge).
 */
@ApplicationScoped
class McpToolRegistry(
    private val accounts: AccountReadPort,
    private val proposals: ProposalPort,
    private val masker: McpPiiMasker,
    private val mapper: ObjectMapper,
) {

    /** tool name -> the OPA charter capability the PDP gates the call on. Absent = refused. */
    val capabilities: Map<String, String> = mapOf(
        "list_accounts" to "query.account.readonly",
        "get_balance" to "query.balance.readonly",
        "list_transactions" to "query.transaction.readonly",
        "list_consents" to "query.consent.readonly",
        "propose_payment" to "propose.payment",
    )

    val tools: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "list_accounts",
            description = "List the accounts the presented PSD2 consent grants access to.",
            inputSchema = obj(mapOf<String, Any>(), required = emptyList()),
            service = "openbank-account-service",
            domain = "accounts",
        ),
        ToolDefinition(
            name = "get_balance",
            description = "Get the balance of a granted account.",
            inputSchema = obj(
                mapOf("accountId" to strProp("Account id (must be within the consent)")),
                listOf("accountId"),
            ),
            service = "openbank-balance-service",
            domain = "accounts",
        ),
        ToolDefinition(
            name = "list_transactions",
            description = "List recent transactions of a granted account.",
            inputSchema = obj(
                mapOf(
                    "accountId" to strProp("Account id (must be within the consent)"),
                    "limit" to mapOf("type" to "integer", "description" to "Max rows (default 50)"),
                ),
                listOf("accountId"),
            ),
            service = "openbank-transaction-service",
            domain = "transactions",
        ),
        ToolDefinition(
            name = "list_consents",
            description = "List the PSD2 consents the acting agent holds.",
            inputSchema = obj(mapOf<String, Any>(), required = emptyList()),
            service = "openbank-consent-service",
            domain = "consent",
        ),
        ToolDefinition(
            name = "propose_payment",
            description = "Create a REVIEWABLE payment proposal (never a debit; a human + SCA disposes).",
            inputSchema = obj(
                mapOf(
                    "fromAccountId" to strProp("Debtor account (must be within the consent)"),
                    "toIban" to strProp("Creditor IBAN"),
                    "amount" to mapOf("type" to "string", "description" to "Amount, decimal string"),
                    "currency" to strProp("ISO 4217 currency"),
                ),
                listOf("fromAccountId", "toIban", "amount", "currency"),
            ),
            service = "openbank-mcp-service",
            domain = "payments",
        ),
    )

    /** Execute one tool. The caller (McpEndpoint) has already OPA-authorized [toolName]. */
    fun call(toolName: String, arguments: JsonNode, ctx: ConsentContext): ToolCallResult {
        val result: JsonNode = when (toolName) {
            "list_accounts" -> accounts.listAccounts(ctx)
            "get_balance" -> accounts.getBalance(ctx, arguments.reqText("accountId"))
            "list_transactions" ->
                accounts.listTransactions(
                    ctx,
                    arguments.reqText("accountId"),
                    arguments.path("limit").asInt(DEFAULT_TX_LIMIT),
                )
            "list_consents" -> accounts.listConsents(ctx)
            // The PROPOSED-only invariant is enforced HERE, on the call path, not left to whichever
            // ProposalPort is bound (T-E4, #2414). See ProposedOnly for why it is a whitelist of one.
            "propose_payment" -> ProposedOnly.enforce(proposals.proposePayment(ctx, arguments))
            else -> return ToolCallResult(listOf(ToolContent(text = "Unknown tool: $toolName")), isError = true)
        }
        // Two orthogonal controls, both applied HERE — one response-shaping step every tool passes
        // through, so a new tool cannot forget either of them (#2412).
        //   masker  — the charter's `data_scope.pii: masked` (agents.yaml, `mcp-anonymous`):
        //             narrows WHAT can leave the bank.
        //   wrap    — instruction/data separation (ADR-0195 T-I3): narrows what the data that DOES
        //             leave can make the calling client's model do. See McpUntrustedData.
        // Order matters only in that wrapping must come last: it must enclose the final bytes, or
        // a later step could re-introduce text outside the markers.
        val masked = mapper.writeValueAsString(masker.mask(result))
        return ToolCallResult(listOf(ToolContent(text = McpUntrustedData.wrap(masked))))
    }

    private fun JsonNode.reqText(field: String): String = path(field).takeIf { it.isTextual }?.asText()
        ?: throw IllegalArgumentException("missing or non-string argument: $field")

    private fun strProp(desc: String) = mapOf("type" to "string", "description" to desc)

    private fun obj(props: Map<String, Any>, required: List<String>): Map<String, Any> = buildMap {
        put("type", "object")
        put("properties", props)
        if (required.isNotEmpty()) put("required", required)
    }

    private companion object {
        const val DEFAULT_TX_LIMIT = 50
    }
}
