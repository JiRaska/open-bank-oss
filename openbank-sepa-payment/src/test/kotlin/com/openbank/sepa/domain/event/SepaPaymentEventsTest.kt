// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.domain.event

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
        amount = BigDecimal("123.45"),
        currency = "EUR",
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
        val payment = payment()
        val now = Instant.parse("2026-01-02T11:00:00Z")

        val event = payment.toCreatedEvent(Clock.fixed(now, ZoneOffset.UTC))

        assertThat(event.paymentId).isEqualTo(payment.id)
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
        )
        val now = Instant.parse("2026-01-02T12:00:00Z")

        val event = payment.toStatusChangedEvent(SepaPaymentStatus.RECEIVED, Clock.fixed(now, ZoneOffset.UTC))

        assertThat(event.paymentId).isEqualTo(payment.id)
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
}
