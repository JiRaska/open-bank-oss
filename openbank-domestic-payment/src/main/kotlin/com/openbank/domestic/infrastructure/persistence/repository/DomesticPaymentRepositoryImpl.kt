// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.persistence.repository

import com.openbank.domestic.application.port.out.DomesticPaymentRepository
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.infrastructure.persistence.entity.DomesticPaymentEntity
import com.openbank.domestic.infrastructure.persistence.mapper.toDomain
import com.openbank.domestic.infrastructure.persistence.mapper.toEntity
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class DomesticPaymentRepositoryImpl(private val outboxRepository: DomesticPaymentOutboxRepositoryImpl) :
    DomesticPaymentRepository,
    PanacheRepository<DomesticPaymentEntity> {

    override suspend fun save(payment: DomesticPayment, outboxMessage: OutboxMessage): DomesticPayment =
        Panache.withTransaction {
            persist(payment.toEntity())
                .flatMap { outboxRepository.persistWithinCurrentTransaction(outboxMessage).replaceWith(payment) }
        }.awaitSuspending()

    override suspend fun findById(paymentId: UUID): DomesticPayment? =
        Panache.withSession { find("paymentId", paymentId).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findByIdempotencyKey(idempotencyKey: String): DomesticPayment? =
        Panache.withSession { find("idempotencyKey", idempotencyKey).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun countByStatus(status: DomesticPaymentStatus): Long =
        Panache.withSession { count("status", status.name) }.awaitSuspending()

    override suspend fun oldestCreatedAt(status: DomesticPaymentStatus): Instant? = Panache.withSession {
        find("status = ?1 order by createdAt asc", status.name).firstResult()
    }.awaitSuspending()?.createdAt

    override suspend fun list(
        status: DomesticPaymentStatus?,
        debtorAccountId: UUID?,
        limit: Int,
        offset: Int,
    ): List<DomesticPayment> = Panache.withSession {
        val query = when {
            status != null && debtorAccountId != null -> find(
                "status = ?1 and debtorAccountId = ?2 order by createdAt desc",
                status.name,
                debtorAccountId,
            )
            status != null -> find("status = ?1 order by createdAt desc", status.name)
            debtorAccountId != null -> find("debtorAccountId = ?1 order by createdAt desc", debtorAccountId)
            else -> find("order by createdAt desc")
        }
        query.range(offset, offset + limit - 1).list()
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun findRedrivable(maxAttempts: Int, minAge: Instant, limit: Int): List<UUID> =
        Panache.withSession {
            find(
                "status = ?1 and redriveAttempts < ?2 and createdAt < ?3 order by createdAt asc",
                DomesticPaymentStatus.RECEIVED.name,
                maxAttempts,
                minAge,
            ).range(0, limit - 1).list()
        }.awaitSuspending().map { it.paymentId }

    override suspend fun recordRedriveAttempt(paymentId: UUID) {
        Panache.withTransaction {
            update("redriveAttempts = redriveAttempts + 1 where paymentId = ?1", paymentId)
        }.awaitSuspending()
    }

    override suspend fun update(payment: DomesticPayment, outboxMessage: OutboxMessage): DomesticPayment =
        Panache.withTransaction {
            find("paymentId", payment.id).firstResult()
                .invoke { entity ->
                    if (entity != null) {
                        entity.status = payment.status.name
                        entity.rejectReason = payment.rejectReason?.name
                        entity.rejectDetail = payment.rejectDetail
                        entity.submittedAt = payment.submittedAt
                        entity.settledAt = payment.settledAt
                        entity.updatedAt = payment.updatedAt
                    }
                }
                .flatMap { outboxRepository.persistWithinCurrentTransaction(outboxMessage).replaceWith(payment) }
        }.awaitSuspending()
}
