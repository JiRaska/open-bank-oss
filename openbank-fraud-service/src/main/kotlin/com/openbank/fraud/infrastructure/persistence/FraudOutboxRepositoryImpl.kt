// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.persistence

import com.openbank.fraud.application.port.out.FraudOutboxRepository
import com.openbank.fraud.infrastructure.persistence.entity.FraudOutboxEntity
import com.openbank.libs.persistence.outbox.AbstractPanacheOutboxRepository
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.libs.persistence.outbox.OutboxTableShape
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock

/**
 * The fraud-hold outbox on the kernel repository (ADR-0327 phase 2): claim by aggregate head,
 * `next_attempt_at` backoff, batched markSent, indexed count and SENT retention (ADR-0329) all
 * come from [AbstractPanacheOutboxRepository]. Only the write side is service-specific.
 */
@ApplicationScoped
class FraudOutboxRepositoryImpl(clock: Clock) :
    AbstractPanacheOutboxRepository<FraudOutboxEntity>(
        OutboxTableShape("fraud_outbox"),
        FraudOutboxEntity::class.java,
        clock,
    ),
    FraudOutboxRepository,
    PanacheRepository<FraudOutboxEntity> {

    fun persistInTransaction(message: OutboxMessage): Uni<Void> = persist(message.toEntity()).replaceWithVoid()

    private fun OutboxMessage.toEntity() = FraudOutboxEntity().also {
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
