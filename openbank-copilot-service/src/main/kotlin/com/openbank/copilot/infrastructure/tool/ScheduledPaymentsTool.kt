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
 * READ tool — the caller's standing orders (trvalé příkazy): creditor, amount, frequency and next
 * execution date. Ownership is enforced at the customer edge — the edge scopes by JWT party so only
 * the caller's own orders are returned. Model narrates; never invents.
 */
@ApplicationScoped
class ScheduledPaymentsTool(@RestClient private val client: CustomerEdgeRestClient) : CopilotTool {

    override val name = "get_scheduled_payments"
    override val description =
        "List the customer's standing orders (trvalé příkazy / scheduled payments): payee IBAN, " +
            "amount, currency, frequency and next execution date. Use for questions about recurring " +
            "payments, who money is sent to regularly, or when the next payment goes out."
    override val capability = "account.scheduled-payments.read"
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to emptyMap<String, Any>(),
    )

    override suspend fun call(arguments: JsonNode): ToolResult {
        val orders = try {
            client.listStandingOrders().awaitSuspending()
        } catch (e: WebApplicationException) {
            return ToolResult(
                "Trvalé příkazy se nepodařilo načíst (HTTP ${e.response?.status ?: 0}).",
                isError = true,
            )
        }
        if (orders.isEmpty()) return ToolResult("Klient nemá žádné trvalé příkazy.")

        val lines = orders.map { o ->
            buildString {
                append("${o.amount} ${o.currency} → ${o.creditorIban} (${o.creditorName})")
                append(", četnost: ${o.frequency}")
                if (o.nextExecutionDate != null) append(", příští platba: ${o.nextExecutionDate}")
                append(", stav: ${o.status}")
                if (!o.remittanceInfo.isNullOrBlank()) append(", zpráva: ${o.remittanceInfo}")
            }
        }
        return ToolResult(lines.joinToString("\n"))
    }
}
