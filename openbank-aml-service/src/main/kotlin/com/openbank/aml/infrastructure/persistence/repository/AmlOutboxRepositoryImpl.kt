// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.aml.infrastructure.persistence.repository

import com.openbank.aml.application.port.out.AmlOutboxRepository
import com.openbank.aml.infrastructure.persistence.entity.AmlOutboxEntity
import com.openbank.libs.persistence.outbox.AbstractPanacheOutboxRepository
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.libs.persistence.outbox.OutboxTableShape
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock

@ApplicationScoped
class AmlOutboxRepositoryImpl(clock: Clock) :
    AbstractPanacheOutboxRepository<AmlOutboxEntity>(
        OutboxTableShape("aml_outbox"),
        AmlOutboxEntity::class.java,
        clock,
    ),
    AmlOutboxRepository,
    PanacheRepository<AmlOutboxEntity> {

    override fun persistInTransaction(message: OutboxMessage): Uni<Void> = persist(message.toEntity()).replaceWithVoid()

    private fun OutboxMessage.toEntity() = AmlOutboxEntity().also {
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
