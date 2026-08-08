// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.application.port.`in`

import com.openbank.copilot.domain.ChatOutcome
import com.openbank.copilot.domain.ChatTurn

/**
 * Inbound port: one turn of the governed customer reasoning loop (ADR-0089). Implemented by
 * [com.openbank.copilot.application.CopilotChatService]; driven by the REST resource.
 *
 * [customerId] is always the authenticated subject the resource resolved from the bearer — never a
 * value the request body carried, so a turn can never be attributed to another customer.
 *
 * `partyId` is the same token's `party_id` claim (`sub` as fallback — the resolution customer-edge
 * already uses). It is recorded with the conversation purely so `PARTY_ERASED` can find it later,
 * and never participates in lookup: which conversation a customer resumes is still decided by
 * [customerId] alone. See `ConversationStore.deleteForParty` for why the two must both be kept.
 */
interface CopilotChatUseCase {

    /** Run the turn and return the complete outcome (disabled, refused, or replied). */
    suspend fun handle(turn: ChatTurn, customerId: String, partyId: String? = null): ChatOutcome

    /**
     * Same governed loop, streaming: [onChunk] receives each text chunk of the final answer as it
     * arrives, plus the `[PROGRESS:…]` / `[THEME_SPEC:…]` / `[PROPOSAL_END:…]` control markers the
     * client parses. Tool-call rounds emit no text, so nothing is streamed for them.
     */
    suspend fun handleStream(
        turn: ChatTurn,
        customerId: String,
        partyId: String? = null,
        onChunk: suspend (String) -> Unit,
    )
}
