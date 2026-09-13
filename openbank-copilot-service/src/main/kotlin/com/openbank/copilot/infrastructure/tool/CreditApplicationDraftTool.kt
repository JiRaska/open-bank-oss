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

/**
 * L2 AGENT tool — prepare (do NOT submit) a credit application (ADR-0269 rule 5).
 *
 * The agent's entire remit in one class: it fills the form and hands it back. It cannot submit,
 * cannot accept an offer, cannot raise a limit and cannot draw funds — the app renders the proposal
 * as a non-AI-controlled card and the customer confirms it into the existing intake + SCA flow,
 * exactly as `PaymentProposalTool` does for money. Reused rather than reinvented, because the
 * reason is identical: an AI that can commit the customer is a different risk class from one that
 * can fill in a form.
 *
 * ## Why the level check is NOT here
 *
 * `ActionProposalTool.propose` is synchronous and never reaches the network — it validates
 * arguments and returns a structure. Making it suspend just to read a consent would push an
 * I/O call into a pure validation path. The level gate for L2 lives where the proposal is
 * CONFIRMED (the intake route the app posts to), which is also the only place where getting it
 * wrong could actually create an application. A draft that never leaves the phone is not the
 * hazard; a submitted one is.
 *
 * That said, the proposal carries no price and no approval language on purpose: a draft that
 * claimed a rate would be a quote the bank never made.
 */
@ApplicationScoped
class CreditApplicationDraftTool : ActionProposalTool {

    override val name = "credit_prepare_application"
    override val description =
        "Prepare (do NOT submit) a loan application for the customer to review and confirm. " +
            "Carries no rate, no instalment and no approval — it is a filled-in form, nothing more."
    override val capability = "credit.application.propose"
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "amount" to mapOf("type" to "string", "description" to "Requested amount, decimal"),
            "termMonths" to mapOf("type" to "integer", "description" to "Repayment term in months"),
        ),
        "required" to listOf("amount", "termMonths"),
    )

    override fun propose(arguments: JsonNode): ProposalResult {
        val amount = arguments.get("amount")?.asText()?.trim()?.takeIf { it.isNotBlank() }
            ?: return ProposalResult(error = "Uveďte prosím částku.")
        val parsed = runCatching { BigDecimal(amount) }.getOrNull()
            ?: return ProposalResult(error = "Neplatná částka.")
        if (parsed <= BigDecimal.ZERO) return ProposalResult(error = "Částka musí být kladná.")
        val term = arguments.get("termMonths")?.asInt() ?: 0
        if (term <= 0) return ProposalResult(error = "Uveďte prosím dobu splácení v měsících.")

        // Amount and term only — the same two fields the intake endpoint accepts, and for the same
        // reason: a customer may choose how much and how long, never at what price.
        val fields = mapOf("amount" to parsed.toPlainString(), "termMonths" to term.toString())
        return ProposalResult(
            ActionProposal(
                kind = ActionKind.CREDIT_APPLICATION,
                summary = "Žádost o půjčku ${parsed.toPlainString()} na $term měsíců (návrh k potvrzení)",
                fields = fields,
            ),
        )
    }
}
