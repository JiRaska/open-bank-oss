// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.persistence.repository

import com.openbank.domestic.application.port.out.DomesticPaymentOutboxRepository
import com.openbank.domestic.infrastructure.persistence.entity.DomesticPaymentOutboxEntity
import com.openbank.libs.persistence.outbox.AbstractClaimFencedOutboxRepository
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.libs.persistence.outbox.OutboxTableShape
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock

@ApplicationScoped
class DomesticPaymentOutboxRepositoryImpl(clock: Clock) :
    AbstractClaimFencedOutboxRepository<DomesticPaymentOutboxEntity>(
        OutboxTableShape("domestic_payment_outbox"),
        DomesticPaymentOutboxEntity::class.java,
        clock,
    ),
    DomesticPaymentOutboxRepository,
    PanacheRepository<DomesticPaymentOutboxEntity> {

    fun persistWithinCurrentTransaction(message: OutboxMessage): Uni<DomesticPaymentOutboxEntity> =
        persist(message.toEntity())

    private fun OutboxMessage.toEntity() = DomesticPaymentOutboxEntity().also {
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
