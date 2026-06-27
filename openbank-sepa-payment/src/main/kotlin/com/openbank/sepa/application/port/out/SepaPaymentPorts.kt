// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.application.port.out

import com.openbank.libs.persistence.outbox.OutboxRepository
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import java.time.Instant
import java.util.UUID

/**
 * A domain event to be written into the outbox in the same transaction as the aggregate change.
 * [eventId] and [createdAt] are assigned at construction so callers only supply the payload identity.
 */
data class SepaPaymentOutboxMessage(
    val aggregateId: UUID,
    val eventType: String,
    val payload: String,
    val eventId: UUID = UUID.randomUUID(),
    val createdAt: Instant,
)

/**
 * Outbound persistence port for the SEPA-payment aggregate.
 *
 * Mutating operations that produce a domain event take the corresponding [SepaPaymentOutboxMessage]
 * so the row and its outbox entry are written in the SAME database transaction (transactional
 * outbox pattern): either both commit or neither does.
 */
interface SepaPaymentRepository {

    /** Persist a new payment together with its domain-event outbox message, atomically. */
    suspend fun save(payment: SepaPayment, outboxMessage: SepaPaymentOutboxMessage): SepaPayment

    suspend fun findById(paymentId: UUID): SepaPayment?

    suspend fun findByIdempotencyKey(idempotencyKey: String): SepaPayment?

    suspend fun findByEndToEndId(endToEndId: String): SepaPayment?

    suspend fun list(status: SepaPaymentStatus?, debtorAccountId: UUID?, limit: Int, offset: Int): List<SepaPayment>

    /** Update a payment and enqueue a domain-event outbox message, atomically. */
    suspend fun update(payment: SepaPayment, outboxMessage: SepaPaymentOutboxMessage): SepaPayment
}

/**
 * Outbound port for draining the transactional outbox (read pending, mark sent/failed).
 * Extends [OutboxRepository] from libs — listProcessable, countProcessable, markSent, markFailed
 * are all inherited. Only service-specific write-side concerns remain here.
 */
interface SepaPaymentOutboxRepository : OutboxRepository

/**
 * Outbound port for SEPA-payment domain event serialization.
 * The `*Payload` methods serialize the event body stored in the outbox at write time.
 * Transport (Kafka publish) is handled by the libs [com.openbank.libs.persistence.outbox.OutboxEventPublisher].
 */
interface SepaPaymentEventPublisher {

    fun paymentCreatedPayload(payment: SepaPayment): String

    fun statusChangedPayload(previous: SepaPayment, current: SepaPayment): String
}
