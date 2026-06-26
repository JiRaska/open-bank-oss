// SPDX-License-Identifier: MPL-2.0\n// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.\n// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.\n
package com.openbank.sepa.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SepaPaymentTest {

    @Test
    fun `reject transition requires reject reason`() {
        assertThatThrownBy {
            payment().transitionTo(SepaPaymentStatus.REJECTED, clock = Clock.systemUTC())
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Reject reason is required")
    }

    @Test
    fun `completed transition sets submitted and completed timestamps`() {
        val now = Instant.parse("2026-01-02T00:00:00Z")

        val transitioned = payment(status = SepaPaymentStatus.PROCESSING).transitionTo(
            targetStatus = SepaPaymentStatus.COMPLETED,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        assertThat(transitioned.status).isEqualTo(SepaPaymentStatus.COMPLETED)
        assertThat(transitioned.submittedAt).isEqualTo(now)
        assertThat(transitioned.completedAt).isEqualTo(now)
    }

    private fun payment(status: SepaPaymentStatus = SepaPaymentStatus.RECEIVED) = SepaPayment(
        id = UUID.randomUUID(),
        idempotencyKey = "idem",
        type = SepaPaymentType.SCT,
        status = status,
        debtorAccountId = UUID.randomUUID(),
        debtorIban = "CZ6500000000000000000001",
        debtorName = "Debtor",
        creditorIban = "DE89370400440532013000",
        creditorName = "Creditor",
        creditorBic = "COBADEFFXXX",
        amount = BigDecimal("10.00"),
        currency = "EUR",
        remittanceInfo = null,
        endToEndId = "E2E1",
        rejectReason = null,
        rejectDetail = null,
        submittedAt = null,
        completedAt = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )
}
