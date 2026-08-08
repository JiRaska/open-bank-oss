// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.copilot.application.port.out.ConversationStore
import com.openbank.copilot.domain.model.ChatMessage
import com.openbank.copilot.domain.model.ChatRole
import io.quarkus.arc.properties.IfBuildProperty
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.value.SetArgs
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.jboss.logging.Logger
import java.time.Instant

/**
 * Production conversation memory (Valkey/Redis). Stores one JSON array of [StoredMessage] per
 * (customer, conversation) under [key], with a sliding [ConversationStore.TTL_SECONDS] TTL.
 *
 * Only role + content are persisted (see [ConversationStore]); tool calls/results are never stored.
 */
@ApplicationScoped
@IfBuildProperty(name = "copilot.conversation-store", stringValue = "redis")
class RedisConversationStore @Inject constructor(
    private val redis: ReactiveRedisDataSource,
    private val mapper: ObjectMapper,
) : ConversationStore {
    private val log = Logger.getLogger(RedisConversationStore::class.java)
    private val values by lazy { redis.value(String::class.java) }
    private val keys by lazy { redis.key(String::class.java) }

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

    override fun append(customerId: String, conversationId: String, newTurns: List<ChatMessage>, partyId: String?) {
        if (!ConversationStore.persistable(conversationId) || newTurns.isEmpty()) return
        runBlocking {
            val existing = load(customerId, conversationId)
            val merged = (existing + newTurns)
                .map { StoredMessage(it.role, it.content) }
                .takeLast(ConversationStore.MAX_MESSAGES)
            val json = mapper.writeValueAsString(merged)
            values.set(key(customerId, conversationId), json, SetArgs().ex(ConversationStore.TTL_SECONDS))
                .awaitSuspending()
            // Erasure index (#3881): PARTY_ERASED carries partyId, the history key carries `sub`,
            // and those are not the same value. Without this pointer an erasure scans a prefix that
            // matches nothing and reports success. Same TTL, so it cannot outlive what it points at.
            if (partyId != null && partyId != customerId) {
                values.set(
                    partyIndexKey(partyId, conversationId),
                    customerId,
                    SetArgs().ex(ConversationStore.TTL_SECONDS),
                ).awaitSuspending()
            }
        }
        log.debugf(
            "RedisConversationStore: appended %d turn(s) customer=%s conversation=%s ttl=%ds",
            newTurns.size,
            customerId,
            conversationId,
            ConversationStore.TTL_SECONDS,
        )
    }

    // ---- Erasure (GDPR Art. 17 / ADR-0117, #3870) --------------------------------------------

    override suspend fun deleteForParty(partyId: String): Long {
        // Follow the erasure index first: history is keyed by `sub`, the event carries `partyId`.
        val idxPrefix = "copilot:conv:party:$partyId:"
        val indexKeys = keys.keys("$idxPrefix*").awaitSuspending().filter { it.startsWith(idxPrefix) }
        var viaIndex = 0L
        indexKeys.forEach { idx ->
            val subject = values.get(idx).awaitSuspending()
            val conversationId = idx.removePrefix(idxPrefix)
            if (subject != null && ConversationStore.persistable(conversationId)) {
                viaIndex += keys.del(key(subject, conversationId)).awaitSuspending().toLong()
            }
            keys.del(idx).awaitSuspending()
        }
        // …then the ADR-0069 case, where `sub` IS the party id and no index entry was written.
        val prefix = "copilot:conv:$partyId:"
        // KEYS takes a glob, and customerId is caller-derived, so a metacharacter in it could widen
        // the pattern across customers. The returned keys are therefore re-filtered on the LITERAL
        // prefix before anything is deleted — the glob only narrows the scan, it never authorises.
        val matched = keys.keys("$prefix*").awaitSuspending()
            .filter { it.startsWith(prefix) && !it.startsWith("copilot:conv:party:") }
        if (matched.isEmpty()) return viaIndex
        // Deleted one key at a time rather than with a spread vararg: an erasure set is tiny (a
        // customer's open conversations), and the spread would copy the array on every call.
        matched.forEach { keys.del(it).awaitSuspending() }
        log.infof("RedisConversationStore: erased %d conversation(s) for a party", matched.size + viaIndex)
        return matched.size.toLong() + viaIndex
    }

    override suspend fun deleteConversation(customerId: String, conversationId: String): Long {
        if (!ConversationStore.persistable(conversationId)) return 0L
        return keys.del(key(customerId, conversationId)).awaitSuspending().toLong()
    }

    /**
     * No-op by construction: Redis evicts on its own TTL, so there is never a past-expiry key left
     * to sweep. Returning 0 is the honest answer, not a silent success.
     */
    override suspend fun deleteExpired(now: Instant): Long = 0L

    // customerId precedes conversationId, so a crafted conversationId can only ever land inside the
    // SAME customer's namespace — it can never escape to another customer's history.
    private fun key(customerId: String, conversationId: String) = "copilot:conv:$customerId:$conversationId"

    /** Erasure pointer partyId -> the `sub` the history is keyed under. Holds no message content. */
    private fun partyIndexKey(partyId: String, conversationId: String) = "copilot:conv:party:$partyId:$conversationId"
}
