// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.persistence.repository

import com.openbank.notification.domain.model.NotificationTemplate
import com.openbank.notification.domain.model.OperatorMessagePurpose
import com.openbank.notification.infrastructure.persistence.entity.OperatorMessageEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class OperatorMessageRepository : PanacheRepository<OperatorMessageEntity> {

    /** Born PENDING_APPROVAL (see V10 migration comment on why there is no separate DRAFT state). */
    suspend fun create(
        id: UUID,
        partyId: UUID,
        template: NotificationTemplate,
        referenceId: String,
        purpose: OperatorMessagePurpose,
        makerId: String,
    ): OperatorMessageEntity {
        val now = Instant.now()
        val entity = OperatorMessageEntity().also {
            it.messageId = id
            it.partyId = partyId
            it.template = template.name
            it.referenceId = referenceId
            it.purpose = purpose.name
            it.status = "PENDING_APPROVAL"
            it.makerId = makerId
            it.createdAt = now
            it.updatedAt = now
        }
        return Panache.withTransaction { persist(entity) }.awaitSuspending()
    }

    suspend fun findByMessageId(id: UUID): OperatorMessageEntity? =
        Panache.withSession { find("messageId", id).firstResult() }.awaitSuspending()

    /** Second operator's discovery surface — ApprovalStore itself cannot be listed. */
    suspend fun pageByStatus(status: String, page: Int, size: Int): Pair<List<OperatorMessageEntity>, Long> =
        Panache.withSession {
            val query = find("status", status)
            query.count().flatMap { total -> query.page(page, size).list().map { items -> items to total } }
        }.awaitSuspending()

    /**
     * PENDING_APPROVAL -> SENT, after dispatch() has already succeeded. Scoped to rows still
     * PENDING_APPROVAL so a duplicate retry (e.g. a maker double-submitting once already
     * approved) cannot flip an already-SENT or already-REJECTED row back.
     */
    suspend fun markSent(id: UUID) {
        Panache.withTransaction {
            update(
                "status = ?1, updatedAt = ?2 where messageId = ?3 and status = 'PENDING_APPROVAL'",
                "SENT",
                Instant.now(),
                id,
            )
        }.awaitSuspending()
    }

    /**
     * PENDING_APPROVAL -> REJECTED. Called from the checker's reject decision, NOT from the
     * maker's retry — ApprovalStore refuses a retry against a non-APPROVED decision, so the
     * maker's own submit body never runs to record a rejection; this is the only place that does.
     */
    suspend fun markRejected(id: UUID) {
        Panache.withTransaction {
            update(
                "status = ?1, updatedAt = ?2 where messageId = ?3 and status = 'PENDING_APPROVAL'",
                "REJECTED",
                Instant.now(),
                id,
            )
        }.awaitSuspending()
    }
}
