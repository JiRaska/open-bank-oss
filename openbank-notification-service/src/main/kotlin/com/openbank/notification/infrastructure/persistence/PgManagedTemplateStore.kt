// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.persistence

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.notification.domain.model.ManagedNotificationTemplate
import com.openbank.notification.domain.model.ManagedTemplateState
import com.openbank.notification.domain.model.NotificationChannel
import com.openbank.notification.domain.model.NotificationLanguage
import com.openbank.notification.domain.model.NotificationTemplate
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@ApplicationScoped
class PgManagedTemplateStore(private val pool: PgPool) {
    private data class Key(
        val template: NotificationTemplate,
        val language: NotificationLanguage,
        val channel: NotificationChannel,
    )

    private data class Cached(val copy: ManagedNotificationTemplate?, val checkedAtNanos: Long)

    private val cache = ConcurrentHashMap<Key, Cached>()
    private val log = Logger.getLogger(PgManagedTemplateStore::class.java)

    suspend fun create(
        template: NotificationTemplate,
        language: NotificationLanguage,
        channel: NotificationChannel,
        subject: String,
        body: String,
        actor: String,
    ): ManagedNotificationTemplate {
        // Validate the closed schema before SQL, including disallowed editable identities.
        val id = Ids.newId()
        ManagedNotificationTemplate(
            id, template, language, channel, 0, subject, body,
            ManagedTemplateState.DRAFT, actor, java.time.Instant.now(),
        )
        val row = pool.preparedQuery(
            """
            INSERT INTO managed_notification_templates
                (id, template, language, channel, state, subject, body, created_by, created_at)
            VALUES ($1, $2, $3, $4, 'DRAFT', $5, $6, $7, now())
            RETURNING *
            """.trimIndent(),
        ).execute(
            Tuple.tuple().addValue(id).addValue(template.name).addValue(language.name)
                .addValue(channel.name).addValue(subject).addValue(body).addValue(actor),
        )
            .awaitSuspending().first()
        return row.toManagedTemplate()
    }

    suspend fun find(id: UUID): ManagedNotificationTemplate? = pool.preparedQuery(
        "SELECT * FROM managed_notification_templates WHERE id = $1",
    ).execute(Tuple.of(id)).awaitSuspending().firstOrNull()?.toManagedTemplate()

    suspend fun list(template: NotificationTemplate): List<ManagedNotificationTemplate> = pool.preparedQuery(
        "SELECT * FROM managed_notification_templates WHERE template = $1 ORDER BY revision DESC LIMIT 100",
    ).execute(Tuple.of(template.name)).awaitSuspending().map { it.toManagedTemplate() }

    suspend fun publish(id: UUID, actor: String): ManagedNotificationTemplate? {
        val published = pool.preparedQuery(
            """
            UPDATE managed_notification_templates
            SET state = 'PUBLISHED', published_by = $2, published_at = now()
            WHERE id = $1 AND state = 'DRAFT' AND created_by <> $2
            RETURNING *
            """.trimIndent(),
        ).execute(Tuple.of(id, actor)).awaitSuspending().firstOrNull()?.toManagedTemplate()
        if (published != null) cache.remove(Key(published.template, published.language, published.channel))
        return published
    }

    fun latestPublished(
        template: NotificationTemplate,
        language: NotificationLanguage,
        channel: NotificationChannel,
    ): Uni<ManagedNotificationTemplate?> {
        val key = Key(template, language, channel)
        val previous = cache[key]
        if (previous != null && System.nanoTime() - previous.checkedAtNanos < CACHE_TTL_NANOS) {
            return Uni.createFrom().item(previous.copy)
        }
        return pool.preparedQuery(
            """
            SELECT * FROM managed_notification_templates
            WHERE template = $1 AND language = $2 AND channel = $3 AND state = 'PUBLISHED'
            ORDER BY published_at DESC, revision DESC LIMIT 1
            """.trimIndent(),
        ).execute(Tuple.of(template.name, language.name, channel.name))
            .map { rows -> rows.firstOrNull()?.toManagedTemplate() }
            .invoke { copy -> cache[key] = Cached(copy, System.nanoTime()) }
            .onFailure().recoverWithItem { failure ->
                log.warnf(
                    failure,
                    "managed notification copy unavailable template=%s; using last known or built-in",
                    template,
                )
                cache[key]?.copy
            }
    }

    private companion object {
        val CACHE_TTL_NANOS: Long = TimeUnit.SECONDS.toNanos(30)
    }
}

private fun Row.toManagedTemplate(): ManagedNotificationTemplate = ManagedNotificationTemplate(
    id = getUUID("id"),
    template = NotificationTemplate.valueOf(getString("template")),
    language = NotificationLanguage.valueOf(getString("language")),
    channel = NotificationChannel.valueOf(getString("channel")),
    revision = getLong("revision"),
    subject = getString("subject"),
    body = getString("body"),
    state = ManagedTemplateState.valueOf(getString("state")),
    createdBy = getString("created_by"),
    createdAt = getOffsetDateTime("created_at").toInstant(),
    publishedBy = getString("published_by"),
    publishedAt = getOffsetDateTime("published_at")?.toInstant(),
)
