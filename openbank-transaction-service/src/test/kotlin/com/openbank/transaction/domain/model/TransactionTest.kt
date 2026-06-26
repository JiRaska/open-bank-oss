// SPDX-License-Identifier: MPL-2.0\n// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.\n// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.\n
package com.openbank.transaction.domain.model

import com.openbank.libs.domain.money.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class TransactionTest {

    private fun transaction(status: TransactionStatus = TransactionStatus.PENDING) = Transaction(
        id = UUID.randomUUID(),
        referenceNumber = "TXN-1",
        type = TransactionType.TRANSFER,
        sourceAccountId = UUID.randomUUID(),
        targetAccountId = UUID.randomUUID(),
        amount = Money.of(BigDecimal("10.00"), "CZK"),
        fxRate = null,
        baseAmount = Money.of(BigDecimal("10.00"), "CZK"),
        status = status,
        description = "transfer",
        valueDate = LocalDate.parse("2026-01-02"),
        bookingDate = LocalDate.parse("2026-01-02"),
        initiatedAt = Instant.parse("2026-01-02T00:00:00Z"),
        completedAt = null,
        failedAt = null,
        failureReason = null,
        idempotencyKey = "idem",
        version = 0L,
    )

    @Test
    fun `pending transaction can start processing then complete`() {
        val processing = transaction().startProcessing()
        val completed = processing.complete(Clock.systemUTC())

        assertThat(processing.status).isEqualTo(TransactionStatus.PROCESSING)
        assertThat(completed.status).isEqualTo(TransactionStatus.COMPLETED)
        assertThat(completed.completedAt).isNotNull()
    }

    @Test
    fun `completed transaction cannot fail`() {
        assertThatThrownBy { transaction(TransactionStatus.COMPLETED).fail("late failure", Clock.systemUTC()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Cannot fail completed transaction")
    }
}
