// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.ledger.infrastructure.persistence.repository

import com.openbank.ledger.application.port.out.LedgerOutboxRepository
import com.openbank.ledger.infrastructure.persistence.entity.LedgerOutboxEntity
import com.openbank.libs.persistence.outbox.AbstractClaimFencedOutboxRepository
import com.openbank.libs.persistence.outbox.OutboxFailurePolicy
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.libs.persistence.outbox.OutboxTableShape
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock

@ApplicationScoped
class LedgerOutboxRepositoryImpl(clock: Clock) :
    AbstractClaimFencedOutboxRepository<LedgerOutboxEntity>(
        OutboxTableShape("ledger_outbox"),
        LedgerOutboxEntity::class.java,
        clock,
    ),
    LedgerOutboxRepository,
    PanacheRepository<LedgerOutboxEntity> {

    fun persistInTransaction(message: OutboxMessage) = persist(message.toEntity())

    private fun OutboxMessage.toEntity() = LedgerOutboxEntity().also {
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

    companion object {
        const val MAX_ATTEMPTS: Int = OutboxFailurePolicy.DEFAULT_MAX_ATTEMPTS

        fun statusAfterFailure(attemptCount: Int): OutboxStatus = OutboxFailurePolicy.statusAfterFailure(attemptCount)
    }
}
