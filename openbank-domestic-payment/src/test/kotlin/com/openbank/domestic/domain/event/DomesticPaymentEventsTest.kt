// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.domain.event

import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticTransferScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class DomesticPaymentEventsTest {

    private fun payment(
        initiatedByPartyId: UUID? = UUID.randomUUID(),
        delegationId: UUID? = null,
        reservationId: UUID? = null,
    ) = DomesticPayment(
        id = UUID.randomUUID(),
        idempotencyKey = "idem-event",
        status = DomesticPaymentStatus.VALIDATED,
        debtorAccountId = UUID.randomUUID(),
        debtorAccountNumber = "1000",
        debtorBankCode = "0800",
        debtorName = "Payer",
        creditorAccountNumber = "2000",
        creditorBankCode = "0100",
        creditorName = "Payee",
        amount = BigDecimal("99.50"),
        currency = "CZK",
        variableSymbol = "VS",
        specificSymbol = null,
        constantSymbol = null,
        messageForPayee = null,
        priority = DomesticPaymentPriority.URGENT,
        transferScope = DomesticTransferScope.INTERNAL_CLIENT,
        technicalAccountCode = null,
        statementLabel = null,
        endToEndId = "DOMU42",
        rejectReason = null,
        rejectDetail = null,
        submittedAt = null,
        settledAt = null,
        createdAt = Instant.parse("2026-06-01T09:00:00Z"),
        updatedAt = Instant.parse("2026-06-01T09:00:00Z"),
        initiatedByPartyId = initiatedByPartyId,
        delegationId = delegationId,
        reservationId = reservationId,
    )

    @Test
    fun `toCreatedEvent projects the wire-relevant payment fields`() {
        val payment = payment()
        val now = Instant.parse("2026-06-01T10:00:00Z")

        val event = payment.toCreatedEvent(Clock.fixed(now, ZoneOffset.UTC))

        assertThat(event.paymentId).isEqualTo(payment.id)
        assertThat(event.idempotencyKey).isEqualTo("idem-event")
        assertThat(event.status).isEqualTo(DomesticPaymentStatus.VALIDATED)
        assertThat(event.debtorAccountId).isEqualTo(payment.debtorAccountId)
        assertThat(event.debtorAccountNumber).isEqualTo("1000")
        assertThat(event.debtorBankCode).isEqualTo("0800")
        assertThat(event.creditorAccountNumber).isEqualTo("2000")
        assertThat(event.creditorBankCode).isEqualTo("0100")
        assertThat(event.amount).isEqualByComparingTo(BigDecimal("99.50"))
        assertThat(event.currency).isEqualTo("CZK")
        assertThat(event.priority).isEqualTo(DomesticPaymentPriority.URGENT)
        assertThat(event.endToEndId).isEqualTo("DOMU42")
        assertThat(event.occurredAt).isEqualTo(now)
        assertThat(event.initiatedByPartyId).isEqualTo(payment.initiatedByPartyId)
        // AuditConsumer attribution fields (#3994) — before these existed the audit trail
        // recorded 124 real domestic-payment rows as event_type="UNKNOWN"/source_service="unknown".
        assertThat(event.eventType).isEqualTo("DOMESTIC_PAYMENT_CREATED")
        assertThat(event.sourceService).isEqualTo("domestic-payment")
    }

    @Test
    fun `toCreatedEvent carries no actor when the payment was created without one`() {
        val payment = payment(initiatedByPartyId = null)

        val event = payment.toCreatedEvent(Clock.fixed(Instant.parse("2026-06-01T10:00:00Z"), ZoneOffset.UTC))

        assertThat(event.initiatedByPartyId).isNull()
    }

    @Test
    fun `created event is a value object compared by content`() {
        val now = Instant.parse("2026-06-01T10:00:00Z")
        val payment = payment()

        val clock = Clock.fixed(now, ZoneOffset.UTC)
        assertThat(payment.toCreatedEvent(clock)).isEqualTo(payment.toCreatedEvent(clock))
    }

    @Test
    fun `created and status events carry the same delegated spend context`() {
        val initiator = UUID.randomUUID()
        val delegation = UUID.randomUUID()
        val reservation = UUID.randomUUID()
        val previous = payment(initiator, delegation, reservation)
        val current = previous.copy(status = DomesticPaymentStatus.SENT_TO_CLEARING)
        val clock = Clock.fixed(Instant.parse("2026-06-01T10:00:00Z"), ZoneOffset.UTC)

        val created = current.toCreatedEvent(clock)
        val changed = current.toStatusChangedEvent(previous, clock)

        assertThat(created.initiatedByPartyId).isEqualTo(initiator)
        assertThat(created.delegationId).isEqualTo(delegation)
        assertThat(created.reservationId).isEqualTo(reservation)
        assertThat(changed.initiatedByPartyId).isEqualTo(initiator)
        assertThat(changed.delegationId).isEqualTo(delegation)
        assertThat(changed.reservationId).isEqualTo(reservation)
    }

    @Test
    fun `status changed event carries the transition and reject metadata`() {
        val paymentId = UUID.randomUUID()
        val occurredAt = Instant.parse("2026-06-01T12:00:00Z")

        val event = DomesticPaymentStatusChangedEvent(
            paymentId = paymentId,
            previousStatus = DomesticPaymentStatus.SENT_TO_CLEARING,
            newStatus = DomesticPaymentStatus.REJECTED,
            rejectReason = "SANCTIONS_HIT",
            rejectDetail = "creditor on list",
            occurredAt = occurredAt,
        )

        assertThat(event.paymentId).isEqualTo(paymentId)
        assertThat(event.previousStatus).isEqualTo(DomesticPaymentStatus.SENT_TO_CLEARING)
        assertThat(event.newStatus).isEqualTo(DomesticPaymentStatus.REJECTED)
        assertThat(event.rejectReason).isEqualTo("SANCTIONS_HIT")
        assertThat(event.rejectDetail).isEqualTo("creditor on list")
        assertThat(event.occurredAt).isEqualTo(occurredAt)
        assertThat(event.eventType).isEqualTo("DOMESTIC_PAYMENT_STATUS_CHANGED")
        assertThat(event.sourceService).isEqualTo("domestic-payment")
    }
}
