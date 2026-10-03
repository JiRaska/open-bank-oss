// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.infrastructure.persistence.repository

import com.openbank.libs.persistence.outbox.AbstractPanacheOutboxRepository
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.libs.persistence.outbox.OutboxTableShape
import com.openbank.statement.application.port.out.StatementOutboxRepository
import com.openbank.statement.infrastructure.persistence.entity.StatementOutboxEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock

@ApplicationScoped
class StatementOutboxRepositoryImpl(clock: Clock) :
    AbstractPanacheOutboxRepository<StatementOutboxEntity>(
        OutboxTableShape("statement_outbox"),
        StatementOutboxEntity::class.java,
        clock,
    ),
    StatementOutboxRepository,
    PanacheRepository<StatementOutboxEntity> {

    override fun persistInTransaction(message: OutboxMessage): Uni<Void> {
        val now = clock.instant()
        val e = StatementOutboxEntity().apply {
            eventId = message.eventId
            synthetic = message.synthetic
            aggregateId = message.aggregateId
            eventType = message.eventType
            payload = message.payload
            status = OutboxStatus.PENDING.name
            attemptCount = 0
            createdAt = now
            updatedAt = now
        }
        return persist(e).replaceWithVoid()
    }
}
