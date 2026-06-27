// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.port.out

import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepository
import java.util.UUID

/**
 * Outbound persistence port for the domestic-payment aggregate.
 *
 * Mutating operations that produce a domain event take the corresponding [OutboxMessage]
 * so the row and its outbox entry are written in the SAME database transaction (transactional
 * outbox pattern): either both commit or neither does.
 */
interface DomesticPaymentRepository {

    /** Persist a new payment together with its domain-event outbox message, atomically. */
    suspend fun save(payment: DomesticPayment, outboxMessage: OutboxMessage): DomesticPayment

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
}
