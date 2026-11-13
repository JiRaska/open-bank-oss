// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.persistence.repository

import com.openbank.domestic.application.port.out.DelegatedPaymentSaveOutcome
import com.openbank.domestic.application.port.out.DomesticBookingWrites
import com.openbank.domestic.application.port.out.DomesticPaymentRepository
import com.openbank.domestic.application.port.out.HeldPaymentRef
import com.openbank.domestic.application.port.out.HoldDecisionRecord
import com.openbank.domestic.application.port.out.PaymentAdmissionWrites
import com.openbank.domestic.application.usecase.DomesticPaymentIdempotencyConflictException
import com.openbank.domestic.domain.model.DelegatedSpendBindingState
import com.openbank.domestic.domain.model.DelegatedSpendReservationSnapshot
import com.openbank.domestic.domain.model.DelegatedSpendReservationState
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.HoldDecision
import com.openbank.domestic.domain.model.RecordedFraudScore
import com.openbank.domestic.infrastructure.persistence.entity.DelegatedSpendBindingEntity
import com.openbank.domestic.infrastructure.persistence.entity.DomesticPaymentEntity
import com.openbank.domestic.infrastructure.persistence.mapper.toColumn
import com.openbank.domestic.infrastructure.persistence.mapper.toDomain
import com.openbank.domestic.infrastructure.persistence.mapper.toEntity
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import java.time.Duration
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

