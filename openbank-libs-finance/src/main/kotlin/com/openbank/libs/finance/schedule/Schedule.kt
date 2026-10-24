// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.schedule

import com.openbank.libs.domain.calendar.YearFraction
import com.openbank.libs.finance.leg.LegSpec
import java.math.BigDecimal
import java.time.LocalDate

/**
 * One accrual period of a leg.
 *
 * @property unadjusted the period as generated from the anchor, before any business-day roll.
 * @property accrual the period interest actually accrues over: [unadjusted] rolled when the leg
 *   adjusts its accrual periods, identical to it otherwise.
 * @property paymentDate when this period's flows are paid (rolled and lagged per the leg).
 * @property fixingDate when a floating period's rate is observed; `null` for a non-floating leg.
 * @property notional the outstanding notional over the period (before this period's [principal]).
 * @property principal the principal repaid at the end of this period (zero for a bullet's
 *   non-final periods, and for a leg without final exchange).
 * @property yearFraction the leg's day count over [accrual], exact.
 */
data class SchedulePeriod(
    val index: Int,
    val unadjusted: DateRange,
    val accrual: DateRange,
    val paymentDate: LocalDate,
    val fixingDate: LocalDate?,
    val notional: BigDecimal,
    val principal: BigDecimal,
    val yearFraction: YearFraction,
) {
    val unadjustedStart: LocalDate get() = unadjusted.start
    val unadjustedEnd: LocalDate get() = unadjusted.end
    val accrualStart: LocalDate get() = accrual.start
    val accrualEnd: LocalDate get() = accrual.end
}

/**
 * A generated schedule: the ordered, contiguous accrual periods of [leg].
 *
 * @property initialExchangeDate the (rolled) start date on which an initial notional exchange
 *   settles; meaningful only when the leg exchanges notional initially.
 */
data class Schedule(val leg: LegSpec, val periods: List<SchedulePeriod>, val initialExchangeDate: LocalDate)
