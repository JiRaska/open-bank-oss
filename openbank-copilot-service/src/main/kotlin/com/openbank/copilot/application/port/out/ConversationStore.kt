// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.application.port.out

import com.openbank.copilot.domain.model.ChatMessage
import java.time.Instant

/**
 * Outbound port: short-lived conversation memory for the copilot (ADR-0089): without it every turn is stateless,
 * so a follow-up like "ano, potvrzuji" or "a na spořicím?" has no context and the assistant is
 * unusable for anything multi-turn.
 *
 * Only the *conversational* turns are persisted — USER messages and the final ASSISTANT text.
 * Tool calls, tool results and the system prompt are deliberately NOT stored: replaying a prior
 * turn's tool output would surface stale balances/rates, and the model re-runs the tools with the
 * live bearer whenever it needs fresh data. History gives the model what was *asked and answered*,
 * grounding stays live (D4).
 *
 * Isolation: the key is scoped by `customerId`, so a guessed/replayed `conversationId` can never
 * read another customer's history. History is capped ([MAX_MESSAGES]) and expires ([TTL_SECONDS],
 * sliding on each write) — it is transient UX state, not a record of account activity.
 *
 * Two adapters mirror [ProposalTokenStore]:
 * - `infrastructure.persistence.InMemoryConversationStore` — single-node dev/test
 *   (build property `copilot.token-store=memory`)
 * - `infrastructure.persistence.RedisConversationStore` — production Valkey/Redis (default)
 */
interface ConversationStore {
    /** Prior conversational turns for (customer, conversation), oldest first; empty if none/expired. */
    fun load(customerId: String, conversationId: String): List<ChatMessage>

    /**
     * Append [newTurns] (typically the current USER message + the final ASSISTANT reply) to the
     * conversation, trimming to the most recent [MAX_MESSAGES] and (re)setting the TTL.
     */
    fun append(customerId: String, conversationId: String, newTurns: List<ChatMessage>)

    /**
     * Erase every conversation held for [customerId], returning how many were removed (GDPR Art. 17 /
     * ADR-0117). Called by the `PARTY_ERASED` consumer.
     *
     * This is a **hard delete**, not the read-side `expires_at` filter [load] applies: an expired
     * conversation stops being *served* but its free-text message bodies stay on disk (and in every
     * base backup) until something removes the row. For an erasure request the difference between
     * "not served" and "not held" is the whole question.
     *
     * `suspend`, deliberately: both callers (the Kafka consumer and the retention sweep) already run
     * on a Vert.x context, where the blocking `VertxContextSupport.subscribeAndAwait` bridge the
     * read/write path uses would throw. Keeping erasure suspending means it never needs that bridge.
     */
    suspend fun deleteForCustomer(customerId: String): Long

    /** Erase one conversation. Scoped by [customerId], so it can never remove another customer's row. */
    suspend fun deleteConversation(customerId: String, conversationId: String): Long

    /**
     * Hard-delete every conversation whose `expires_at` is at or before [now], returning the count.
     * This is what turns the rolling TTL from a read filter into an actual retention guarantee.
     */
    suspend fun deleteExpired(now: Instant): Long

    companion object {
        /** Sliding TTL. A chat left idle past this loses its context — matches typical session UX. */
        const val TTL_SECONDS = 1800L // 30 min

        /** Keep the last N conversational messages (~10 exchanges) — bounds prompt size + token cost. */
        const val MAX_MESSAGES = 20

        /** Sentinel the resource substitutes for a missing client id; such turns are NOT persisted. */
        const val NO_MEMORY_ID = "new"

        /** A client conversation id longer than this is rejected (Redis key hygiene / abuse guard). */
        const val MAX_ID_LENGTH = 128

        /** True when [conversationId] is a real, persistable client id (not the stateless sentinel). */
        fun persistable(conversationId: String): Boolean = conversationId.isNotBlank() &&
            conversationId != NO_MEMORY_ID &&
            conversationId.length <= MAX_ID_LENGTH
    }
}
