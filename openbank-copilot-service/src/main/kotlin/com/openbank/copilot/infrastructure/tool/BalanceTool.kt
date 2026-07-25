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
 * READ tool — the customer's own account balances (ADR-0089 Phase 1). Runs as the customer via the
 * propagated bearer through the customer edge, which enforces ownership (ADR-0065). The figures are
 * returned for the model to NARRATE, never to invent (ADR-0089 D4).
 */
@ApplicationScoped
class BalanceTool(@RestClient private val client: CustomerEdgeRestClient) : CopilotTool {

    override val name = "get_account_balance"
    override val description = "Get the customer's balances for one of their own accounts, by account id (UUID)."
    override val capability = "account.balance.read"
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
            val balances = client.getBalances(accountId).awaitSuspending()
            if (balances.isEmpty()) {
                ToolResult("No balances found for account $accountId.")
            } else {
                ToolResult(
                    balances.joinToString("\n") { b ->
                        "${b.currency}: available ${b.availableAmount}, booked ${b.bookedAmount}"
                    },
                )
            }
        } catch (e: WebApplicationException) {
            when (e.response?.status) {
                FORBIDDEN, NOT_FOUND -> ToolResult("That account isn't accessible.", isError = true)
                else -> ToolResult("Balance service is unavailable (HTTP ${e.response?.status ?: 0}).", isError = true)
            }
        }
    }

    private companion object {
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
    }
}
