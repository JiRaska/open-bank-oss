// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.domain.model

/**
 * The human contract number of a [Loan] (#11107): `UV-<origination year>-<sequence>`, the sequence
 * zero-padded to at least six digits and never truncated (`UV-2026-000123`, `UV-2026-1000000`).
 *
 * The loan's UUID stays its technical identity; this is the reference people read. It is assigned
 * once, at creation, by the database (`next_loan_contract_number`, V24) — unique per year under
 * concurrent creation and gap-tolerant — and never changes afterwards (the column is immutable at
 * the database). This object only states the format, so the domain, the migration's CHECK and
 * every test agree on one definition.
 */
object LoanContractNumber {
    const val PREFIX = "UV"
    private const val MIN_DIGITS = 6
    private const val MIN_YEAR = 1000
    private const val MAX_YEAR = 9999
    private val PATTERN = Regex("""^UV-(\d{4})-(\d{6,})$""")

    fun format(year: Int, sequence: Long): String {
        require(year in MIN_YEAR..MAX_YEAR) { "year must have four digits, was $year" }
        require(sequence > 0) { "sequence must be positive, was $sequence" }
        return "$PREFIX-$year-${sequence.toString().padStart(MIN_DIGITS, '0')}"
    }

    fun isValid(value: String): Boolean = PATTERN.matches(value)

    /** The origination year a valid number carries, or null for anything not in the format. */
    fun yearOf(value: String): Int? = PATTERN.matchEntire(value)?.groupValues?.get(1)?.toInt()
}
