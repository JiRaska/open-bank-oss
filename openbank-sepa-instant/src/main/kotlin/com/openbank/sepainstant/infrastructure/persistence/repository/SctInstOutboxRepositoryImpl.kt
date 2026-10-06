// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sepainstant.infrastructure.persistence.repository

import com.openbank.libs.persistence.outbox.AbstractPanacheOutboxRepository
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.libs.persistence.outbox.OutboxTableShape
import com.openbank.sepainstant.application.port.out.SctInstOutboxRepository
import com.openbank.sepainstant.infrastructure.persistence.entity.SctInstOutboxEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock

@ApplicationScoped
class SctInstOutboxRepositoryImpl(clock: Clock) :
    AbstractPanacheOutboxRepository<SctInstOutboxEntity>(
        OutboxTableShape("sct_inst_outbox"),
        SctInstOutboxEntity::class.java,
        clock,
    ),
    SctInstOutboxRepository,
    PanacheRepository<SctInstOutboxEntity> {
    override suspend fun countDead(): Long = Panache.withSession {
        count("status = ?1", OutboxStatus.DEAD.name)
    }.awaitSuspending()

    fun persistInTransaction(message: OutboxMessage): Uni<Void> = persist(
        SctInstOutboxEntity().also {
            it.eventId = message.eventId
            it.aggregateId = message.aggregateId
            it.eventType = message.eventType
            it.payload = message.payload
            it.status = OutboxStatus.PENDING.name
            it.attemptCount = 0
            it.synthetic = message.synthetic
            it.createdAt = message.createdAt
            it.updatedAt = message.createdAt
        },
    ).replaceWithVoid()
}
