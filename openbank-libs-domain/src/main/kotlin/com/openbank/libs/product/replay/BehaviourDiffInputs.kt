// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.product.replay

import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The two request knobs of a behavioural diff, resolved ONCE for `obp diff --behavioural` and the
 * catalog's approval backtest (ADR-0352 phase 5b), so the same portfolio, dates and threshold give the
 * same diff on both surfaces — and both refuse the same inputs. Errors are [IllegalArgumentException]s;
 * the CLI maps them to exit 64, the catalog to 400.
 */
object BehaviourDiffInputs {

    /**
     * The calendar: [from] defaults to the portfolio's earliest `openedOn`, [to] to its last fixture day.
     * A resolved range that is empty or longer than [ReplayLimits.MAX_DAYS] is refused, naming the
     * resolved dates and which of them were defaults.
     */
    fun range(parsed: ReplayPortfolioJson.Parsed, from: LocalDate?, to: LocalDate?): ReplayRange {
        val start = from ?: parsed.firstDay
        val end = to ?: parsed.lastDay
        require(!end.isBefore(start)) {
            "resolved range $start..$end is empty" +
                (if (from == null) " (from defaults to the earliest openedOn, ${parsed.firstDay})" else "") +
                (if (to == null) " (to defaults to the last portfolio day, ${parsed.lastDay})" else "")
        }
        val days = ChronoUnit.DAYS.between(start, end) + 1
        require(days <= ReplayLimits.MAX_DAYS) { "range of $days days exceeds ${ReplayLimits.MAX_DAYS}" }
        return ReplayRange(start, end, parsed.zone)
    }

    /**
     * The materiality threshold: absent means the [DiffOptions] default; present, it is a non-negative
     * amount in [ReplayPortfolioJson.amount]'s bounded plain-decimal grammar — `1E+999999999` is refused
     * before it is ever rendered or compared.
     */
    fun options(threshold: Any?): DiffOptions {
        if (threshold == null) return DiffOptions()
        val value = ReplayPortfolioJson.amount(threshold, "threshold")
        require(value.signum() >= 0) { "threshold must not be negative" }
        return DiffOptions(value)
    }

    /** Re-exported so a caller needs one import for the bound; see [ReplayPortfolioJson.amount]. */
    fun amount(text: Any?, at: String): BigDecimal = ReplayPortfolioJson.amount(text, at)
}
