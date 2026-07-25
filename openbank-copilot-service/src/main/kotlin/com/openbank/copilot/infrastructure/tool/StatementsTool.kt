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
 * READ tool — closed-period statement records for one of the caller's own accounts (opening/closing
 * balance, entry count, period dates). Ownership is enforced at the customer edge (IDOR guard). The
 * model narrates the metadata; it never invents balances or generates the document (that is a
 * separate on-demand render flow, outside the assistant scope).
 */
@ApplicationScoped
class StatementsTool(@RestClient private val client: CustomerEdgeRestClient) : CopilotTool {

    override val name = "get_account_statement"
    override val description =
        "List the closed-period statement records for a specific account: opening and closing " +
            "balances, transaction count and period dates. Use when the customer asks about account " +
            "statements, monthly summaries or period balances. Requires accountId."
    override val capability = "account.statements.read"
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "accountId" to mapOf("type" to "string", "description" to "Account UUID to fetch statements for"),
        ),
        "required" to listOf("accountId"),
    )

    override suspend fun call(arguments: JsonNode): ToolResult {
        val accountIdStr = arguments.get("accountId")?.asText()?.trim()?.takeIf { it.isNotBlank() }
            ?: return ToolResult("Chybí accountId.", isError = true)
        val accountId = runCatching { UUID.fromString(accountIdStr) }.getOrNull()
            ?: return ToolResult("Neplatné accountId: $accountIdStr", isError = true)

        val statements = try {
            client.listStatements(accountId).awaitSuspending()
        } catch (e: WebApplicationException) {
            return ToolResult(
                "Výpisy se nepodařilo načíst (HTTP ${e.response?.status ?: 0}).",
                isError = true,
            )
        }
        if (statements.isEmpty()) return ToolResult("Pro tento účet nejsou k dispozici žádné výpisy.")

        val lines = statements.map { s ->
            buildString {
                append("Výpis ${s.periodFrom ?: "?"} – ${s.periodTo ?: "?"}")
                append(" (${s.pocketCurrency})")
                append(": počáteční zůstatek ${s.openingBalance}, konečný ${s.closingBalance}")
                append(", ${s.entryCount} pohybů")
                append(", č. výpisu: ${s.legalSequenceNumber}")
            }
        }
        return ToolResult(lines.joinToString("\n"))
    }
}
