// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.domain.event

import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaPaymentType
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class SepaPaymentCreatedEvent(
    val paymentId: UUID,
    val idempotencyKey: String,
    val type: SepaPaymentType,
    val status: SepaPaymentStatus,
    val debtorAccountId: UUID,
    val debtorIban: String,
    val creditorIban: String,
    val amount: BigDecimal,
    val currency: String,
    val endToEndId: String,
    val occurredAt: Instant,
    /**
     * Producing service, read by `AuditConsumer.resolveSourceService` (audit-service) as the
     * strongest (EVENT-sourced) attribution — issue #3994/#5256. `EventAttribution.TopicAttribution`
     * already maps `openbank.sepa.payment.events` -> `sepa-payment` correctly, but only as
     * TOPIC-sourced, not the producer's own claim, and audit-service subscribes to this topic today
     * (`openbank-audit-service/src/main/resources/application.yaml`'s consumed-topics list), so this
     * is a live attribution upgrade. Serialised via `objectMapper.writeValueAsString` in
     * `KafkaSepaPaymentEventPublisher`, so the wire key exists only as this Kotlin property name.
     */
    val sourceService: String = "sepa-payment",
)

data class SepaPaymentStatusChangedEvent(
    val paymentId: UUID,
    val previousStatus: SepaPaymentStatus,
    val newStatus: SepaPaymentStatus,
    val rejectReason: String?,
    val rejectDetail: String?,
    val occurredAt: Instant,
    /** See [SepaPaymentCreatedEvent.sourceService] (#3994/#5256). */
    val sourceService: String = "sepa-payment",
)

fun SepaPayment.toStatusChangedEvent(previousStatus: SepaPaymentStatus, clock: Clock) = SepaPaymentStatusChangedEvent(
    paymentId = id,
    previousStatus = previousStatus,
    newStatus = status,
    rejectReason = rejectReason?.name,
    rejectDetail = rejectDetail,
    occurredAt = Instant.now(clock),
    sourceService = "sepa-payment",
)

fun SepaPayment.toCreatedEvent(clock: Clock) = SepaPaymentCreatedEvent(
    paymentId = id,
    idempotencyKey = idempotencyKey,
    type = type,
    status = status,
    debtorAccountId = debtorAccountId,
    debtorIban = debtorIban,
    creditorIban = creditorIban,
    amount = amount,
    currency = currency,
    endToEndId = endToEndId,
    occurredAt = Instant.now(clock),
    sourceService = "sepa-payment",
)
