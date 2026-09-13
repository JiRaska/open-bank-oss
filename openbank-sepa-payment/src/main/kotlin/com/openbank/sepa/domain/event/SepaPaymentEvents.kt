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

/**
 * The non-repudiation record of one `POST /api/v1/sepa-payments/returns` invocation (issue #6056).
 *
 * **Why this exists next to [SepaPaymentStatusChangedEvent].** The status-changed event already
 * says that a payment reached `RETURNED`; it does not say **who** presented the pacs.004 that
 * caused it, under which correlation id, or against which original end-to-end reference. Those
 * are exactly the facts a denial-of-having-processed-a-return dispute turns on, and the threat
 * model's R row credited an `AuditService` that has never existed in this repository.
 *
 * **Where the evidence actually lands.** This is written into `sepa_payment_outbox` in the SAME
 * transaction as the `RETURNED` transition (so the record and the act commit together or not at
 * all), dispatched to `openbank.sepa.payment.events`, and consumed by openbank-audit-service's
 * `AuditConsumer` into the append-only, hash-chained `audit_entries` table. It is deliberately
 * NOT routed through `com.openbank.libs.audit.AuditEventPublisher`: the only implementation of
 * that interface in this repository is `LoggingAuditEventPublisher`, and a log line written by
 * the same code path whose behaviour is in dispute is not evidence.
 *
 * The field names are chosen to match what `AuditConsumer` reads, so the row is attributed rather
 * than landing on its `"unknown"`/absent sentinels: `eventType`, `actorId`, `actorType`,
 * `correlationId`, `occurredAt`, `sourceService`, and `paymentId` (which maps to the `PAYMENT`
 * aggregate type).
 */
data class SepaPaymentReturnedEvent(
    val paymentId: UUID,
    /** `OrgnlEndToEndId` from the pacs.004 — the reference the dispute would be raised against. */
    val originalEndToEndId: String,
    /** pacs.004 `RtrRsnInf/Rsn/Cd` (AC04, AM09, ...); null when the message carried none. */
    val returnReasonCode: String?,
    /** Server-derived principal name of the caller that presented the pacs.004. Never from the body. */
    val actorId: String,
    /** Most-specific role held by that principal (`SecurityContext.actorType`). */
    val actorType: String,
    val correlationId: String?,
    /** Whether the ledger reversal was actually performed, or skipped (no txId / reversal down). */
    val reversalPerformed: Boolean,
    val occurredAt: Instant,
    /** Stated explicitly so the audit row is EVENT-attributed rather than topic-inferred (#3994). */
    val eventType: String = RETURN_EVIDENCE_EVENT_TYPE,
    /** See [SepaPaymentCreatedEvent.sourceService] (#3994/#5256). */
    val sourceService: String = "sepa-payment",
)

/** Outbox/`ce-type` discriminator for [SepaPaymentReturnedEvent]. */
const val RETURN_EVIDENCE_EVENT_TYPE = "sepa.payment.returned"

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
