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
import java.util.UUID

/**
 * ACTION tool — propose freezing one of the customer's own cards (ADR-0089 D2). Validates the card id
 * and returns a structured proposal; it NEVER applies the freeze. The app confirms it with the
 * customer via the existing card flow. Proposing a freeze (precautionary) is low-risk, but it is still
 * a state change, so it goes through the same propose-only path as a payment.
 */
@ApplicationScoped
class CardFreezeProposalTool : ActionProposalTool {

    override val name = "propose_card_freeze"
    override val description = "Prepare (do NOT apply) a freeze on the customer's card for them to confirm."
    override val capability = "card.freeze.propose"
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "cardId" to mapOf("type" to "string", "description" to "Card UUID"),
            "reason" to mapOf("type" to "string", "description" to "Optional reason (e.g. lost)"),
        ),
        "required" to listOf("cardId"),
    )

    override fun propose(arguments: JsonNode): ProposalResult {
        val cardId = arguments.text("cardId") ?: return ProposalResult(error = "Chybí číslo karty.")
        if (runCatching { UUID.fromString(cardId) }.getOrNull() == null) {
            return ProposalResult(error = "Neplatné číslo karty.")
        }
        val reason = arguments.text("reason")?.take(MAX_REASON)
        val fields = buildMap {
            put("cardId", cardId)
            if (reason != null) put("reason", reason)
        }
        return ProposalResult(ActionProposal(ActionKind.CARD_FREEZE, "Zablokování karty $cardId", fields))
    }

    private fun JsonNode.text(field: String): String? = get(field)?.asText()?.trim()?.takeIf { it.isNotBlank() }

    private companion object {
        const val MAX_REASON = 140
    }
}
