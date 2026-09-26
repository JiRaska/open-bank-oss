// SPDX-License-Identifier: Apache-2.0
package com.openbank.settlement.domain.model

import java.math.BigDecimal

/** The approved amount must fit NUMERIC(19,4) without rounding or overflow. */
fun validateSettlementAmount(amount: BigDecimal) {
    require(amount > BigDecimal.ZERO) { "amount must be positive" }
    val normalized = amount.stripTrailingZeros()
    require(normalized.scale() <= MAX_FRACTION_DIGITS) { "amount must have at most 4 fractional digits" }
    require(normalized.precision().toLong() - normalized.scale().toLong() <= MAX_INTEGER_DIGITS) {
        "amount must have at most 15 integer digits"
    }
}

private const val MAX_FRACTION_DIGITS = 4
private const val MAX_INTEGER_DIGITS = 15
