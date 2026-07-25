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
 * READ tool — the customer's own accounts (ADR-0089 Phase 1). Runs as the customer via the propagated
 * bearer through the customer edge, which scopes the list to the caller's party (ADR-0065). The model
 * needs this to resolve an account id before [BalanceTool], and to answer "what accounts/products do
 * I have". Figures are NARRATED from tool output, never invented (ADR-0089 D4).
 */
@ApplicationScoped
class AccountsTool(@RestClient private val client: CustomerEdgeRestClient) : CopilotTool {

    override val name = "get_my_accounts"
    override val description =
        "List the customer's own accounts (account id, IBAN, type, currency, status). " +
            "Call this first to find an account id before get_account_balance, or to answer what " +
            "accounts/products the customer has."
    override val capability = "account.read"
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to emptyMap<String, Any>(),
    )

    override suspend fun call(arguments: JsonNode): ToolResult = try {
        val accounts = client.listAccounts().awaitSuspending().data
        if (accounts.isEmpty()) {
            ToolResult("Klient nemá žádné účty.")
        } else {
            ToolResult(
                accounts.joinToString("\n") { a ->
                    "účet ${a.accountNumber} (typ ${a.accountType}, měna ${a.currencyCode}, " +
                        "stav ${a.status}) id=${a.id}"
                },
            )
        }
    } catch (e: WebApplicationException) {
        ToolResult("Účty se nepodařilo načíst (HTTP ${e.response?.status ?: 0}).", isError = true)
    }
}
