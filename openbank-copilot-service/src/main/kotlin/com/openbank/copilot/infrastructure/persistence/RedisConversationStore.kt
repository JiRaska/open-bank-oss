// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.copilot.application.port.out.ConversationStore
import com.openbank.copilot.domain.model.ChatMessage
import com.openbank.copilot.domain.model.ChatRole
import io.quarkus.arc.properties.UnlessBuildProperty
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.value.SetArgs
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.jboss.logging.Logger

/**
 * Production conversation memory (Valkey/Redis). Stores one JSON array of [StoredMessage] per
 * (customer, conversation) under [key], with a sliding [ConversationStore.TTL_SECONDS] TTL.
 *
 * Only role + content are persisted (see [ConversationStore]); tool calls/results are never stored.
 */
@ApplicationScoped
@UnlessBuildProperty(name = "copilot.token-store", stringValue = "memory")
class RedisConversationStore @Inject constructor(
    private val redis: ReactiveRedisDataSource,
    private val mapper: ObjectMapper,
) : ConversationStore {
    private val log = Logger.getLogger(RedisConversationStore::class.java)
    private val values by lazy { redis.value(String::class.java) }

    /** Wire form — deliberately minimal so a schema drift in [ChatMessage] can't corrupt history. */
    data class StoredMessage(val role: ChatRole = ChatRole.USER, val content: String = "")

    override fun load(customerId: String, conversationId: String): List<ChatMessage> {
        if (!ConversationStore.persistable(conversationId)) return emptyList()
        val json = runBlocking { values.get(key(customerId, conversationId)).awaitSuspending() }
            ?: return emptyList()
        return runCatching {
            mapper.readValue(json, Array<StoredMessage>::class.java)
                .map { ChatMessage(it.role, it.content) }
        }.getOrElse { e ->
            log.warnf(e, "RedisConversationStore: failed to deserialise conversation, ignoring")
            emptyList()
        }
    }

    override fun append(customerId: String, conversationId: String, newTurns: List<ChatMessage>) {
        if (!ConversationStore.persistable(conversationId) || newTurns.isEmpty()) return
        runBlocking {
            val existing = load(customerId, conversationId)
            val merged = (existing + newTurns)
                .map { StoredMessage(it.role, it.content) }
                .takeLast(ConversationStore.MAX_MESSAGES)
            val json = mapper.writeValueAsString(merged)
            values.set(key(customerId, conversationId), json, SetArgs().ex(ConversationStore.TTL_SECONDS))
                .awaitSuspending()
        }
        log.debugf(
            "RedisConversationStore: appended %d turn(s) customer=%s conversation=%s ttl=%ds",
            newTurns.size,
            customerId,
            conversationId,
            ConversationStore.TTL_SECONDS,
        )
    }

    // customerId precedes conversationId, so a crafted conversationId can only ever land inside the
    // SAME customer's namespace — it can never escape to another customer's history.
    private fun key(customerId: String, conversationId: String) = "copilot:conv:$customerId:$conversationId"
}