@ApplicationScoped
// Reactive transaction failures can be wrapped by Mutiny/Hibernate in different RuntimeException
// subtypes. Both catch sites inspect the complete cause chain and immediately rethrow anything
// other than either of the named idempotency constraints.
@Suppress("TooManyFunctions", "TooGenericExceptionCaught")
class DomesticPaymentRepositoryImpl(private val outboxRepository: DomesticPaymentOutboxRepositoryImpl) :
    DomesticPaymentRepository,
    DomesticBookingWrites,
    PaymentAdmissionWrites,
    PanacheRepository<DomesticPaymentEntity> {

    override suspend fun save(payment: DomesticPayment, outboxMessage: OutboxMessage): DomesticPayment = try {
        Panache.withTransaction {
            persist(payment.toEntity())
                .flatMap { outboxRepository.persistWithinCurrentTransaction(outboxMessage).replaceWith(payment) }
        }.awaitSuspending()
    } catch (exception: RuntimeException) {
        resolveIdempotencyRace(payment.idempotencyKey, exception)
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
        DelegatedPaymentSaveOutcome.Replayed(resolveIdempotencyRace(payment.idempotencyKey, exception))
    }

    private suspend fun resolveIdempotencyRace(idempotencyKey: String, failure: RuntimeException): DomesticPayment {
        if (!failure.isIdempotencyKeyViolation()) throw failure
        // The payment, reservation update and outbox have already rolled back. Only a winner with
        // the caller's own rail key can replay. A different rail key yields the generic conflict.
        return findByIdempotencyKey(idempotencyKey) ?: throw DomesticPaymentIdempotencyConflictException()
    }

    override suspend fun findById(paymentId: UUID): DomesticPayment? =
        Panache.withSession { find("paymentId", paymentId).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findByIdempotencyKey(idempotencyKey: String): DomesticPayment? =
        Panache.withSession { find("idempotencyKey", idempotencyKey).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findCustomerSubmission(
        idempotencyKey: String,
        debtorAccountId: UUID,
        scope: String,
        originalSubmissionKeyDigest: String?,
    ): DomesticPayment? = Panache.withSession {
        find(
            "idempotencyKey = ?1 and debtorAccountId = ?2 and customerSubmissionScope = ?3 " +
                "and originalSubmissionKeyDigest is not distinct from ?4",
            idempotencyKey,
            debtorAccountId,
            scope,
            originalSubmissionKeyDigest,
        ).firstResult()
    }.awaitSuspending()?.toDomain()

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

    override suspend fun findRedrivableLeastRecentlyChecked(maxAttempts: Int, minAge: Instant, limit: Int): List<UUID> =
        Panache.withSession {
            find(
                "status = ?1 and redriveAttempts < ?2 and createdAt < ?3 " +
                    "order by runCheckedAt asc nulls first, createdAt asc",
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

    override suspend fun findHeldToReconcile(heldBefore: Instant, limit: Int): List<HeldPaymentRef> =
        Panache.withSession {
            find(
                "status = ?1 and updatedAt < ?2 order by runCheckedAt asc nulls first, updatedAt asc",
                DomesticPaymentStatus.HELD.name,
                heldBefore,
            ).range(0, limit - 1).list()
        }.awaitSuspending().map { HeldPaymentRef(it.paymentId, it.heldAt ?: it.updatedAt) }

    override suspend fun markRunChecked(paymentId: UUID, at: Instant) {
        Panache.withTransaction {
            update("runCheckedAt = ?1 where paymentId = ?2", at, paymentId)
        }.awaitSuspending()
    }

    override suspend fun redriveAttempts(paymentId: UUID): Int = Panache.withSession {
        find("paymentId", paymentId).firstResult()
    }.awaitSuspending()?.redriveAttempts ?: 0

    override suspend fun countRedriveExhausted(maxAttempts: Int): Long = Panache.withSession {
        count("status = ?1 and redriveAttempts >= ?2", DomesticPaymentStatus.RECEIVED.name, maxAttempts)
    }.awaitSuspending()

    override suspend fun oldestHeldAt(): Instant? = Panache.withSession {
        find("status = ?1 order by heldAt asc nulls last, updatedAt asc", DomesticPaymentStatus.HELD.name).firstResult()
    }.awaitSuspending()?.let { it.heldAt ?: it.updatedAt }

    override suspend fun update(payment: DomesticPayment, outboxMessage: OutboxMessage): DomesticPayment =
        writeLifecycle(payment, listOf(outboxMessage))

    override suspend fun transition(payment: DomesticPayment, events: List<OutboxMessage>): DomesticPayment =
        writeLifecycle(payment, events)

    // Locks the row so the check and the insert are one decision: a concurrent release or reject
    // either commits first (and this writes nothing) or waits for this event to be written.
    override suspend fun requestCaseWhileHeld(
        paymentId: UUID,
        event: OutboxMessage,
        requestedAt: Instant,
        reasons: Set<String>,
    ): Boolean = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.createQuery(
                "FROM DomesticPaymentEntity WHERE paymentId = :paymentId",
                DomesticPaymentEntity::class.java,
            ).setParameter("paymentId", paymentId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .singleResultOrNull
                .flatMap { entity ->
                    when {
                        entity?.status != DomesticPaymentStatus.HELD.name -> Uni.createFrom().item(false)
                        // Already requested: the hold has its case, a second event would say nothing new.
                        entity.holdCaseRequestedAt != null -> Uni.createFrom().item(true)
                        else -> {
                            entity.holdCaseRequestedAt = requestedAt
                            entity.holdCaseReasons = reasons.toColumn()
                            outboxRepository.persistWithinCurrentTransaction(event).replaceWith(true)
                        }
                    }
                }
        }
    }.awaitSuspending()

    // Locks the row, so two attempts of one release cannot both ask: the second finds the review
    // already moved on and writes nothing.
    override suspend fun requestNextReview(
        paymentId: UUID,
        fromReview: Int,
        reasons: Set<String>,
        event: OutboxMessage,
    ): Boolean = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.createQuery(
                "FROM DomesticPaymentEntity WHERE paymentId = :paymentId",
                DomesticPaymentEntity::class.java,
            ).setParameter("paymentId", paymentId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .singleResultOrNull
                .flatMap { entity ->
                    if (entity?.status == DomesticPaymentStatus.HELD.name && (entity.holdReview ?: 1) == fromReview) {
                        entity.holdReview = fromReview + 1
                        entity.holdDecision = null
                        entity.holdCaseReasons = reasons.toColumn()
                        outboxRepository.persistWithinCurrentTransaction(event).replaceWith(true)
                    } else {
                        Uni.createFrom().item(false)
                    }
                }
        }
    }.awaitSuspending()

    // The row is locked and judged in one transaction, so two deliveries of a decision, or a
    // decision racing the expiry or a new review, cannot both win.
    override suspend fun recordHoldDecision(
        paymentId: UUID,
        caseKey: String,
        caseKeyOfReview: (Int) -> String,
        decision: HoldDecision,
        at: Instant,
        expiresAfter: Duration,
    ): HoldDecisionRecord = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.createQuery(
                "FROM DomesticPaymentEntity WHERE paymentId = :paymentId",
                DomesticPaymentEntity::class.java,
            ).setParameter("paymentId", paymentId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .singleResultOrNull
                .map { entity ->
                    when {
                        entity?.status != DomesticPaymentStatus.HELD.name || entity.holdCaseRequestedAt == null ->
                            HoldDecisionRecord.NO_HOLD
                        // Exact equality with the one canonical key. The key that arrived is not parsed.
                        caseKeyOfReview(entity.holdReview ?: 1) != caseKey -> HoldDecisionRecord.OTHER_CASE
                        entity.holdDecision != null -> HoldDecisionRecord.ALREADY_DECIDED
                        // held_at is null only on a row held by a build from before the column; its
                        // last write was the move to HELD.
                        !(entity.heldAt ?: entity.updatedAt).isAfter(at.minus(expiresAfter)) ->
                            HoldDecisionRecord.EXPIRED
                        else -> {
                            entity.holdDecision = decision.name
                            HoldDecisionRecord.ACCEPTED
                        }
                    }
                }
        }
    }.awaitSuspending()

    // A bulk update guarded by "is null": the first score wins, and neither the revision nor any
    // other column of the aggregate is touched, so it cannot race a lifecycle write.
    override suspend fun rememberFraudScore(paymentId: UUID, score: RecordedFraudScore) {
        Panache.withTransaction {
            update(
                "admissionFraudVerdict = ?1, admissionFraudScore = ?2, admissionFraudRuleVersion = ?3 " +
                    "where paymentId = ?4 and admissionFraudVerdict is null",
                score.verdict,
                score.score,
                score.ruleVersion,
                paymentId,
            )
        }.awaitSuspending()
    }

    override suspend fun acceptForBooking(payment: DomesticPayment, outbox: OutboxMessage): DomesticPayment =
        writeLifecycle(payment, listOf(outbox), acceptedBooking = true)

    override suspend fun completeBooking(
        payment: DomesticPayment,
        transactionId: UUID,
        outbox: OutboxMessage,
    ): DomesticPayment = writeLifecycle(payment, listOf(outbox), provenTransactionId = transactionId)

    private suspend fun writeLifecycle(
        payment: DomesticPayment,
        outboxMessages: List<OutboxMessage>,
        acceptedBooking: Boolean = false,
        provenTransactionId: UUID? = null,
    ): DomesticPayment = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.createQuery(
                "FROM DomesticPaymentEntity WHERE paymentId = :paymentId",
                DomesticPaymentEntity::class.java,
            ).setParameter("paymentId", payment.id)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .singleResultOrNull
                .invoke { entity ->
                    requireNotNull(entity) { "Domestic payment ${payment.id} disappeared during update" }
                    check(payment.aggregateRevision == entity.aggregateRevision + 1) {
                        "Stale domestic payment revision ${payment.aggregateRevision}; " +
                            "current revision is ${entity.aggregateRevision}"
                    }
                    // A hold's review and decision are written without a revision bump (by the decision
                    // consumer and by a request for the next review), so a write that ends a hold also
                    // proves, under this lock, that the hold is still the one it evaluated. Otherwise a
                    // release computed on review N could land after review N+1 was requested.
                    if (entity.status == DomesticPaymentStatus.HELD.name) {
                        check(
                            (entity.holdReview ?: 1) == payment.holdReview &&
                                entity.holdDecision == payment.holdDecision?.name,
                        ) {
                            "Hold of domestic payment ${payment.id} changed since it was evaluated " +
                                "(review ${entity.holdReview}, decision ${entity.holdDecision})"
                        }
                    }
                    entity.applyBookingTransition(payment, acceptedBooking, provenTransactionId)
                    entity.status = payment.status.name
                    entity.rejectReason = payment.rejectReason?.name
                    entity.rejectDetail = payment.rejectDetail
                    entity.submittedAt = payment.submittedAt
                    entity.settledAt = payment.settledAt
                    entity.updatedAt = payment.updatedAt
                    entity.aggregateRevision = payment.aggregateRevision
                    // Hold facts only ever go from unset to set; a writer holding an older read of
                    // the payment must not clear what another transaction recorded meanwhile.
                    entity.heldAt = entity.heldAt ?: payment.heldAt
                    if (entity.holdCaseRequestedAt == null && payment.holdCaseRequestedAt != null) {
                        entity.holdCaseRequestedAt = payment.holdCaseRequestedAt
                        entity.holdCaseReasons = payment.holdCaseReasons.toColumn()
                    }
                    // holdDecision is written by recordHoldDecision alone and never by a lifecycle write.
                }
                // One after another on the one session; every row is part of this transaction.
                .flatMap {
                    outboxMessages.fold(Uni.createFrom().voidItem()) { written, message ->
                        written.flatMap { outboxRepository.persistWithinCurrentTransaction(message).replaceWithVoid() }
                    }.replaceWith(payment)
                }
        }
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
            val constraint = (cause as? org.hibernate.exception.ConstraintViolationException)?.constraintName
            if (constraint != null && constraint in IDEMPOTENCY_CONSTRAINTS) return@any true
            val sql = cause as? SQLException ?: return@any false
            val message = sql.message.orEmpty()
            val duplicateKey = sql.sqlState == UNIQUE_VIOLATION || message.contains("($UNIQUE_VIOLATION)")
            duplicateKey && IDEMPOTENCY_CONSTRAINTS.any { message.contains("\"$it\"") }
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

            amount.compareTo(payment.amount.amount) != 0 -> "amount does not match the reserved amount"

            currency != payment.currency -> "currency does not match the reserved currency"

            idempotencyKeyHash != DelegatedSpendReservationSnapshot.hashIdempotencyKey(payment.idempotencyKey) ->
                "Idempotency-Key does not match the reservation"

            else -> null
        }

    private companion object {
        const val UNIQUE_VIOLATION = "23505"
        val IDEMPOTENCY_CONSTRAINTS = setOf(
            "domestic_payments_idempotency_key_key",
            "uq_domestic_payment_original_submission_key",
        )
    }
}
