// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.application.port.out

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.copilot.domain.ActionProposal

/** Outcome of building a proposal: either a validated [proposal] or a customer-facing [error]. */
data class ProposalResult(val proposal: ActionProposal? = null, val error: String? = null)

/**
 * Outbound port for a money-path ACTION exposed to the model (ADR-0089 D2). By construction it can ONLY *propose*:
 * [propose] validates the arguments and returns a structured [ActionProposal] — there is no execute
 * path on this interface. Execution happens later in the existing customer-edge payment + SCA flow,
 * after the customer confirms the exact amount and payee in a non-AI-controlled card. The policy gate
 * authorises the [capability]; HITL + SCA are enforced downstream, never here.
 */
interface ActionProposalTool {
    val name: String
    val description: String
    val capability: String
    val inputSchema: Map<String, Any>

    fun propose(arguments: JsonNode): ProposalResult
}
