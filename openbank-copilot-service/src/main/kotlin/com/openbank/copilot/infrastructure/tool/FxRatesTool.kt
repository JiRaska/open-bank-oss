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
 * READ tool — the bank's current FX rate sheet (kurzovní lístek) as the edge projects it: mid-price,
 * bid, ask, ČNB reference mid and spread percentage. Ownership is not relevant — rates are the same
 * for all customers; the edge requires a valid ROLE_CUSTOMER bearer but does not scope by party.
 * Model narrates the figures; it never invents them (ADR-0089 D4).
 */
@ApplicationScoped
class FxRatesTool(@RestClient private val client: CustomerEdgeRestClient) : CopilotTool {

    override val name = "get_fx_rates"
    override val description =
        "Fetch the current FX rate sheet (kurzovní lístek): mid-price, buy/sell rates and the ČNB " +
            "reference spread for each currency pair. Use this for any question about exchange rates, " +
            "currency conversion costs or the current EUR/CZK rate."
    override val capability = "fx.rates.read"
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to emptyMap<String, Any>(),
    )

    override suspend fun call(arguments: JsonNode): ToolResult {
        val rates = try {
            client.getFxRates().awaitSuspending()
        } catch (e: WebApplicationException) {
            return ToolResult("Kurzovní lístek se nepodařilo načíst (HTTP ${e.response?.status ?: 0}).", isError = true)
        }
        if (rates.isEmpty()) return ToolResult("Kurzovní lístek je momentálně prázdný.")

        val lines = rates.map { r ->
            buildString {
                append("${r.base}/${r.quote}: střed ${r.rate}")
                if (r.bid != null) append(", nákup ${r.bid}")
                if (r.ask != null) append(", prodej ${r.ask}")
                if (r.refMid != null) append(", ČNB ref ${r.refMid}")
                if (r.spreadPct != null) append(", marže ${r.spreadPct}%")
            }
        }
        return ToolResult(lines.joinToString("\n"))
    }
}
