// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sepainstant.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

class SctInstPaymentTest {

    @Test
    fun `construction sets all fields correctly`() {
        val now = OffsetDateTime.parse("2026-01-01T10:15:30Z")
        val paymentId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val debtorAccountId = UUID.fromString("22222222-2222-2222-2222-222222222222")

        val payment = SctInstPayment(
            id = 42,
            paymentId = paymentId,
            idempotencyKey = "idem-123",
            status = SctInstStatus.PROCESSING,
            debtorAccountId = debtorAccountId,
            debtorIban = "DE89370400440532013000",
            debtorName = "Alice Debtor",
            creditorIban = "FR7630006000011234567890189",
            creditorName = "Bob Creditor",
            creditorBic = "AGRIFRPP",
            amount = BigDecimal("123.45"),
            currency = "EUR",
            remittanceInfo = "Invoice 42",
            endToEndId = "E2E-123",
            executionTimeoutAt = now,
            settledAt = now.plusMinutes(1),
            recalledAt = now.plusMinutes(2),
            recallReason = "Customer request",
            rejectReason = "REJECTED",
            rejectDetail = "Invalid beneficiary",
            submittedAt = now.minusMinutes(1),
            createdAt = now.minusHours(1),
            updatedAt = now
        )

        assertThat(payment.id).isEqualTo(42)
        assertThat(payment.paymentId).isEqualTo(paymentId)
        assertThat(payment.idempotencyKey).isEqualTo("idem-123")
        assertThat(payment.status).isEqualTo(SctInstStatus.PROCESSING)
        assertThat(payment.debtorAccountId).isEqualTo(debtorAccountId)
        assertThat(payment.debtorIban).isEqualTo("DE89370400440532013000")
        assertThat(payment.debtorName).isEqualTo("Alice Debtor")
        assertThat(payment.creditorIban).isEqualTo("FR7630006000011234567890189")
        assertThat(payment.creditorName).isEqualTo("Bob Creditor")
        assertThat(payment.creditorBic).isEqualTo("AGRIFRPP")
        assertThat(payment.amount).isEqualByComparingTo("123.45")
        assertThat(payment.currency).isEqualTo("EUR")
        assertThat(payment.remittanceInfo).isEqualTo("Invoice 42")
        assertThat(payment.endToEndId).isEqualTo("E2E-123")
        assertThat(payment.executionTimeoutAt).isEqualTo(now)
        assertThat(payment.settledAt).isEqualTo(now.plusMinutes(1))
        assertThat(payment.recalledAt).isEqualTo(now.plusMinutes(2))
        assertThat(payment.recallReason).isEqualTo("Customer request")
        assertThat(payment.rejectReason).isEqualTo("REJECTED")
        assertThat(payment.rejectDetail).isEqualTo("Invalid beneficiary")
        assertThat(payment.submittedAt).isEqualTo(now.minusMinutes(1))
        assertThat(payment.createdAt).isEqualTo(now.minusHours(1))
        assertThat(payment.updatedAt).isEqualTo(now)
    }
}
