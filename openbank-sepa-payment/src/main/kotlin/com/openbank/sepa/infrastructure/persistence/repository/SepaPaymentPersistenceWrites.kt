// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.persistence.repository

import com.openbank.sepa.application.port.out.SepaPaymentOutboxMessage
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaReturnIntent
import com.openbank.sepa.domain.model.SepaReturnWritePolicy
import com.openbank.sepa.infrastructure.persistence.entity.SepaPaymentEntity
import com.openbank.sepa.infrastructure.persistence.mapper.applyLifecycleUpdate
import com.openbank.sepa.infrastructure.persistence.mapper.applyReturnIntent
import com.openbank.sepa.infrastructure.persistence.mapper.toDomain
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.persistence.LockModeType
import java.util.UUID

internal suspend fun PanacheRepository<SepaPaymentEntity>.mutateReturnIntent(
    paymentId: UUID,
    change: (SepaPayment) -> SepaReturnIntent,
): SepaPayment = Panache.withTransaction {
    find("paymentId", paymentId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()
        .map { entity ->
            checkNotNull(entity) { "SEPA payment $paymentId disappeared during return" }
            val before = entity.toDomain()
            val intent = if (before.status ==
                SepaPaymentStatus.RETURNED
            ) {
                before.returnIntent
            } else {
                change(before)
            }
            if (intent != before.returnIntent) {
                entity.applyReturnIntent(intent)
                entity.revision += 1
            }
            entity
        }
        .flatMap { entity ->
            // Return PostgreSQL's stored timestamp precision, not the pre-flush JVM value.
            // Keep the write, refresh and original row lock inside this transaction.
            Panache.getSession().flatMap { session ->
                session.flush().flatMap { session.refresh(entity) }.map { entity.toDomain() }
            }
        }
}.awaitSuspending()

/**
 * General revision-checked write path: the aggregate change and EVERY accompanying outbox message inside one
 * `Panache.withTransaction`. The evidence row must not be able to commit without the transition
 * (it would assert an act that did not happen) nor the transition without the evidence row
 * (issue #6056 — that is the state this service shipped in).
 */
internal suspend fun PanacheRepository<SepaPaymentEntity>.updateWithMessages(
    payment: SepaPayment,
    outboxMessages: List<SepaPaymentOutboxMessage>,
    outboxRepository: SepaPaymentOutboxRepositoryImpl,
    allowReturnResolution: Boolean = false,
): SepaPayment = Panache.withTransaction {
    find("paymentId", payment.id).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()
        .invoke { entity ->
            requireNotNull(entity) { "SEPA payment ${payment.id} disappeared during transition" }
            require(payment.revision == entity.revision + 1) {
                "stale SEPA payment revision ${payment.revision}; expected ${entity.revision + 1}"
            }
            SepaReturnWritePolicy.validate(entity.toDomain(), payment, allowReturnResolution)
            entity.applyLifecycleUpdate(payment)
        }
        .flatMap {
            outboxMessages.fold(Uni.createFrom().voidItem()) { chain, message ->
                chain.flatMap { outboxRepository.persistWithinCurrentTransaction(message).replaceWithVoid() }
            }.replaceWith(payment)
        }
}.awaitSuspending()
