// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.interest.infrastructure.rest.dto

import com.openbank.interest.domain.model.AccrualStatus
import com.openbank.interest.domain.model.DayCount
import com.openbank.interest.domain.model.InterestAccrual
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class InterestAccrualResponse(
    val id: UUID,
    val accountId: UUID,
    val accrualDate: LocalDate,
    val accruedAmount: BigDecimal,
    val currency: String,
    val rate: BigDecimal,
    val dayCount: DayCount,
    val status: AccrualStatus
)

fun InterestAccrual.toResponse(dayCount: DayCount) = InterestAccrualResponse(
    id = id,
    accountId = accountId,
    accrualDate = accrualDate,
    accruedAmount = accruedAmount,
    currency = currency,
    rate = dailyRate,
    dayCount = dayCount,
    status = status
)
