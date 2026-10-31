// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.domain.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Every number in the unit register is a [BigDecimal] with an EXPLICIT scale and rounding mode.
 *
 * - money: 2 decimals, HALF_EVEN (banker's rounding, no systematic drift across many rows);
 * - NAV per unit: 6 decimals, HALF_EVEN;
 * - units issued: 6 decimals, rounded DOWN — the fund never issues a fraction of a unit it was
 *   not paid for, so rounding can only ever favour the remaining participants, never dilute them;
 * - units cancelled for a fee: rounded UP, for the same reason in the other direction.
 */
object Precision {
    const val MONEY_SCALE = 2
    const val NAV_SCALE = 6
    const val UNIT_SCALE = 6
    const val WEIGHT_SCALE = 6

    /** Actual/365 day count for the management-fee accrual. */
    const val DAY_COUNT_BASIS = 365

    /** Intermediate scale for divisions before the final, explicit rounding. */
    const val INTERMEDIATE_SCALE = 16

    val MONEY_ROUNDING: RoundingMode = RoundingMode.HALF_EVEN
    val NAV_ROUNDING: RoundingMode = RoundingMode.HALF_EVEN

    fun money(value: BigDecimal): BigDecimal = value.setScale(MONEY_SCALE, MONEY_ROUNDING)

    fun nav(value: BigDecimal): BigDecimal = value.setScale(NAV_SCALE, NAV_ROUNDING)

    fun unitsIssued(amount: BigDecimal, navPerUnit: BigDecimal): BigDecimal =
        amount.divide(navPerUnit, UNIT_SCALE, RoundingMode.DOWN)

    fun unitsCancelledForFee(amount: BigDecimal, navPerUnit: BigDecimal): BigDecimal =
        amount.divide(navPerUnit, UNIT_SCALE, RoundingMode.UP)

    fun proceeds(units: BigDecimal, navPerUnit: BigDecimal): BigDecimal = money(units.multiply(navPerUnit))

    fun units(value: BigDecimal): BigDecimal = value.setScale(UNIT_SCALE, RoundingMode.DOWN)
}
