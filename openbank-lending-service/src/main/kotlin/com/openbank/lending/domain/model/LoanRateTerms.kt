// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.lending.domain.model

import java.math.BigDecimal
import java.time.LocalDate

enum class LoanRateType { FIXED, FLOATING }

/** Closed on purpose: the risk engine needs a curve for each (ADR-0314 D5). Same set interest-service uses. */
enum class LoanRateIndex { CZEONIA, PRIBOR_1M, PRIBOR_3M, PRIBOR_6M, ESTR, EURIBOR_3M }

/**
 * How a loan's rate behaves over its life (ADR-0314 D5). FIXED carries nothing else; FLOATING
 * carries the index it follows, the spread over it, how often it resets and the next reset date.
 * The V18 CHECK constraint enforces the same shape in the database.
 */
data class LoanRateTerms(
    val rateType: LoanRateType = LoanRateType.FIXED,
    val rateIndex: LoanRateIndex? = null,
    val spread: BigDecimal? = null,
    val resetFrequencyMonths: Int? = null,
    val nextResetDate: LocalDate? = null,
) {
    /** Throws [IllegalArgumentException] (a 400 at the edge) for any shape V18 would reject at flush. */
    fun validate(firstDueDate: LocalDate) {
        when (rateType) {
            LoanRateType.FIXED -> require(
                rateIndex == null && spread == null && resetFrequencyMonths == null && nextResetDate == null,
            ) { "A FIXED rate carries no index, spread or reset terms" }
            LoanRateType.FLOATING -> {
                require(rateIndex != null && spread != null && nextResetDate != null && resetFrequencyMonths != null) {
                    "A FLOATING rate needs rateIndex, spread, resetFrequencyMonths and nextResetDate"
                }
                require(resetFrequencyMonths in RESET_FREQUENCIES) {
                    "resetFrequencyMonths must be one of $RESET_FREQUENCIES"
                }
                require(!nextResetDate.isBefore(firstDueDate)) { "nextResetDate cannot precede the first due date" }
            }
        }
    }

    /**
     * The terms as JSON members with a trailing comma, for the hand-built event payloads. Every
     * value is an enum name, a plain decimal, a number or an ISO date, so none needs escaping.
     */
    fun jsonFields(): String {
        fun q(v: Any?) = if (v == null) "null" else "\"$v\""
        return "\"rateType\":${q(rateType)},\"rateIndex\":${q(rateIndex)}," +
            "\"spread\":${q(spread?.toPlainString())},\"resetFrequencyMonths\":${resetFrequencyMonths ?: "null"}," +
            "\"nextResetDate\":${q(nextResetDate)},"
    }

    companion object {
        val FIXED = LoanRateTerms()
        val RESET_FREQUENCIES = setOf(1, 3, 6, 12)
    }
}
