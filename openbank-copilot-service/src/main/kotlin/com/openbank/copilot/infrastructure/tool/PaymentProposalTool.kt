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
 * ACTION tool — propose a payment from one of the customer's own accounts (ADR-0089 D2). It validates
 * the arguments and returns a structured proposal; it NEVER sends money. The app confirms the exact
 * amount + payee with the customer via the existing edge SCA (dynamic-linking) flow.
 */
@ApplicationScoped
class PaymentProposalTool : ActionProposalTool {

    override val name = "propose_payment"
    override val description =
        "Prepare (do NOT send) a payment from the customer's account for them to confirm with SCA."
    override val capability = "payment.propose"
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "fromAccountId" to mapOf("type" to "string", "description" to "Source account UUID"),
            "payeeIban" to mapOf("type" to "string", "description" to "Payee IBAN"),
            "amount" to mapOf("type" to "string", "description" to "Amount, e.g. 1500.00"),
            "currency" to mapOf("type" to "string", "description" to "ISO currency, default CZK"),
            "note" to mapOf("type" to "string", "description" to "Optional message for the payee"),
        ),
        "required" to listOf("fromAccountId", "payeeIban", "amount"),
    )

    override fun propose(arguments: JsonNode): ProposalResult {
        val accountId = arguments.text("fromAccountId") ?: return error("Chybí číslo zdrojového účtu.")
        if (runCatching { UUID.fromString(accountId) }.getOrNull() == null) return error("Neplatné číslo účtu.")

        val payee = arguments.text("payeeIban")?.uppercase() ?: return error("Chybí IBAN příjemce.")
        if (!IBAN.matches(payee)) return error("Neplatný IBAN příjemce.")

        val amount = arguments.text("amount")?.replace(",", ".")?.let { runCatching { BigDecimal(it) }.getOrNull() }
            ?: return error("Neplatná částka.")
        if (amount <= BigDecimal.ZERO) return error("Částka musí být kladná.")

        val currency = arguments.text("currency")?.uppercase()?.takeIf { CURRENCY.matches(it) } ?: "CZK"
        // Cap free text so an over-long / adversarial note can't bloat the action card (#998 nit 1).
        val note = arguments.text("note")?.take(MAX_NOTE)

        val fields = buildMap {
            put("fromAccountId", accountId)
            put("payeeIban", payee)
            put("amount", amount.toPlainString())
            put("currency", currency)
            if (note != null) put("note", note)
        }
        val summary = "Platba ${amount.toPlainString()} $currency na $payee z účtu $accountId"
        return ProposalResult(ActionProposal(ActionKind.PAYMENT, summary, fields))
    }

    private fun JsonNode.text(field: String): String? = get(field)?.asText()?.trim()?.takeIf { it.isNotBlank() }

    private fun error(message: String) = ProposalResult(error = message)

    private companion object {
        const val MAX_NOTE = 140
        val IBAN = Regex("[A-Z]{2}\\d{2}[A-Z0-9]{10,30}")
        val CURRENCY = Regex("[A-Z]{3}")
    }
}
