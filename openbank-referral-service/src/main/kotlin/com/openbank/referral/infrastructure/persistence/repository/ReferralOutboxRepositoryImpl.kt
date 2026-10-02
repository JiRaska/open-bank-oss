// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.referral.infrastructure.persistence.repository

import com.openbank.libs.persistence.outbox.AbstractPanacheOutboxRepository
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.libs.persistence.outbox.OutboxTableShape
import com.openbank.referral.application.port.out.ReferralOutboxRepository
import com.openbank.referral.infrastructure.persistence.entity.ReferralOutboxEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock

/**
 * Outbox repository on the kernel base (ADR-0327 D1): claim by aggregate head with
 * `FOR UPDATE SKIP LOCKED` (#1201, D3), next_attempt_at backoff (D4), batched `markSent` (D6),
 * the O(1) count and retention all live in [AbstractPanacheOutboxRepository]. What stays here is
 * this service's own write path, which joins the caller's transaction (ADR-0050).
 */
@ApplicationScoped
class ReferralOutboxRepositoryImpl(clock: Clock) :
    AbstractPanacheOutboxRepository<ReferralOutboxEntity>(
        OutboxTableShape("referral_outbox"),
        ReferralOutboxEntity::class.java,
        clock,
    ),
    ReferralOutboxRepository,
    PanacheRepository<ReferralOutboxEntity> {

    override fun persistInTransaction(message: OutboxMessage): Uni<Void> = persist(message.toEntity()).replaceWithVoid()

    suspend fun countDead(): Long = Panache.withSession {
        count("status = ?1", OutboxStatus.DEAD.name)
    }.awaitSuspending()

    private fun OutboxMessage.toEntity() = ReferralOutboxEntity().also {
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
