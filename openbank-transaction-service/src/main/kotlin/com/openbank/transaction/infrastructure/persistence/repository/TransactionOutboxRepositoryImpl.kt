// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.persistence.repository

import com.openbank.libs.persistence.outbox.AbstractClaimFencedOutboxRepository
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.libs.persistence.outbox.OutboxTableShape
import com.openbank.transaction.application.port.out.TransactionOutboxRepository
import com.openbank.transaction.infrastructure.persistence.entity.TransactionOutboxEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock

@ApplicationScoped
class TransactionOutboxRepositoryImpl(clock: Clock) :
    AbstractClaimFencedOutboxRepository<TransactionOutboxEntity>(
        OutboxTableShape("transaction_outbox"),
        TransactionOutboxEntity::class.java,
        clock,
    ),
    TransactionOutboxRepository,
    PanacheRepository<TransactionOutboxEntity> {

    override suspend fun persistInTransaction(message: OutboxMessage) {
        persist(message.toEntity()).awaitSuspending()
    }

    fun persistMessageUni(message: OutboxMessage): Uni<Unit> = persist(message.toEntity()).replaceWith(Unit)

    private fun OutboxMessage.toEntity() = TransactionOutboxEntity().also {
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
