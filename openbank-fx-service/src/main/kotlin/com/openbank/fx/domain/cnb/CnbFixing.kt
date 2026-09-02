// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.domain.cnb

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * One line of the ČNB central-bank exchange-rate fixing (kurz devizového trhu). The fixing
 * quotes [rate] CZK per [amount] units of [code] (e.g. JPY is quoted per 100). [ratePerUnit]
 * normalises that to CZK per single unit, at the 8-decimal scale of the `fx_rates` table.
 */
data class CnbFixingRate(val code: String, val amount: Int, val rate: BigDecimal) {
    init {
        require(amount > 0) { "ČNB fixing amount must be positive for $code, was $amount" }
        require(rate.signum() > 0) { "ČNB fixing rate must be positive for $code, was $rate" }
    }

    val ratePerUnit: BigDecimal
        get() = rate.divide(BigDecimal(amount), 8, RoundingMode.HALF_UP)
}

/**
 * A parsed ČNB daily fixing: the [date] it is valid for, the publication [sequence] (the
 * `#NNN` ordinal), and one [CnbFixingRate] per quoted currency.
 */
data class CnbFixing(val date: LocalDate, val sequence: Int?, val rates: List<CnbFixingRate>) {
    fun rateFor(code: String): BigDecimal? = rates.firstOrNull { it.code == code }?.ratePerUnit
}
