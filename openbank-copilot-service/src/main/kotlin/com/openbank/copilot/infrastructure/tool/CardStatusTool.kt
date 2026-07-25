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
 * READ tool — the caller's card portfolio: masked PAN, card type, network, status and expiry date.
 * PCI-safe: no full PAN or CVV ever crosses the edge (masked PAN only). Ownership enforced at the
 * customer edge (ADR-0065) — the edge scopes by JWT party. Model narrates status; never invents it.
 */
@ApplicationScoped
class CardStatusTool(@RestClient private val client: CustomerEdgeRestClient) : CopilotTool {

    override val name = "get_card_status"
    override val description =
        "List the customer's payment cards with their current status, masked card number, network " +
            "and expiry date. Use for questions about card status, whether a card is active or frozen."
    override val capability = "card.status.read"
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to emptyMap<String, Any>(),
    )

    override suspend fun call(arguments: JsonNode): ToolResult {
        val cards = try {
            client.listCards().awaitSuspending()
        } catch (e: WebApplicationException) {
            return ToolResult("Karty se nepodařilo načíst (HTTP ${e.response?.status ?: 0}).", isError = true)
        }
        if (cards.isEmpty()) return ToolResult("Klient nemá žádné platební karty.")

        val lines = cards.map { c ->
            buildString {
                append("${c.cardType} ${c.network} ${c.maskedPan}")
                if (c.expiryDate != null) append(" platná do ${c.expiryDate}")
                append(" — stav: ${c.status}")
                if (c.currency.isNotBlank()) append(", měna: ${c.currency}")
                if (c.id != null) append(" (id: ${c.id})")
            }
        }
        return ToolResult(lines.joinToString("\n"))
    }
}
