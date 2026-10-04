// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.notification.infrastructure.persistence.repository

import com.openbank.libs.persistence.outbox.AbstractPanacheOutboxRepository
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.libs.persistence.outbox.OutboxTableShape
import com.openbank.notification.application.port.out.NotificationOutboxRepository
import com.openbank.notification.infrastructure.persistence.entity.NotificationOutboxEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant

@ApplicationScoped
class NotificationOutboxRepositoryImpl(clock: Clock) :
    AbstractPanacheOutboxRepository<NotificationOutboxEntity>(
        OutboxTableShape("notification_outbox"),
        NotificationOutboxEntity::class.java,
        clock,
    ),
    NotificationOutboxRepository,
    PanacheRepository<NotificationOutboxEntity> {

    override fun persistInTransaction(message: OutboxMessage): Uni<Void> = persist(message.toEntity()).replaceWithVoid()

    override fun purgeDeadBefore(threshold: Instant): Uni<Long> = Panache.withTransaction {
        delete("status = ?1 and updatedAt < ?2", OutboxStatus.DEAD.name, threshold)
    }

    private fun OutboxMessage.toEntity() = NotificationOutboxEntity().also {
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
