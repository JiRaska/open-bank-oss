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
 * ACTION tool — propose opening a dispute on one of the customer's own transactions (ADR-0089 D2).
 * Validates the transaction id + reason and returns a structured proposal; it NEVER files the dispute.
 * The app confirms it with the customer (and SCA where required) before it reaches dispute-service.
 */
@ApplicationScoped
class DisputeProposalTool : ActionProposalTool {

    override val name = "propose_dispute"
    override val description = "Prepare (do NOT file) a dispute on one of the customer's transactions for confirmation."
    override val capability = "dispute.open.propose"
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "transactionId" to mapOf("type" to "string", "description" to "Transaction UUID"),
            "reason" to mapOf("type" to "string", "description" to "Why the customer disputes it"),
        ),
        "required" to listOf("transactionId", "reason"),
    )

    override fun propose(arguments: JsonNode): ProposalResult {
        val txId = arguments.text("transactionId") ?: return ProposalResult(error = "Chybí číslo transakce.")
        if (runCatching { UUID.fromString(txId) }.getOrNull() == null) {
            return ProposalResult(error = "Neplatné číslo transakce.")
        }
        val reason = arguments.text("reason")?.take(MAX_REASON)
            ?: return ProposalResult(error = "Uveďte prosím důvod reklamace.")
        val fields = mapOf("transactionId" to txId, "reason" to reason)
        return ProposalResult(ActionProposal(ActionKind.DISPUTE, "Reklamace transakce $txId", fields))
    }

    private fun JsonNode.text(field: String): String? = get(field)?.asText()?.trim()?.takeIf { it.isNotBlank() }

    private companion object {
        const val MAX_REASON = 280
    }
}
