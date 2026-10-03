// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.infrastructure.persistence.repository

import com.openbank.libs.persistence.outbox.AbstractPanacheOutboxRepository
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.libs.persistence.outbox.OutboxTableShape
import com.openbank.loyalty.application.port.out.LoyaltyOutboxRepository
import com.openbank.loyalty.infrastructure.persistence.entity.LoyaltyOutboxEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock

/**
 * Outbox repository on the kernel base (ADR-0327 D1): claim by aggregate head with
 * `FOR UPDATE SKIP LOCKED` (#1201, D3), next_attempt_at backoff (D4), batched `markSent` (D6),
 * the O(1) count and retention all live in [AbstractPanacheOutboxRepository]. What stays here is
 * this service's own write path, which joins the caller's transaction (ADR-0050).
 */
@ApplicationScoped
class LoyaltyOutboxRepositoryImpl(clock: Clock) :
    AbstractPanacheOutboxRepository<LoyaltyOutboxEntity>(
        OutboxTableShape("loyalty_outbox"),
        LoyaltyOutboxEntity::class.java,
        clock,
    ),
    LoyaltyOutboxRepository,
    PanacheRepository<LoyaltyOutboxEntity> {

    fun persistInTransaction(message: OutboxMessage) = persist(message.toEntity())

    private fun OutboxMessage.toEntity() = LoyaltyOutboxEntity().also {
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
