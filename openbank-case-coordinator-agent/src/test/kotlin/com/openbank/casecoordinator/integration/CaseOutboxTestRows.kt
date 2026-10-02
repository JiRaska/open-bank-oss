// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.casecoordinator.integration

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.coroutines.awaitSuspending

/**
 * Production writes `case_outbox` with a native INSERT inside the workflow activity
 * (`CaseActivitiesImpl`), and the table's `id` is a `BIGSERIAL`: there is no `case_outbox_seq`, so
 * `persist()` of the entity (which asks Hibernate for the next pooled id from that sequence) fails
 * with `relation "case_outbox_seq" does not exist`. The conformance kits therefore seed a PENDING
 * row per [OutboxMessage] with the same native INSERT shape production uses.
 */
internal suspend fun seedCaseOutbox(message: OutboxMessage) {
    Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery<Int>(
                """
                INSERT INTO case_outbox
                    (event_id, synthetic, aggregate_id, event_type, payload, status, attempt_count, created_at, updated_at)
                VALUES (?1, ?2, ?3, ?4, ?5, ?6, 0, ?7, ?7)
                """.trimIndent(),
            )
                .setParameter(1, message.eventId)
                .setParameter(2, message.synthetic)
                .setParameter(3, message.aggregateId)
                .setParameter(4, message.eventType)
                .setParameter(5, message.payload)
                .setParameter(6, OutboxStatus.PENDING.name)
                .setParameter(7, message.createdAt)
                .executeUpdate()
        }
    }.awaitSuspending()
}
