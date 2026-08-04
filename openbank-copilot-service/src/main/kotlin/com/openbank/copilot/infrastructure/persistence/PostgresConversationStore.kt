// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.copilot.application.port.out.ConversationStore
import com.openbank.copilot.domain.model.ChatMessage
import com.openbank.copilot.domain.model.ChatRole
import io.quarkus.arc.properties.IfBuildProperty
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.jboss.logging.Logger
import java.time.Instant
import java.util.UUID

/** Rolling TTL for durable conversation history (ADR-0238 T1). 90 days matches typical banking app session retention. */
private const val T1_TTL_SECONDS = 90L * 24 * 3600

@Entity
@Table(name = "conversation_history")
class ConversationHistoryEntity : PanacheEntityBase() {
    @Id
    lateinit var id: UUID

    @Column(nullable = false, name = "customer_id")
    lateinit var customerId: String

    @Column(nullable = false, name = "conversation_id")
    lateinit var conversationId: String

    // text not jsonb: the Vert.x PG client returns JsonArray for jsonb columns, which cannot be
    // cast to String — the same V2 lesson from campaign-service and copilot's RedisConversationStore.
    @Column(nullable = false, columnDefinition = "text", name = "messages_json")
    lateinit var messagesJson: String

    @Column(nullable = false, name = "created_at")
    lateinit var createdAt: Instant

    @Column(nullable = false, name = "last_message_at")
    lateinit var lastMessageAt: Instant

    @Column(nullable = false, name = "expires_at")
    lateinit var expiresAt: Instant
}

/**
 * ADR-0238 T1 — durable conversation history in Postgres (#3710).
 *
 * Replaces the Redis adapter as the production conversation store: history survives pod restarts,
 * is resumable across devices (any session presenting the same conversationId gets the same
 * context), and obeys a 90-day rolling TTL updated on every append. The isolation contract from
 * [ConversationStore] is preserved: key = (customerId, conversationId), so a guessed conversationId
 * can never read another customer's messages.
 *
 * Wire control: `copilot.conversation-store: postgres` (default in prod); the `memory` and `redis`
 * values are kept for dev and tests via the [UnlessBuildProperty] guards on the other adapters.
 */
@ApplicationScoped
@IfBuildProperty(name = "copilot.conversation-store", stringValue = "postgres", enableIfMissing = true)
class PostgresConversationStore(private val mapper: ObjectMapper) :
    ConversationStore,
    PanacheRepository<ConversationHistoryEntity> {

    private val log = Logger.getLogger(PostgresConversationStore::class.java)

    override fun load(customerId: String, conversationId: String): List<ChatMessage> {
        if (!ConversationStore.persistable(conversationId)) return emptyList()
        return vtx {
            Panache.withSession {
                find(
                    "customerId = ?1 and conversationId = ?2 and expiresAt > ?3",
                    customerId,
                    conversationId,
                    Instant.now(),
                )
                    .firstResult<ConversationHistoryEntity>()
            }.awaitSuspending()
        }?.let { entity ->
            runCatching {
                mapper.readValue<List<StoredMessage>>(entity.messagesJson)
                    .map { ChatMessage(it.role, it.content) }
            }.getOrElse { e ->
                log.warnf(
                    e,
                    "PostgresConversationStore: failed to deserialise conversation %s/%s, ignoring",
                    customerId,
                    conversationId,
                )
                emptyList()
            }
        } ?: emptyList()
    }

    override fun append(customerId: String, conversationId: String, newTurns: List<ChatMessage>) {
        if (!ConversationStore.persistable(conversationId) || newTurns.isEmpty()) return
        vtx {
            Panache.withTransaction {
                val existing = find("customerId = ?1 and conversationId = ?2", customerId, conversationId)
                    .firstResult<ConversationHistoryEntity>()
                existing.flatMap { entity ->
                    val prior = if (entity != null) {
                        runCatching {
                            mapper.readValue<List<StoredMessage>>(entity.messagesJson)
                        }.getOrElse { emptyList() }
                    } else {
                        emptyList()
                    }
                    val merged = (prior + newTurns.map { StoredMessage(it.role, it.content) })
                        .takeLast(ConversationStore.MAX_MESSAGES)
                    val now = Instant.now()
                    val upsert = entity ?: ConversationHistoryEntity().apply {
                        id = UUID.randomUUID()
                        this.customerId = customerId
                        this.conversationId = conversationId
                        createdAt = now
                    }
                    upsert.messagesJson = mapper.writeValueAsString(merged)
                    upsert.lastMessageAt = now
                    upsert.expiresAt = now.plusSeconds(T1_TTL_SECONDS)
                    Panache.getSession().flatMap { s -> s.merge(upsert) }
                }
            }.awaitSuspending()
        }
    }

    data class StoredMessage(val role: ChatRole = ChatRole.USER, val content: String = "")

    private fun <T> vtx(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni() }
}
