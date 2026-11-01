// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.schedule

import java.time.LocalDate

/**
 * How often a schedule rolls. [Zero] is a single period from start to end (bullet / "term"); the
 * other variants step by a whole number of months, weeks or days. Steps are always taken from the
 * schedule ANCHOR (`anchor + k × step`), never chained period to period, so a 31st-of-month anchor
 * does not drift to the 28th after February.
 */
sealed interface Frequency {
    /** The `k`-th date after [anchor] (`k < 0` steps backward). [Zero] has no step. */
    fun step(anchor: LocalDate, k: Int): LocalDate

    /** Canonical token used in schedule hashes, e.g. `3M`. */
    val token: String

    /** Single period from start to end. */
    data object Zero : Frequency {
        override fun step(anchor: LocalDate, k: Int): LocalDate =
            throw UnsupportedOperationException("a ZERO frequency has no step")

        override val token: String = "0T"
    }

    data class Months(val months: Int) : Frequency {
        init {
            require(months > 0) { "months must be positive: $months" }
        }

        override fun step(anchor: LocalDate, k: Int): LocalDate = anchor.plusMonths(months.toLong() * k)

        override val token: String get() = "${months}M"
    }

    data class Weeks(val weeks: Int) : Frequency {
        init {
            require(weeks > 0) { "weeks must be positive: $weeks" }
        }

        override fun step(anchor: LocalDate, k: Int): LocalDate = anchor.plusWeeks(weeks.toLong() * k)

        override val token: String get() = "${weeks}W"
    }

    data class Days(val days: Int) : Frequency {
        init {
            require(days > 0) { "days must be positive: $days" }
        }

        override fun step(anchor: LocalDate, k: Int): LocalDate = anchor.plusDays(days.toLong() * k)

        override val token: String get() = "${days}D"
    }

    companion object {
        val DAILY: Frequency = Days(1)
        val WEEKLY: Frequency = Weeks(1)
        val MONTHLY: Frequency = Months(1)
        val QUARTERLY: Frequency = Months(3)
        val SEMI_ANNUAL: Frequency = Months(6)
        val ANNUAL: Frequency = Months(12)

        /**
         * The named frequencies and their codes, the vocabulary deal and leg payloads spell a
         * frequency in. ONCE is [Zero] (one period from start to end).
         */
        val NAMED: Map<String, Frequency> = linkedMapOf(
            "ONCE" to Zero,
            "DAILY" to DAILY,
            "WEEKLY" to WEEKLY,
            "MONTHLY" to MONTHLY,
            "QUARTERLY" to QUARTERLY,
            "SEMI_ANNUAL" to SEMI_ANNUAL,
            "ANNUAL" to ANNUAL,
        )

        /** The frequency a [NAMED] code names, or `null` for any other text. */
        fun named(code: String): Frequency? = NAMED[code]

        /** The [NAMED] code of [frequency], or `null` when it has none (e.g. `Months(2)`). */
        fun codeOf(frequency: Frequency): String? = NAMED.entries.firstOrNull { it.value == frequency }?.key
    }
}

/** The direction dates are generated in: from the start date forward, or from the end date backward. */
enum class RollDirection { FROM_START, FROM_END }

/**
 * Where an irregular period goes and whether it is short or long. An INITIAL stub implies
 * generation [RollDirection.FROM_END] (regular periods are anchored on the end date); a FINAL stub
 * implies [RollDirection.FROM_START]. A LONG stub merges the would-be short stub into its
 * neighbouring regular period. When the period divides evenly there is no stub either way.
 */
enum class StubType(val direction: RollDirection) {
    SHORT_INITIAL(RollDirection.FROM_END),
    LONG_INITIAL(RollDirection.FROM_END),
    SHORT_FINAL(RollDirection.FROM_START),
    LONG_FINAL(RollDirection.FROM_START),
    ;

    val isLong: Boolean get() = this == LONG_INITIAL || this == LONG_FINAL

    companion object {
        /** The wire spelling of "this leg names no stub" (a `null` stub in the domain). */
        const val NO_STUB: String = "NONE"

        /**
         * The single rule for a leg that names no stub: an explicit [stub] always wins; otherwise the
         * deal's roll [direction] places it — FROM_START generates forward, so the irregular period
         * falls at the end ([SHORT_FINAL]); FROM_END anchors on the end date, so it falls at the
         * start ([SHORT_INITIAL]). Every producer and consumer of deal legs must use this function so
         * they derive the same schedule from the same terms.
         */
        fun resolve(stub: StubType?, direction: RollDirection): StubType =
            stub ?: if (direction == RollDirection.FROM_START) SHORT_FINAL else SHORT_INITIAL
    }
}

/** A half-open date interval `[start, end)`. */
data class DateRange(val start: LocalDate, val end: LocalDate) {
    init {
        require(!end.isBefore(start)) { "range end $end precedes start $start" }
    }
}
