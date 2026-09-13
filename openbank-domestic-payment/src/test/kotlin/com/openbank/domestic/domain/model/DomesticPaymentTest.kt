// SPDX-License-Identifier: Apache-2.0\n// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.\n// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.\n
package com.openbank.domestic.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class DomesticPaymentTest {

    @Test
    fun `rejected transition requires reason`() {
        assertThatThrownBy {
            payment().transitionTo(DomesticPaymentStatus.REJECTED, clock = Clock.systemUTC())
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Reject reason is required")
    }

    @Test
    fun `settled transition stamps submitted and settled timestamps`() {
        val now = Instant.parse("2026-01-02T00:00:00Z")

        val settled = payment(status = DomesticPaymentStatus.SENT_TO_CLEARING).transitionTo(
            DomesticPaymentStatus.SETTLED,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        assertThat(settled.submittedAt).isEqualTo(now)
        assertThat(settled.settledAt).isEqualTo(now)
    }

    @Test
    fun `delegation and reservation form an indivisible pair`() {
        assertThatThrownBy {
            payment().copy(delegationId = UUID.randomUUID())
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must either both be present or both be absent")

        assertThatThrownBy {
            payment().copy(reservationId = UUID.randomUUID())
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must either both be present or both be absent")
    }

    private fun payment(status: DomesticPaymentStatus = DomesticPaymentStatus.RECEIVED) = DomesticPayment(
        id = UUID.randomUUID(),
        idempotencyKey = "idem",
        status = status,
        debtorAccountId = UUID.randomUUID(),
        debtorAccountNumber = "1234567890",
        debtorBankCode = "0800",
        debtorName = "Debtor",
        creditorAccountNumber = "0987654321",
        creditorBankCode = "2010",
        creditorName = "Creditor",
        amount = BigDecimal("20.00"),
        currency = "CZK",
        variableSymbol = null,
        specificSymbol = null,
        constantSymbol = null,
        messageForPayee = null,
        priority = DomesticPaymentPriority.STANDARD,
        transferScope = DomesticTransferScope.INTERNAL_CLIENT,
        technicalAccountCode = null,
        statementLabel = null,
        endToEndId = "DOM1",
        rejectReason = null,
        rejectDetail = null,
        submittedAt = null,
        settledAt = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )
}
