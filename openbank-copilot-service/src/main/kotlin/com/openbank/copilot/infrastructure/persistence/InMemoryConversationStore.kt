// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.infrastructure.persistence

import com.openbank.copilot.application.port.out.ConversationStore
import com.openbank.copilot.domain.model.ChatMessage
import io.quarkus.arc.properties.IfBuildProperty
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Single-node conversation memory for dev/test (active with `copilot.token-store=memory`, mirroring
 * [InMemoryProposalTokenStore]). Same contract as [RedisConversationStore]; entries expire via [clock].
 */
@ApplicationScoped
@IfBuildProperty(name = "copilot.token-store", stringValue = "memory")
class InMemoryConversationStore(private val clock: Clock) : ConversationStore {
    private val log = Logger.getLogger(InMemoryConversationStore::class.java)

    private data class Entry(val messages: List<ChatMessage>, val expiresAt: Instant)

    private val store = ConcurrentHashMap<String, Entry>()

    override fun load(customerId: String, conversationId: String): List<ChatMessage> {
        if (!ConversationStore.persistable(conversationId)) return emptyList()
        val entry = store[key(customerId, conversationId)] ?: return emptyList()
        if (Instant.now(clock).isAfter(entry.expiresAt)) {
            store.remove(key(customerId, conversationId))
            return emptyList()
        }
        return entry.messages
    }

    override fun append(customerId: String, conversationId: String, newTurns: List<ChatMessage>) {
        if (!ConversationStore.persistable(conversationId) || newTurns.isEmpty()) return
        evictExpired()
        val k = key(customerId, conversationId)
        // Strip everything but role + content, mirroring the Redis wire form.
        val merged = (load(customerId, conversationId) + newTurns)
            .map { ChatMessage(it.role, it.content) }
            .takeLast(ConversationStore.MAX_MESSAGES)
        store[k] = Entry(merged, Instant.now(clock).plusSeconds(ConversationStore.TTL_SECONDS))
        log.debugf("InMemoryConversationStore: appended %d turn(s) key=%s", newTurns.size, k)
    }

    private fun evictExpired() {
        val now = Instant.now(clock)
        store.entries.removeIf { now.isAfter(it.value.expiresAt) }
    }

    private fun key(customerId: String, conversationId: String) = "$customerId|$conversationId"
}
