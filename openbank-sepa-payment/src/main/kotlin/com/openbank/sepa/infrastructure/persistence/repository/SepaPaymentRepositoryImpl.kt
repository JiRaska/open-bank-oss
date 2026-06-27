// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.persistence.repository

import com.openbank.sepa.application.port.out.SepaPaymentOutboxMessage
import com.openbank.sepa.application.port.out.SepaPaymentRepository
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.infrastructure.persistence.entity.SepaPaymentEntity
import com.openbank.sepa.infrastructure.persistence.mapper.toDomain
import com.openbank.sepa.infrastructure.persistence.mapper.toEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class SepaPaymentRepositoryImpl(private val outboxRepository: SepaPaymentOutboxRepositoryImpl) :
    SepaPaymentRepository,
    PanacheRepository<SepaPaymentEntity> {

    override suspend fun save(payment: SepaPayment, outboxMessage: SepaPaymentOutboxMessage): SepaPayment =
        Panache.withTransaction {
            persist(payment.toEntity())
                .flatMap { outboxRepository.persistWithinCurrentTransaction(outboxMessage).replaceWith(payment) }
        }.awaitSuspending()

    override suspend fun findById(paymentId: UUID): SepaPayment? =
        Panache.withSession { find("paymentId", paymentId).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findByIdempotencyKey(idempotencyKey: String): SepaPayment? =
        Panache.withSession { find("idempotencyKey", idempotencyKey).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findByEndToEndId(endToEndId: String): SepaPayment? =
        Panache.withSession { find("endToEndId", endToEndId).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun list(
        status: SepaPaymentStatus?,
        debtorAccountId: UUID?,
        limit: Int,
        offset: Int,
    ): List<SepaPayment> = Panache.withSession {
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

    override suspend fun update(payment: SepaPayment, outboxMessage: SepaPaymentOutboxMessage): SepaPayment =
        Panache.withTransaction {
            find("paymentId", payment.id).firstResult()
                .invoke { entity ->
                    if (entity != null) {
                        entity.status = payment.status.name
                        entity.rejectReason = payment.rejectReason?.name
                        entity.rejectDetail = payment.rejectDetail
                        entity.submittedAt = payment.submittedAt
                        entity.completedAt = payment.completedAt
                        entity.updatedAt = payment.updatedAt
                    }
                }
                .flatMap { outboxRepository.persistWithinCurrentTransaction(outboxMessage).replaceWith(payment) }
        }.awaitSuspending()
}
