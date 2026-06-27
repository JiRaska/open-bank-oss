// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.standingorder.domain.model

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class StandingOrderStatus { ACTIVE, PAUSED, CANCELLED, COMPLETED, FAILED }
enum class Frequency { DAILY, WEEKLY, BIWEEKLY, MONTHLY, QUARTERLY, ANNUALLY }
enum class PaymentType { SEPA_CREDIT, DOMESTIC, INTERNAL }

data class StandingOrder(
    val id: UUID,
    val idempotencyKey: String,
    val partyId: UUID,
    val debitAccountId: UUID,
    val creditorIban: String,
    val creditorName: String,
    val creditorBic: String?,
    val amountMinorUnits: Long,
    val currency: String,
    val frequency: Frequency,
    val paymentType: PaymentType,
    val remittanceInfo: String?,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val nextExecutionDate: LocalDate,
    val lastExecutionDate: LocalDate?,
    val executionCount: Int,
    val failureCount: Int,
    val status: StandingOrderStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun pause(now: Instant) = also {
        require(status == StandingOrderStatus.ACTIVE) { "Only ACTIVE orders can be paused" }
    }.copy(status = StandingOrderStatus.PAUSED, updatedAt = now)

    fun resume(now: Instant) = also {
        require(status == StandingOrderStatus.PAUSED) { "Only PAUSED orders can be resumed" }
    }.copy(status = StandingOrderStatus.ACTIVE, updatedAt = now)

    fun cancel(now: Instant) = also {
        require(status !in setOf(StandingOrderStatus.CANCELLED, StandingOrderStatus.COMPLETED)) {
            "Cannot cancel order in status $status"
        }
    }.copy(status = StandingOrderStatus.CANCELLED, updatedAt = now)

    fun recordExecution(nextDate: LocalDate, now: Instant) = copy(
        lastExecutionDate = nextExecutionDate,
        nextExecutionDate = nextDate,
        executionCount = executionCount + 1,
        updatedAt = now,
        status = if (endDate != null && nextDate.isAfter(endDate)) StandingOrderStatus.COMPLETED else status,
    )

    fun calculateNextDate(from: LocalDate): LocalDate = when (frequency) {
        Frequency.DAILY -> from.plusDays(1)
        Frequency.WEEKLY -> from.plusWeeks(1)
        Frequency.BIWEEKLY -> from.plusWeeks(2)
        Frequency.MONTHLY -> from.plusMonths(1)
        Frequency.QUARTERLY -> from.plusMonths(3)
        Frequency.ANNUALLY -> from.plusYears(1)
    }
}
