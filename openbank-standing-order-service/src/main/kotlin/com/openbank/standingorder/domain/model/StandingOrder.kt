// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.domain.model

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class StandingOrderStatus { ACTIVE, PAUSED, CANCELLED, COMPLETED, FAILED }
// ONCE = a one-off future-dated payment: it fires exactly once on its startDate and then
// COMPLETES (never recurs). Modelled as a frequency so it reuses the whole scheduler engine
// (due-query + execute + record) rather than a separate scheduled-payment service.
enum class Frequency { ONCE, DAILY, WEEKLY, BIWEEKLY, MONTHLY, QUARTERLY, ANNUALLY }
enum class PaymentType { SEPA_CREDIT, DOMESTIC, INTERNAL }

data class StandingOrder(
    val id: UUID,
    val idempotencyKey: String,
    val partyId: UUID,
    val debitAccountId: UUID,
    // Debtor IBAN + name captured at creation (#889): the SEPA rail's createPayment needs both,
    // and account-service's by-id response exposes neither. Nullable for backward compatibility
    // with orders created before this field existed — such orders can be created but not executed
    // (the SEPA rail records a failure with a clear "missing debtor details" reason).
    val debtorIban: String?,
    val debtorName: String?,
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
        // A ONCE order is spent after its single execution; a recurring order completes only once
        // it runs past its (optional) endDate.
        status = if (frequency == Frequency.ONCE || (endDate != null && nextDate.isAfter(endDate))) {
            StandingOrderStatus.COMPLETED
        } else {
            status
        },
    )

    fun calculateNextDate(from: LocalDate): LocalDate = when (frequency) {
        // ONCE never recurs (recordExecution completes it); the returned date is unused, so keep it
        // unchanged rather than inventing a future occurrence.
        Frequency.ONCE -> from
        Frequency.DAILY -> from.plusDays(1)
        Frequency.WEEKLY -> from.plusWeeks(1)
        Frequency.BIWEEKLY -> from.plusWeeks(2)
        Frequency.MONTHLY -> from.plusMonths(1)
        Frequency.QUARTERLY -> from.plusMonths(3)
        Frequency.ANNUALLY -> from.plusYears(1)
    }

    fun recordFailure(now: Instant): StandingOrder {
        require(status == StandingOrderStatus.ACTIVE) { "Cannot record failure for $status order" }
        val newFailureCount = failureCount + 1
        return copy(
            failureCount = newFailureCount,
            updatedAt = now,
            status = if (newFailureCount >= MAX_CONSECUTIVE_FAILURES) StandingOrderStatus.FAILED else status,
        )
    }

    fun confirmExecution(now: Instant): StandingOrder {
        require(status == StandingOrderStatus.ACTIVE) { "Cannot confirm execution for $status order" }
        return copy(failureCount = 0, updatedAt = now)
    }

    companion object {
        const val MAX_CONSECUTIVE_FAILURES = 3
    }
}
