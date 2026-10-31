// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.domain.event

import com.openbank.libs.domain.money.Money
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaPaymentType
import com.openbank.sepa.domain.model.SepaRejectReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SepaPaymentEventsTest {

    private fun payment(
        status: SepaPaymentStatus = SepaPaymentStatus.RECEIVED,
        rejectReason: SepaRejectReason? = null,
        rejectDetail: String? = null,
    ) = SepaPayment(
        id = UUID.randomUUID(),
        idempotencyKey = "idem-event",
        type = SepaPaymentType.SCT_INST,
        status = status,
        debtorAccountId = UUID.randomUUID(),
        debtorIban = "DE89370400440532013000",
        debtorName = "Alice Example",
        creditorIban = "FR7630006000011234567890189",
        creditorName = "Bob Example",
        creditorBic = "DEUTDEFF",
        amount = Money.of(BigDecimal("123.45"), "EUR"),
        remittanceInfo = "ref",
        endToEndId = "E2E-event",
        rejectReason = rejectReason,
        rejectDetail = rejectDetail,
        submittedAt = null,
        completedAt = null,
        createdAt = Instant.parse("2026-01-02T10:00:00Z"),
        updatedAt = Instant.parse("2026-01-02T10:00:00Z"),
    )

    @Test
    fun `toCreatedEvent projects identity, parties and amount with the supplied timestamp`() {
        // revision 3 (not the default 0): the version getter mutant that returns a hardcoded
        // 0 is invisible to an isZero assertion — the projection must carry the aggregate's
        // actual revision so consumers can order events against the payment.
        val payment = payment().copy(revision = 3)
        val now = Instant.parse("2026-01-02T11:00:00Z")

        val event = payment.toCreatedEvent(Clock.fixed(now, ZoneOffset.UTC))

        assertThat(event.paymentId).isEqualTo(payment.id)
        assertThat(event.version).isEqualTo(3)
        assertThat(event.idempotencyKey).isEqualTo("idem-event")
        assertThat(event.type).isEqualTo(SepaPaymentType.SCT_INST)
        assertThat(event.status).isEqualTo(SepaPaymentStatus.RECEIVED)
        assertThat(event.debtorAccountId).isEqualTo(payment.debtorAccountId)
        assertThat(event.debtorIban).isEqualTo(payment.debtorIban)
        assertThat(event.creditorIban).isEqualTo(payment.creditorIban)
        assertThat(event.amount).isEqualByComparingTo(BigDecimal("123.45"))
        assertThat(event.currency).isEqualTo("EUR")
        assertThat(event.endToEndId).isEqualTo("E2E-event")
        assertThat(event.occurredAt).isEqualTo(now)
        // Issue #3994/#5256: read by AuditConsumer.resolveSourceService as the strongest
        // (EVENT-sourced) attribution, upgrading over EventAttribution.TopicAttribution's
        // TOPIC-sourced `openbank.sepa.payment.events` -> `sepa-payment` fallback.
        assertThat(event.sourceService).isEqualTo("sepa-payment")
    }

    @Test
    fun `toStatusChangedEvent carries previous and new status with reject context`() {
        val payment = payment(
            status = SepaPaymentStatus.REJECTED,
            rejectReason = SepaRejectReason.SANCTIONS_HIT,
            rejectDetail = "OFAC hit",
        ).copy(revision = 3)
        val now = Instant.parse("2026-01-02T12:00:00Z")

        val event = payment.toStatusChangedEvent(SepaPaymentStatus.RECEIVED, Clock.fixed(now, ZoneOffset.UTC))

        assertThat(event.paymentId).isEqualTo(payment.id)
        assertThat(event.version).isEqualTo(3)
        assertThat(event.previousStatus).isEqualTo(SepaPaymentStatus.RECEIVED)
        assertThat(event.newStatus).isEqualTo(SepaPaymentStatus.REJECTED)
        assertThat(event.rejectReason).isEqualTo("SANCTIONS_HIT")
        assertThat(event.rejectDetail).isEqualTo("OFAC hit")
        assertThat(event.occurredAt).isEqualTo(now)
        assertThat(event.sourceService).isEqualTo("sepa-payment")
    }

    @Test
    fun `toStatusChangedEvent leaves reject fields null for a non-reject transition`() {
        val payment = payment(status = SepaPaymentStatus.VALIDATED)

        val event = payment.toStatusChangedEvent(SepaPaymentStatus.RECEIVED, Clock.systemUTC())

        assertThat(event.newStatus).isEqualTo(SepaPaymentStatus.VALIDATED)
        assertThat(event.rejectReason).isNull()
        assertThat(event.rejectDetail).isNull()
    }

    @Test
    fun `returned event carries the full non-repudiation evidence envelope`() {
        // Issue #6056: this record is what a denial-of-having-processed-a-return dispute turns
        // on once audit-service persists it, so every field is asserted — a data-class getter
        // returning a default is indistinguishable from the truth unless the values are
        // distinctive.
        val paymentId = UUID.randomUUID()
        val reversalTransactionId = UUID.randomUUID()
        val occurredAt = Instant.parse("2026-01-02T13:00:00Z")

        val event = SepaPaymentReturnedEvent(
            paymentId = paymentId,
            version = 7,
            originalEndToEndId = "E2E-orig-1",
            returnReasonCode = "AC04",
            actorId = "svc:sepa-gateway",
            actorType = "SERVICE",
            correlationId = "corr-42",
            reversalPerformed = true,
            occurredAt = occurredAt,
            reversalTransactionId = reversalTransactionId,
        )

        assertThat(event.paymentId).isEqualTo(paymentId)
        assertThat(event.version).isEqualTo(7)
        assertThat(event.originalEndToEndId).isEqualTo("E2E-orig-1")
        assertThat(event.returnReasonCode).isEqualTo("AC04")
        assertThat(event.actorId).isEqualTo("svc:sepa-gateway")
        assertThat(event.actorType).isEqualTo("SERVICE")
        assertThat(event.correlationId).isEqualTo("corr-42")
        assertThat(event.reversalPerformed).isTrue()
        assertThat(event.occurredAt).isEqualTo(occurredAt)
        assertThat(event.reversalTransactionId).isEqualTo(reversalTransactionId)
        assertThat(event.eventType).isEqualTo(RETURN_EVIDENCE_EVENT_TYPE)
        assertThat(event.sourceService).isEqualTo("sepa-payment")
    }

    @Test
    fun `returned event defaults reversal transaction and evidence discriminator`() {
        val event = SepaPaymentReturnedEvent(
            paymentId = UUID.randomUUID(),
            version = 1,
            originalEndToEndId = "E2E-orig-2",
            returnReasonCode = null,
            actorId = "operator-1",
            actorType = "EMPLOYEE",
            correlationId = null,
            reversalPerformed = false,
            occurredAt = Instant.parse("2026-01-02T14:00:00Z"),
        )

        assertThat(event.returnReasonCode).isNull()
        assertThat(event.correlationId).isNull()
        assertThat(event.reversalTransactionId).isNull()
        assertThat(event.reversalPerformed).isFalse()
        assertThat(event.eventType).isEqualTo("sepa.payment.returned")
    }
}
