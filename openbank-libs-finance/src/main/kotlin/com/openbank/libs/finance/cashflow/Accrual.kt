// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.cashflow

import com.openbank.libs.domain.calendar.YearFraction
import com.openbank.libs.finance.leg.LegSpec
import java.math.BigDecimal

/**
 * The single interest formula of the engine: `notional × rate × yearFraction`, computed exactly
 * (the year fraction is a rational) and rounded ONCE under the leg's rounding policy at the leg
 * currency's scale. Both the projector and the annuity solver call it, so an annuity's principal
 * split and its projected interest can never disagree by a rounding.
 */
object Accrual {
    fun interest(leg: LegSpec, notional: BigDecimal, rate: BigDecimal, yearFraction: YearFraction): BigDecimal =
        yearFraction.applyTo(notional.multiply(rate), leg.rounding.scaleFor(leg.currency), leg.rounding.mode)
}
