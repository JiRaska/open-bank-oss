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

/**
 * READ tool — accounts AND their current balances in a single call (ADR-0089 Phase 1). Replaces
 * the two-step get_my_accounts → get_account_balance pattern that required two model inference rounds.
 * The system prompt directs the model to prefer this tool for any balance or account overview query,
 * cutting the common case from 3 inference rounds to 2. Ownership is enforced at the customer edge
 * (ADR-0065); figures are returned for the model to NARRATE, never to invent (ADR-0089 D4).
 */
@ApplicationScoped
class AccountsWithBalancesTool(@RestClient private val client: CustomerEdgeRestClient) : CopilotTool {

    override val name = "get_my_balances"
    override val description =
        "List the customer's own accounts together with their current balances in one call. " +
            "Use this as the FIRST choice for any balance or account overview query. " +
            "It replaces the two-step get_my_accounts + get_account_balance flow."
    override val capability = "account.balance.read"
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to emptyMap<String, Any>(),
    )

    override suspend fun call(arguments: JsonNode): ToolResult {
        val accounts = try {
            client.listAccounts().awaitSuspending().data
        } catch (e: WebApplicationException) {
            return ToolResult("Účty se nepodařilo načíst (HTTP ${e.response?.status ?: 0}).", isError = true)
        }
        if (accounts.isEmpty()) return ToolResult("Klient nemá žádné účty.")

        val lines = accounts.map { a ->
            val id = a.id
            val balancePart = if (id != null) {
                try {
                    val balances = client.getBalances(id).awaitSuspending()
                    if (balances.isEmpty()) {
                        "zůstatek nedostupný"
                    } else {
                        balances.joinToString(", ") { b -> "${b.currency}: ${b.availableAmount} k dispozici" }
                    }
                } catch (_: WebApplicationException) {
                    "zůstatek nedostupný"
                }
            } else {
                "zůstatek nedostupný"
            }
            "účet ${a.accountNumber} (typ ${a.accountType}, stav ${a.status}) — $balancePart id=${a.id}"
        }
        return ToolResult(lines.joinToString("\n"))
    }
}
