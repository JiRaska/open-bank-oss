// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.port.out

import com.openbank.domestic.domain.model.DelegatedSpendBinding
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepository
import java.time.Instant
import java.util.UUID

/**
 * Outbound persistence port for the domestic-payment aggregate.
 *
 * Mutating operations that produce a domain event take the corresponding [OutboxMessage]
 * so the row and its outbox entry are written in the SAME database transaction (transactional
 * outbox pattern): either both commit or neither does.
 */
@Suppress("TooManyFunctions") // one method per persistence question the rail asks (hexagonal)
interface DomesticPaymentRepository {

    /** Persist a new payment together with its domain-event outbox message, atomically. */
    suspend fun save(payment: DomesticPayment, outboxMessage: OutboxMessage): DomesticPayment

    /**
     * Lock and consume a local PENDING reservation projection while inserting the payment and its
     * created-event outbox row in the same transaction. This is the create-vs-finalizer arbiter.
     */
    suspend fun saveDelegated(
        payment: DomesticPayment,
        outboxMessage: OutboxMessage,
        boundAt: Instant,
        debitOwnerPartyId: UUID,
    ): DelegatedPaymentSaveOutcome

    suspend fun findById(paymentId: UUID): DomesticPayment?

    suspend fun findByIdempotencyKey(idempotencyKey: String): DomesticPayment?

    suspend fun list(
        status: DomesticPaymentStatus?,
        debtorAccountId: UUID?,
        limit: Int,
        offset: Int,
    ): List<DomesticPayment>

    /** Update a payment and enqueue a domain-event outbox message, atomically. */
    suspend fun update(payment: DomesticPayment, outboxMessage: OutboxMessage): DomesticPayment

    /**
     * Set or clear the scheme-dispatch marker on [paymentId] (#4218).
     *
     * A non-null [dispatchedAt] records that a `pacs.008` is about to be handed to the scheme. It
     * is committed in its OWN transaction, before the outbound call, precisely so it outlives the
     * work that follows: if the status update after a successful submit fails, this marker is what
     * stops a later re-drive submitting the same payment to the clearing scheme a second time.
     * Carries no outbox message — internal bookkeeping, not a domain event, and nothing outside
     * this service should react to a dispatch whose outcome is not established.
     *
     * `null` clears it, and is legitimate ONLY once the gateway has proven the request never left
     * this process (connection refused, unknown host). An ambiguous failure — a timeout above all —
     * must keep the marker, because the scheme may hold a live clearing item; for an outbound money
     * instruction a strand an operator can see beats a duplicate payment nobody can recall.
     */
    /**
     * Claims the scheme dispatch for [paymentId], returning `true` only if THIS caller won it.
     *
     * Compare-and-set, not a plain write, and the distinction is the whole guard: a read of
     * `schemeDispatchedAt` followed by an unconditional UPDATE lets two concurrent attempts both
     * observe `null`, both pass, and both hand a pacs.008 to the gateway — the duplicate clearing
     * item #4218 exists to prevent. The predicate lives in the UPDATE so the database arbitrates.
     *
     * A `false` return means someone else is already dispatching (or has dispatched) this payment.
     * The caller must NOT submit.
     */
    suspend fun claimSchemeDispatch(paymentId: UUID, dispatchedAt: Instant): Boolean

    /**
     * Releases a dispatch claim, and may be called ONLY when the gateway has proved the request
     * never left this process. Clearing it re-arms submission, so an ambiguous failure must keep
     * the claim instead.
     */
    suspend fun clearSchemeDispatch(paymentId: UUID)

    /**
     * Ids of payments still in `RECEIVED` that are worth screening again (#3266).
     *
     * A payment held because sanctions screening was unavailable has no other way out: the workflow
     * completed, and nothing consumes the AML case's decision. Bounded three ways so a genuinely
     * held payment is not re-screened forever — [maxAttempts], a [minAge] so a payment mid-flight is
     * never touched, and [limit].
     */
    suspend fun findRedrivable(maxAttempts: Int, minAge: Instant, limit: Int): List<UUID>

    /** Count one re-drive against [paymentId], before it is attempted. */
    suspend fun recordRedriveAttempt(paymentId: UUID)

    /** How many payments currently sit in [status]. */
    suspend fun countByStatus(status: DomesticPaymentStatus): Long

    /**
     * When the oldest payment still in [status] was created, or `null` if none is.
     *
     * Feeds the stranded-payment age gauge (#3273): a payment held in a non-terminal state is a
     * successful-looking 2xx that never progressed, so no error-rate or latency signal can see it —
     * only its age can.
     */
    suspend fun oldestCreatedAt(status: DomesticPaymentStatus): Instant?
}

/** Outbound port for draining the transactional outbox (read pending, mark sent/failed). */
interface DomesticPaymentOutboxRepository : OutboxRepository

/**
 * Outbound port for domestic-payment domain events. The `*Payload` methods serialize the event body
 * that is stored in the outbox at write time; transport (publish) is handled by the Kafka publisher
 * directly via [com.openbank.libs.persistence.outbox.OutboxEventPublisher].
 */
interface DomesticPaymentEventPublisher {

    fun paymentCreatedPayload(payment: DomesticPayment): String

    fun statusChangedPayload(previous: DomesticPayment, current: DomesticPayment): String

    fun delegatedSpendFinalizedAbsentPayload(binding: DelegatedSpendBinding): String
}

sealed interface DelegatedPaymentSaveOutcome {
    data class Created(val payment: DomesticPayment) : DelegatedPaymentSaveOutcome
    data class Replayed(val payment: DomesticPayment) : DelegatedPaymentSaveOutcome
    data object ProjectionMissing : DelegatedPaymentSaveOutcome
    data object FinalizedAbsent : DelegatedPaymentSaveOutcome
    data class TupleMismatch(val reason: String) : DelegatedPaymentSaveOutcome
}
