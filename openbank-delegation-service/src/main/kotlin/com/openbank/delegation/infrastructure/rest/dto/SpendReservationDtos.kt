// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest.dto

import com.openbank.delegation.domain.model.SpendDecision
import com.openbank.delegation.domain.model.SpendRefusalReason
import com.openbank.delegation.domain.model.SpendReservation
import com.openbank.delegation.domain.model.SpendReservationOperationType
import com.openbank.delegation.domain.model.SpendReservationState
import com.openbank.libs.domain.money.Money
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/** Existing callers default to a rail-neutral reservation. */
data class ReserveSpendRequest(
    val amount: BigDecimal,
    val currency: String,
    val idempotencyKey: String,
    val operationType: SpendReservationOperationType = SpendReservationOperationType.UNSPECIFIED,
) {
    fun toMoney(): Money = Money.of(amount, currency)
}

data class SpendReservationResponse(
    val reservationId: UUID,
    val delegationId: UUID,
    val amount: MoneyDto,
    val operationType: SpendReservationOperationType,
    val state: SpendReservationState,
    val createdAt: OffsetDateTime,
    val settledAt: OffsetDateTime?,
) {
    companion object {
        fun from(r: SpendReservation): SpendReservationResponse = SpendReservationResponse(
            reservationId = r.id,
            delegationId = r.grantId,
            amount = MoneyDto.from(r.amount),
            operationType = r.operationType,
            state = r.state,
            createdAt = r.createdAt,
            settledAt = r.settledAt,
        )
    }
}

/**
 * The 409 body. It names WHICH ceiling refused and HOW MUCH is left under it, because a client that
 * only learns "no" can only guess — and a delegate guessing at their own limit is the experience
 * ADR-0249 exists to replace. [remaining] is clamped at zero; see [SpendDecision.Refused].
 */
data class SpendRefusalResponse(
    val status: Int,
    val reason: SpendRefusalReason,
    val ceiling: MoneyDto?,
    val alreadyCounted: MoneyDto?,
    val remaining: MoneyDto?,
    val error: String,
) {
    companion object {
        fun from(status: Int, decision: SpendDecision.Refused): SpendRefusalResponse = SpendRefusalResponse(
            status = status,
            reason = decision.reason,
            ceiling = decision.ceiling?.let { MoneyDto.from(it) },
            alreadyCounted = decision.alreadyCounted?.let { MoneyDto.from(it) },
            remaining = decision.remaining?.let { MoneyDto.from(it) },
            error = message(decision),
        )

        private fun message(decision: SpendDecision.Refused): String = when (decision.reason) {
            SpendRefusalReason.PER_TX -> "amount exceeds the per-transaction ceiling ${decision.ceiling}"
            SpendRefusalReason.DAILY ->
                "amount would exceed the daily ceiling ${decision.ceiling}; " +
                    "${decision.remaining} remains today"

            SpendRefusalReason.MONTHLY ->
                "amount would exceed the monthly ceiling ${decision.ceiling}; " +
                    "${decision.remaining} remains this month"

            SpendRefusalReason.GRANT_NOT_ACTIVE -> "the delegation is not active"
            SpendRefusalReason.NO_SPEND_CAPABILITY -> "the delegation carries no money-moving capability"
            SpendRefusalReason.CURRENCY_MISMATCH ->
                "the amount is not denominated in the ceiling's currency (${decision.ceiling?.currency?.code})"
        }
    }
}
