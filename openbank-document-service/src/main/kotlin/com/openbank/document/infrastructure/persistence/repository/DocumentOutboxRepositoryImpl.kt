// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.document.infrastructure.persistence.repository

import com.openbank.document.application.port.out.DocumentOutboxRepository
import com.openbank.document.infrastructure.outbox.DocumentEventSchemaValidator
import com.openbank.document.infrastructure.persistence.entity.DocumentOutboxEntity
import com.openbank.libs.persistence.outbox.AbstractPanacheOutboxRepository
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.libs.persistence.outbox.OutboxTableShape
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock

/**
 * Document's outbox on the kernel repository (ADR-0327 D1): the claim-by-aggregate-head with
 * `FOR UPDATE SKIP LOCKED` (#1201, D3), backoff (D4), batched `markSent` (D6), the O(1) count and
 * retention all live in [AbstractPanacheOutboxRepository]. Only the write that joins the caller's
 * transaction (#4007) stays here.
 */
@ApplicationScoped
class DocumentOutboxRepositoryImpl(clock: Clock, private val eventSchema: DocumentEventSchemaValidator) :
    AbstractPanacheOutboxRepository<DocumentOutboxEntity>(
        OutboxTableShape("document_outbox"),
        DocumentOutboxEntity::class.java,
        clock,
    ),
    DocumentOutboxRepository,
    PanacheRepository<DocumentOutboxEntity> {

    override fun persistInTransaction(message: OutboxMessage): Uni<Void> {
        eventSchema.check(message.eventType, message.payload)
        return persist(message.toEntity()).replaceWithVoid()
    }

    private fun OutboxMessage.toEntity() = DocumentOutboxEntity().also {
        it.eventId = eventId
        it.synthetic = synthetic
        it.aggregateId = aggregateId
        it.eventType = eventType
        it.payload = payload
        it.status = OutboxStatus.PENDING.name
        it.attemptCount = 0
        it.createdAt = createdAt
        it.updatedAt = createdAt
    }
}
