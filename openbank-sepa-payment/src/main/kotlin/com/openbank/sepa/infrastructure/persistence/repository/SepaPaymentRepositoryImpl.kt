// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.persistence.repository

import com.openbank.sepa.application.port.out.SepaPaymentOutboxMessage
import com.openbank.sepa.application.port.out.SepaPaymentRepository
import com.openbank.sepa.domain.event.RETURN_EVIDENCE_EVENT_TYPE
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaReturnIntent
import com.openbank.sepa.domain.model.SepaReturnWritePolicy
import com.openbank.sepa.domain.model.SepaSettlementCompletion
import com.openbank.sepa.infrastructure.persistence.entity.SepaPaymentEntity
import com.openbank.sepa.infrastructure.persistence.mapper.applyLifecycleUpdate
import com.openbank.sepa.infrastructure.persistence.mapper.toDomain
import com.openbank.sepa.infrastructure.persistence.mapper.toEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import java.time.Instant
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

    override suspend fun beginReturn(paymentId: UUID, intent: SepaReturnIntent): SepaPayment =
        mutateReturnIntent(paymentId) { payment ->
            check(
                payment.status in setOf(
                    SepaPaymentStatus.RETURNED,
                    SepaPaymentStatus.COMPLETED,
                    SepaPaymentStatus.PROCESSING,
                ),
            ) { "Payment cannot accept a return" }
            payment.returnIntent ?: intent.copy(originalTransactionId = payment.transactionId)
        }

    override suspend fun markReturnUnresolved(paymentId: UUID, cause: String): SepaPayment =
        mutateReturnIntent(paymentId) { payment ->
            checkNotNull(payment.returnIntent).let {
                if (it.resolvedAt == null) it.copy(unresolvedCause = cause) else it
            }
        }

    override suspend fun update(payment: SepaPayment, outboxMessage: SepaPaymentOutboxMessage): SepaPayment =
        updateWithMessages(payment, listOf(outboxMessage), outboxRepository)

    override suspend fun updateWithEvidence(
        payment: SepaPayment,
        outboxMessage: SepaPaymentOutboxMessage,
        evidenceMessage: SepaPaymentOutboxMessage,
    ): SepaPayment = updateWithMessages(
        payment,
        listOf(outboxMessage, evidenceMessage),
        outboxRepository,
        allowReturnResolution = evidenceMessage.eventType == RETURN_EVIDENCE_EVENT_TYPE &&
            evidenceMessage.aggregateId == payment.id &&
            outboxMessage.aggregateId == payment.id,
    )

    override suspend fun completeSettlement(
        requestedPayment: SepaPayment,
        transactionId: UUID,
        completedAt: Instant,
        statusMessage: (SepaPayment) -> SepaPaymentOutboxMessage,
    ): SepaPayment = Panache.withTransaction {
        find("paymentId", requestedPayment.id).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()
            .flatMap { entity ->
                val current = checkNotNull(entity).toDomain()
                SepaSettlementCompletion.validateRequest(current, requestedPayment, transactionId)
                if (SepaSettlementCompletion.isCompatibleReplay(current, transactionId)) {
                    Uni.createFrom().item(current)
                } else {
                    val completed = SepaSettlementCompletion.completed(current, transactionId, completedAt)
                    SepaReturnWritePolicy.validate(current, completed, false, allowInitialSettlementIdentity = true)
                    val message = statusMessage(completed)
                    require(message.aggregateId == completed.id && message.eventType == "sepa.payment.status-changed")
                    entity.applyLifecycleUpdate(completed)
                    outboxRepository.persistWithinCurrentTransaction(message).replaceWith(completed)
                }
            }
    }.awaitSuspending()
}
