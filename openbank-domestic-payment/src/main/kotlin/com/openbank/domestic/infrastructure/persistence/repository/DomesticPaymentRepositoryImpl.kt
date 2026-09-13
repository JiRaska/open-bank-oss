// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.persistence.repository

import com.openbank.domestic.application.port.out.DelegatedPaymentSaveOutcome
import com.openbank.domestic.application.port.out.DomesticPaymentRepository
import com.openbank.domestic.domain.model.DelegatedSpendBindingState
import com.openbank.domestic.domain.model.DelegatedSpendReservationSnapshot
import com.openbank.domestic.domain.model.DelegatedSpendReservationState
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.infrastructure.persistence.entity.DelegatedSpendBindingEntity
import com.openbank.domestic.infrastructure.persistence.entity.DomesticPaymentEntity
import com.openbank.domestic.infrastructure.persistence.mapper.toDomain
import com.openbank.domestic.infrastructure.persistence.mapper.toEntity
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import java.time.Instant
import java.util.UUID

@ApplicationScoped
// Reactive transaction failures can be wrapped by Mutiny/Hibernate in different RuntimeException
// subtypes. Both catch sites inspect the complete cause chain and immediately rethrow anything
// other than the one named idempotency constraint.
@Suppress("TooManyFunctions", "TooGenericExceptionCaught")
class DomesticPaymentRepositoryImpl(private val outboxRepository: DomesticPaymentOutboxRepositoryImpl) :
    DomesticPaymentRepository,
    PanacheRepository<DomesticPaymentEntity> {

    override suspend fun save(payment: DomesticPayment, outboxMessage: OutboxMessage): DomesticPayment = try {
        Panache.withTransaction {
            persist(payment.toEntity())
                .flatMap { outboxRepository.persistWithinCurrentTransaction(outboxMessage).replaceWith(payment) }
        }.awaitSuspending()
    } catch (exception: RuntimeException) {
        if (!exception.isIdempotencyKeyViolation()) throw exception

        // A concurrent creator committed this key while our transaction was in flight. Postgres
        // has already rolled our whole transaction back, including its outbox row; return the
        // winner so the use case can compare its durable request fingerprint before replaying it.
        findByIdempotencyKey(payment.idempotencyKey) ?: throw exception
    }

    override suspend fun saveDelegated(
        payment: DomesticPayment,
        outboxMessage: OutboxMessage,
        boundAt: Instant,
        debitOwnerPartyId: UUID,
    ): DelegatedPaymentSaveOutcome = try {
        val reservationId = checkNotNull(payment.reservationId) { "Delegated payment requires reservationId" }
        checkNotNull(payment.delegationId) { "Delegated payment requires delegationId" }
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createQuery(
                    "FROM DelegatedSpendBindingEntity WHERE reservationId = :reservationId",
                    DelegatedSpendBindingEntity::class.java,
                ).setParameter("reservationId", reservationId)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .singleResultOrNull
                    .flatMap { binding ->
                        when {
                            binding == null -> io.smallrye.mutiny.Uni.createFrom().item(
                                DelegatedPaymentSaveOutcome.ProjectionMissing,
                            )

                            binding.bindingState == DelegatedSpendBindingState.FINALIZED_ABSENT.name ->
                                io.smallrye.mutiny.Uni.createFrom().item(
                                    DelegatedPaymentSaveOutcome.FinalizedAbsent,
                                )

                            binding.bindingState == DelegatedSpendBindingState.BOUND.name -> {
                                val boundPaymentId = checkNotNull(binding.paymentId) {
                                    "BOUND reservation $reservationId has no payment_id"
                                }
                                find("paymentId", boundPaymentId).firstResult()
                                    .map { existing ->
                                        checkNotNull(existing) {
                                            "BOUND reservation $reservationId points to a missing payment"
                                        }
                                        DelegatedPaymentSaveOutcome.Replayed(existing.toDomain())
                                    }
                            }

                            else -> {
                                val mismatch = binding.mismatchWith(payment, debitOwnerPartyId)
                                if (mismatch != null) {
                                    io.smallrye.mutiny.Uni.createFrom().item(
                                        DelegatedPaymentSaveOutcome.TupleMismatch(mismatch),
                                    )
                                } else {
                                    binding.bindingState = DelegatedSpendBindingState.BOUND.name
                                    binding.paymentId = payment.id
                                    binding.boundAt = boundAt
                                    binding.updatedAt = boundAt
                                    persist(payment.toEntity())
                                        .flatMap {
                                            outboxRepository.persistWithinCurrentTransaction(outboxMessage)
                                        }
                                        .replaceWith(DelegatedPaymentSaveOutcome.Created(payment))
                                }
                            }
                        }
                    }
            }
        }.awaitSuspending()
    } catch (exception: RuntimeException) {
        if (!exception.isIdempotencyKeyViolation()) throw exception
        val winner = findByIdempotencyKey(payment.idempotencyKey) ?: throw exception
        DelegatedPaymentSaveOutcome.Replayed(winner)
    }

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

    // #4218. Its own transaction, deliberately: the point of the marker is to survive a failure of
    // the update() above, so it must not share a transaction with anything that can roll back.
    // Written as a bulk update rather than find-then-mutate so it touches only this one column and
    // cannot race the aggregate write on any other field.
    override suspend fun claimSchemeDispatch(paymentId: UUID, dispatchedAt: Instant): Boolean =
        Panache.withTransaction {
            // `and schemeDispatchedAt is null` is the guard, not decoration: without it this is a
            // read-then-write and two concurrent attempts both submit (#4218). The row count is the
            // verdict — 1 means this caller claimed it, 0 means someone else already had it.
            update(
                "schemeDispatchedAt = ?1 where paymentId = ?2 and schemeDispatchedAt is null",
                dispatchedAt,
                paymentId,
            )
        }.awaitSuspending() == 1

    override suspend fun clearSchemeDispatch(paymentId: UUID) {
        Panache.withTransaction {
            update("schemeDispatchedAt = null where paymentId = ?1", paymentId)
        }.awaitSuspending()
    }

    private fun Throwable.isIdempotencyKeyViolation(): Boolean =
        generateSequence(this) { cause -> cause.cause.takeIf { it !== cause } }.any { cause ->
            val hibernateConstraint = (cause as? org.hibernate.exception.ConstraintViolationException)?.constraintName
            hibernateConstraint == IDEMPOTENCY_KEY_CONSTRAINT ||
                cause.message?.contains(IDEMPOTENCY_KEY_CONSTRAINT) == true
        }

    private fun DelegatedSpendBindingEntity.mismatchWith(payment: DomesticPayment, debitOwnerPartyId: UUID): String? =
        when {
            bindingState != DelegatedSpendBindingState.PENDING.name -> "reservation is not pending"

            reservationState != DelegatedSpendReservationState.RESERVED.name || reservationVersion != 1L ->
                "reservation source state is not RESERVED revision 1"

            reservationId != payment.reservationId -> "reservationId does not match"

            delegationId != payment.delegationId -> "delegationId does not match"

            grantorPartyId != debitOwnerPartyId -> "debit owner is not the reservation grantor"

            granteePartyId != payment.initiatedByPartyId -> "initiating party is not the reservation grantee"

            resourceId != payment.debtorAccountId -> "debtor account is not the delegated resource"

            amount.compareTo(payment.amount) != 0 -> "amount does not match the reserved amount"

            currency != payment.currency -> "currency does not match the reserved currency"

            idempotencyKeyHash != DelegatedSpendReservationSnapshot.hashIdempotencyKey(payment.idempotencyKey) ->
                "Idempotency-Key does not match the reservation"

            else -> null
        }

    private companion object {
        const val IDEMPOTENCY_KEY_CONSTRAINT = "domestic_payments_idempotency_key_key"
    }
}
