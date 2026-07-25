// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.infrastructure.tool

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.copilot.application.port.out.CopilotTool
import com.openbank.copilot.application.port.out.ToolResult
import com.openbank.copilot.infrastructure.client.CustomerEdgeRestClient
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

/**
 * READ tool — recent transactions on one of the customer's own accounts (ADR-0089 Phase 1). Runs as
 * the customer through the customer edge (ownership-scoped, ADR-0065). Amounts are returned for the
 * model to NARRATE, never to invent (ADR-0089 D4).
 */
@ApplicationScoped
class TransactionTool(@RestClient private val client: CustomerEdgeRestClient) : CopilotTool {

    override val name = "list_transactions"
    override val description = "List recent transactions on one of the customer's own accounts, by account id (UUID)."
    override val capability = "account.transactions.read"
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf("accountId" to mapOf("type" to "string", "description" to "Account UUID")),
        "required" to listOf("accountId"),
    )

    override suspend fun call(arguments: JsonNode): ToolResult {
        val raw = arguments.get("accountId")?.asText()?.takeIf { it.isNotBlank() }
            ?: return ToolResult("Missing required 'accountId'.", isError = true)
        val accountId = runCatching { UUID.fromString(raw) }.getOrNull()
            ?: return ToolResult("'$raw' is not a valid account id.", isError = true)
        return try {
            val tx = client.listTransactions(accountId, DEFAULT_LIMIT).awaitSuspending().data
            if (tx.isEmpty()) {
                ToolResult("No recent transactions for account $accountId.")
            } else {
                ToolResult(
                    tx.joinToString("\n") { t ->
                        val date = t.bookingDate ?: "—"
                        val note = t.description?.let { " — $it" } ?: ""
                        "$date  ${t.amount} ${t.currencyCode}  [${t.type}/${t.status}]$note"
                    },
                )
            }
        } catch (e: WebApplicationException) {
            when (e.response?.status) {
                FORBIDDEN, NOT_FOUND -> ToolResult("That account isn't accessible.", isError = true)
                else -> ToolResult("Transactions are temporarily unavailable.", isError = true)
            }
        }
    }

    private companion object {
        const val DEFAULT_LIMIT = 10
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
    }
}
