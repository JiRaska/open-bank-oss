// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.domain.event

import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class DomesticPaymentCreatedEvent(
    val paymentId: UUID,
    val idempotencyKey: String,
    val status: DomesticPaymentStatus,
    val debtorAccountId: UUID,
    val debtorAccountNumber: String,
    val debtorBankCode: String,
    val creditorAccountNumber: String,
    val creditorBankCode: String,
    val amount: BigDecimal,
    val currency: String,
    val priority: DomesticPaymentPriority,
    val endToEndId: String,
    val occurredAt: Instant,
    /**
     * The authenticated caller who submitted this payment, or `null` when there was none (issue
     * #3994). Named `initiatedByPartyId`, not `actorId`: it is the same spelling
     * `transaction.initiated` already uses (ADR-0021) and that `AuditConsumer.resolveActor` already
     * reads as its third-priority actor key, so this recovers actor attribution for domestic
     * payments with no consumer-side change.
     */
    val initiatedByPartyId: UUID?,
    /** Delegation grant that authorized the payment; null for an owner-initiated payment. */
    val delegationId: UUID? = null,
    /** Spend reservation bound to the payment; present exactly when [delegationId] is present. */
    val reservationId: UUID? = null,
    /**
     * Discriminator read by `AuditConsumer` (`node.textOrNull("eventType")`) — issue #3994. Before
     * this field the body had no `eventType` key at all, so the consumer fell all the way through
     * to `address.ceType` (the outbox `ce-type` header, itself only populated once #3994's
     * consumer-side fix landed) or the `"UNKNOWN"` sentinel. SCREAMING_SNAKE_CASE to match the
     * fleet's other direct body-field producers (`BalanceEventType.BALANCE_UPDATED`,
     * customer-edge's `CUSTOMER_POCKET_EXCHANGED`/`STANDING_ORDER_CREATED`) rather than the
     * dotted-lowercase spelling the outbox `eventType` column uses for the same event
     * (`domestic.payment.created`) — that column feeds the `ce-type` header, a different audience.
     */
    val eventType: String = "DOMESTIC_PAYMENT_CREATED",
    /**
     * Producing service, read by `AuditConsumer.resolveSourceService` as the strongest
     * (EVENT-sourced) attribution — stronger than its topic-derived fallback (issue #3994). Value
     * matches the fleet's audit convention: the module directory without the `openbank-` prefix,
     * the same spelling `TopicAttribution` already maps `openbank.domestic.payment.events` to and
     * that customer-edge already writes for its own events.
     */
    val sourceService: String = "domestic-payment",
)

data class DomesticPaymentStatusChangedEvent(
    val paymentId: UUID,
    val previousStatus: DomesticPaymentStatus,
    val newStatus: DomesticPaymentStatus,
    val rejectReason: String?,
    val rejectDetail: String?,
    val occurredAt: Instant,
    /** Customer who initiated the payment, preserved across asynchronous status transitions. */
    val initiatedByPartyId: UUID? = null,
    /** Delegation grant that authorized the payment; null for an owner-initiated payment. */
    val delegationId: UUID? = null,
    /** Spend reservation bound to the payment; present exactly when [delegationId] is present. */
    val reservationId: UUID? = null,
    /** See [DomesticPaymentCreatedEvent.eventType] (#3994). */
    val eventType: String = "DOMESTIC_PAYMENT_STATUS_CHANGED",
    /** See [DomesticPaymentCreatedEvent.sourceService] (#3994). */
    val sourceService: String = "domestic-payment",
)

fun DomesticPayment.toCreatedEvent(clock: Clock) = DomesticPaymentCreatedEvent(
    paymentId = id,
    idempotencyKey = idempotencyKey,
    status = status,
    debtorAccountId = debtorAccountId,
    debtorAccountNumber = debtorAccountNumber,
    debtorBankCode = debtorBankCode,
    creditorAccountNumber = creditorAccountNumber,
    creditorBankCode = creditorBankCode,
    amount = amount,
    currency = currency,
    priority = priority,
    endToEndId = endToEndId,
    occurredAt = Instant.now(clock),
    initiatedByPartyId = initiatedByPartyId,
    delegationId = delegationId,
    reservationId = reservationId,
    eventType = "DOMESTIC_PAYMENT_CREATED",
    sourceService = "domestic-payment",
)

fun DomesticPayment.toStatusChangedEvent(previous: DomesticPayment, clock: Clock) = DomesticPaymentStatusChangedEvent(
    paymentId = id,
    previousStatus = previous.status,
    newStatus = status,
    rejectReason = rejectReason?.name,
    rejectDetail = rejectDetail,
    occurredAt = Instant.now(clock),
    initiatedByPartyId = initiatedByPartyId,
    delegationId = delegationId,
    reservationId = reservationId,
    eventType = "DOMESTIC_PAYMENT_STATUS_CHANGED",
    sourceService = "domestic-payment",
)
