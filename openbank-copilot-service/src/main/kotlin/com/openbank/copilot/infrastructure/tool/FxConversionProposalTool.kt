// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.infrastructure.tool

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.copilot.application.port.out.ActionProposalTool
import com.openbank.copilot.application.port.out.ProposalResult
import com.openbank.copilot.domain.ActionKind
import com.openbank.copilot.domain.ActionProposal
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.util.UUID

/**
 * ACTION tool — propose an FX conversion from one of the customer's own currency pockets (ADR-0089
 * D2). Validates all arguments and returns a structured proposal; it NEVER executes the conversion.
 * The app routes the proposal into the existing fx-service conversion + SCA flow (dynamic-linking
 * bound to amount + currency pair) where the customer confirms with a device credential.
 */
@ApplicationScoped
class FxConversionProposalTool : ActionProposalTool {

    override val name = "propose_fx_conversion"
    override val description =
        "Prepare (do NOT execute) an FX conversion between two currency pockets for the customer " +
            "to confirm. The actual conversion only happens after the customer approves via SCA."
    override val capability = "fx.conversion.propose"
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "accountId" to mapOf(
                "type" to "string",
                "description" to "Account UUID holding the source currency pocket",
            ),
            "fromCurrency" to mapOf("type" to "string", "description" to "ISO 4217 source currency, e.g. EUR"),
            "toCurrency" to mapOf("type" to "string", "description" to "ISO 4217 target currency, e.g. CZK"),
            "amount" to mapOf("type" to "string", "description" to "Amount to convert, e.g. 500.00"),
        ),
        "required" to listOf("accountId", "fromCurrency", "toCurrency", "amount"),
    )

    override fun propose(arguments: JsonNode): ProposalResult {
        val accountId = arguments.text("accountId") ?: return error("Chybí číslo účtu (accountId).")
        if (runCatching { UUID.fromString(accountId) }.getOrNull() == null) return error("Neplatné accountId.")

        val from = arguments.text("fromCurrency")?.uppercase()?.takeIf { CURRENCY.matches(it) }
            ?: return error("Chybí nebo neplatná zdrojová měna (fromCurrency).")
        val to = arguments.text("toCurrency")?.uppercase()?.takeIf { CURRENCY.matches(it) }
            ?: return error("Chybí nebo neplatná cílová měna (toCurrency).")
        if (from == to) return error("Zdrojová a cílová měna jsou stejné.")

        val amount = arguments.text("amount")?.replace(",", ".")
            ?.let { runCatching { BigDecimal(it) }.getOrNull() }
            ?: return error("Neplatná částka.")
        if (amount <= BigDecimal.ZERO) return error("Částka musí být kladná.")

        val fields = mapOf(
            "accountId" to accountId,
            "fromCurrency" to from,
            "toCurrency" to to,
            "amount" to amount.toPlainString(),
        )
        val summary = "Konverze ${amount.toPlainString()} $from → $to z účtu $accountId"
        return ProposalResult(ActionProposal(ActionKind.FX_CONVERSION, summary, fields))
    }

    private fun JsonNode.text(field: String): String? = get(field)?.asText()?.trim()?.takeIf { it.isNotBlank() }

    private fun error(message: String) = ProposalResult(error = message)

    private companion object {
        val CURRENCY = Regex("[A-Z]{3}")
    }
}
