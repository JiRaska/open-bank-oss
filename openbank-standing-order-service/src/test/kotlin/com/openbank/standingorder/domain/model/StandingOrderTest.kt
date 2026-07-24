// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.stream.Stream

class StandingOrderTest {

    @Test
    fun `pause() throws IllegalArgumentException if status is not ACTIVE`() {
        val order = standingOrder(status = StandingOrderStatus.PAUSED)

        assertThatThrownBy { order.pause(FIXED_NOW) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Only ACTIVE orders can be paused")
    }

    @Test
    fun `resume() throws IllegalArgumentException if status is not PAUSED`() {
        val order = standingOrder(status = StandingOrderStatus.ACTIVE)

        assertThatThrownBy { order.resume(FIXED_NOW) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Only PAUSED orders can be resumed")
    }

    @Test
    fun `cancel() throws IllegalArgumentException if already CANCELLED`() {
        val order = standingOrder(status = StandingOrderStatus.CANCELLED)

        assertThatThrownBy { order.cancel(FIXED_NOW) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Cannot cancel order in status CANCELLED")
    }

    @Test
    fun `cancel() throws IllegalArgumentException if already COMPLETED`() {
        val order = standingOrder(status = StandingOrderStatus.COMPLETED)

        assertThatThrownBy { order.cancel(FIXED_NOW) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Cannot cancel order in status COMPLETED")
    }

    @Test
    fun `recordExecution() increments executionCount and updates nextExecutionDate`() {
        val order = standingOrder(
            nextExecutionDate = LocalDate.of(2026, 2, 1),
            executionCount = 3,
            lastExecutionDate = LocalDate.of(2026, 1, 1),
            status = StandingOrderStatus.ACTIVE,
        )

        val updated = order.recordExecution(LocalDate.of(2026, 3, 1), FIXED_NOW)

        assertThat(updated.executionCount).isEqualTo(4)
        assertThat(updated.nextExecutionDate).isEqualTo(LocalDate.of(2026, 3, 1))
        assertThat(updated.lastExecutionDate).isEqualTo(LocalDate.of(2026, 2, 1))
        assertThat(updated.updatedAt).isEqualTo(FIXED_NOW)
    }

    @Test
    fun `recordExecution() sets status COMPLETED when nextDate is after endDate`() {
        val order = standingOrder(
            endDate = LocalDate.of(2026, 3, 1),
            status = StandingOrderStatus.ACTIVE,
        )

        val updated = order.recordExecution(LocalDate.of(2026, 3, 2), FIXED_NOW)

        assertThat(updated.status).isEqualTo(StandingOrderStatus.COMPLETED)
    }

    @Test
    fun `recordFailure() increments failureCount`() {
        val order = standingOrder(status = StandingOrderStatus.ACTIVE, failureCount = 0)

        val updated = order.recordFailure(FIXED_NOW)

        assertThat(updated.failureCount).isEqualTo(1)
        assertThat(updated.status).isEqualTo(StandingOrderStatus.ACTIVE)
    }

    @Test
    fun `recordFailure() transitions to FAILED after MAX_CONSECUTIVE_FAILURES`() {
        val order = standingOrder(status = StandingOrderStatus.ACTIVE, failureCount = 2)

        val updated = order.recordFailure(FIXED_NOW)

        assertThat(updated.failureCount).isEqualTo(3)
        assertThat(updated.status).isEqualTo(StandingOrderStatus.FAILED)
    }

    @Test
    fun `recordFailure() throws when order is not ACTIVE`() {
        val order = standingOrder(status = StandingOrderStatus.PAUSED)

        assertThatThrownBy { order.recordFailure(FIXED_NOW) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `confirmExecution() resets failureCount to zero`() {
        val order = standingOrder(status = StandingOrderStatus.ACTIVE, failureCount = 2)

        val updated = order.confirmExecution(FIXED_NOW)

        assertThat(updated.failureCount).isEqualTo(0)
        assertThat(updated.status).isEqualTo(StandingOrderStatus.ACTIVE)
    }

    @Test
    fun `recordExecution completes a ONCE order after its single execution`() {
        val order = standingOrder(
            frequency = Frequency.ONCE,
            startDate = LocalDate.of(2026, 6, 1),
            endDate = null,
            nextExecutionDate = LocalDate.of(2026, 6, 1),
            status = StandingOrderStatus.ACTIVE,
        )

        val executed = order.recordExecution(order.calculateNextDate(order.nextExecutionDate), FIXED_NOW)

        assertThat(executed.status).isEqualTo(StandingOrderStatus.COMPLETED)
        assertThat(executed.executionCount).isEqualTo(order.executionCount + 1)
    }

    private fun standingOrder(
        id: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        idempotencyKey: String = "idempotency-key",
        partyId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002"),
        debitAccountId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000003"),
        debtorIban: String? = "DE89370400440532013001",
        debtorName: String? = "Debtor",
        creditorIban: String = "DE89370400440532013000",
        creditorName: String = "Creditor",
        creditorBic: String? = "DEUTDEFF",
        amountMinorUnits: Long = 1000L,
        currency: String = "EUR",
        frequency: Frequency = Frequency.MONTHLY,
        paymentType: PaymentType = PaymentType.SEPA_CREDIT,
        remittanceInfo: String? = "Rent",
        startDate: LocalDate = LocalDate.of(2026, 1, 1),
        endDate: LocalDate? = LocalDate.of(2026, 12, 31),
        nextExecutionDate: LocalDate = LocalDate.of(2026, 2, 1),
        lastExecutionDate: LocalDate? = LocalDate.of(2026, 1, 1),
        executionCount: Int = 3,
        failureCount: Int = 0,
        status: StandingOrderStatus = StandingOrderStatus.ACTIVE,
        createdAt: Instant = FIXED_NOW,
        updatedAt: Instant = FIXED_NOW,
    ) = StandingOrder(
        id = id,
        idempotencyKey = idempotencyKey,
        partyId = partyId,
        debitAccountId = debitAccountId,
        debtorIban = debtorIban,
        debtorName = debtorName,
        creditorIban = creditorIban,
        creditorName = creditorName,
        creditorBic = creditorBic,
        amountMinorUnits = amountMinorUnits,
        currency = currency,
        frequency = frequency,
        paymentType = paymentType,
        remittanceInfo = remittanceInfo,
        startDate = startDate,
        endDate = endDate,
        nextExecutionDate = nextExecutionDate,
        lastExecutionDate = lastExecutionDate,
        executionCount = executionCount,
        failureCount = failureCount,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    @ParameterizedTest
    @MethodSource("frequencyNextDateCases")
    fun `calculateNextDate advances the date by the frequency`(
        frequency: Frequency,
        from: LocalDate,
        expected: LocalDate,
    ) {
        val order = standingOrder(frequency = frequency)

        assertThat(order.calculateNextDate(from)).isEqualTo(expected)
    }

    private companion object {
        val FIXED_NOW: Instant = Instant.parse("2026-01-15T10:15:30Z")

        @JvmStatic
        fun frequencyNextDateCases(): Stream<Arguments> = Stream.of(
            // ONCE never advances — the returned date is unused (recordExecution completes it).
            Arguments.of(Frequency.ONCE, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1)),
            Arguments.of(Frequency.DAILY, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2)),
            Arguments.of(Frequency.WEEKLY, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 8)),
            Arguments.of(Frequency.BIWEEKLY, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 15)),
            Arguments.of(Frequency.MONTHLY, LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28)),
            Arguments.of(Frequency.QUARTERLY, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1)),
            Arguments.of(Frequency.ANNUALLY, LocalDate.of(2026, 3, 15), LocalDate.of(2027, 3, 15)),
        )
    }
}
