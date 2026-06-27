// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.persistence.repository

import com.openbank.transaction.application.port.out.PaymentSagaRepository
import com.openbank.transaction.domain.saga.PaymentSaga
import com.openbank.transaction.domain.saga.SagaState
import com.openbank.transaction.infrastructure.persistence.entity.PaymentSagaEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class PanachePaymentSagaRepository :
    PaymentSagaRepository,
    PanacheRepository<PaymentSagaEntity> {

    override suspend fun save(saga: PaymentSaga): PaymentSaga = Panache.withTransaction {
        val entity = saga.toEntity()
        persist(entity).replaceWith(entity)
    }.awaitSuspending().toDomain()

    override suspend fun findById(sagaId: UUID): PaymentSaga? = Panache.withSession {
        find("id", sagaId).firstResult()
    }.awaitSuspending()?.toDomain()

    override suspend fun findByTransactionId(transactionId: UUID): PaymentSaga? = Panache.withSession {
        find("transactionId", transactionId).firstResult()
    }.awaitSuspending()?.toDomain()

    override suspend fun findByIdempotencyKey(idempotencyKey: String): PaymentSaga? = Panache.withSession {
        find("idempotencyKey", idempotencyKey).firstResult()
    }.awaitSuspending()?.toDomain()

    override suspend fun update(saga: PaymentSaga): PaymentSaga = Panache.withTransaction {
        find("id", saga.id).firstResult()
            .onItem().ifNotNull().transform { entity ->
                entity!!.state = saga.state.name
                entity.failureReason = saga.failureReason
                entity.compensationReason = saga.compensationReason
                entity.updatedAt = saga.updatedAt
                entity
            }
    }.awaitSuspending()?.toDomain() ?: error("Saga not found: ${saga.id}")

    private fun PaymentSaga.toEntity(): PaymentSagaEntity = PaymentSagaEntity().also {
        it.id = id
        it.transactionId = transactionId
        it.state = state.name
        it.idempotencyKey = idempotencyKey
        it.failureReason = failureReason
        it.compensationReason = compensationReason
        it.createdAt = createdAt
        it.updatedAt = updatedAt
        it.version = version
    }

    private fun PaymentSagaEntity.toDomain(): PaymentSaga = PaymentSaga(
        id = id,
        transactionId = transactionId,
        state = SagaState.valueOf(state),
        idempotencyKey = idempotencyKey,
        failureReason = failureReason,
        compensationReason = compensationReason,
        createdAt = createdAt,
        updatedAt = updatedAt,
        version = version,
    )
}
