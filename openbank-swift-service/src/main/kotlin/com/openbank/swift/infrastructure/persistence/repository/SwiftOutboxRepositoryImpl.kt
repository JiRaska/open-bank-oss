// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.swift.infrastructure.persistence.repository

import com.openbank.libs.persistence.outbox.AbstractPanacheOutboxRepository
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.libs.persistence.outbox.OutboxTableShape
import com.openbank.swift.application.port.out.SwiftOutboxMessage
import com.openbank.swift.application.port.out.SwiftOutboxRepository
import com.openbank.swift.infrastructure.persistence.entity.SwiftOutboxEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock

/**
 * Swift's outbox on the kernel repository (ADR-0327 D1): the claim-by-aggregate-head with
 * `FOR UPDATE SKIP LOCKED` (#1201, D3), backoff (D4), batched `markSent` (D6), the O(1) count and
 * retention all live in [AbstractPanacheOutboxRepository]. Only the writes that join the caller's
 * transaction stay here — `SwiftRepositoryImpl.saveWithOutbox` chains
 * [persistWithinCurrentTransaction] inside its own `Panache.withTransaction`, which is what keeps
 * the message row and its outbox row in one commit (#8353).
 */
@ApplicationScoped
class SwiftOutboxRepositoryImpl(clock: Clock) :
    AbstractPanacheOutboxRepository<SwiftOutboxEntity>(
        OutboxTableShape("swift_outbox"),
        SwiftOutboxEntity::class.java,
        clock,
    ),
    SwiftOutboxRepository,
    PanacheRepository<SwiftOutboxEntity> {

    override suspend fun persistInTransaction(message: SwiftOutboxMessage) {
        persist(message.toEntity()).awaitSuspending()
    }

    fun persistWithinCurrentTransaction(message: SwiftOutboxMessage): Uni<SwiftOutboxEntity> =
        persist(message.toEntity())

    private fun SwiftOutboxMessage.toEntity() = SwiftOutboxEntity().also {
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
