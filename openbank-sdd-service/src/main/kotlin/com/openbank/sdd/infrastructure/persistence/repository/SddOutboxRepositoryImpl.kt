// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.infrastructure.persistence.repository

import com.openbank.libs.persistence.outbox.AbstractPanacheOutboxRepository
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.libs.persistence.outbox.OutboxTableShape
import com.openbank.sdd.application.port.out.SddOutbox
import com.openbank.sdd.application.port.out.SddOutboxRepository
import com.openbank.sdd.infrastructure.persistence.entity.SddOutboxEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock

/**
 * SDD's outbox on the kernel repository (ADR-0327 D1): the claim-by-aggregate-head with
 * `FOR UPDATE SKIP LOCKED` (#1201, D3), backoff (D4), batched `markSent` (D6), the O(1) count and
 * retention all live in [AbstractPanacheOutboxRepository].
 *
 * Only the write that joins the caller's transaction stays here: [append] implements both
 * [SddOutboxRepository] (the dispatcher's port) and [SddOutbox] (the application layer's write
 * port). `SddMandateService` chains it after the mandate save, inside the `@WithTransaction`
 * boundary of the `SddResource` endpoint, which is what keeps the mandate row and its outbox row
 * in one commit (`SddOutboxAtomicityIT`).
 */
@ApplicationScoped
class SddOutboxRepositoryImpl(clock: Clock) :
    AbstractPanacheOutboxRepository<SddOutboxEntity>(
        OutboxTableShape("sdd_outbox"),
        SddOutboxEntity::class.java,
        clock,
    ),
    SddOutboxRepository,
    SddOutbox,
    PanacheRepository<SddOutboxEntity> {

    // --- SddOutbox (application-layer write port) ---

    override fun append(message: OutboxMessage): Uni<Void> {
        val e = message.toEntity()
        return persist(e).replaceWithVoid()
    }

    private fun OutboxMessage.toEntity() = SddOutboxEntity().also {
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
