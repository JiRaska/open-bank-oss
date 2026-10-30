// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.products.cli

import com.openbank.libs.finance.deposit.FinanceProductArithmetic
import com.openbank.libs.product.ir.IrJson
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.product.ir.ProductRevisionIr
import com.openbank.libs.product.replay.BehaviourDiff
import com.openbank.libs.product.replay.DiffOptions
import com.openbank.libs.product.replay.ProductBehaviourDiff
import com.openbank.libs.product.replay.ReplayLimits
import com.openbank.libs.product.replay.ReplayPortfolioJson
import com.openbank.libs.product.replay.ReplayRange
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * `obp diff --behavioural` (ADR-0352 §11.1, §11.4): the repricing backtest. It replays a portfolio
 * under two revisions with the shared [ProductBehaviourDiff] (openbank-libs-domain) and prints that
 * object's canonical JSON (`product-ir/behaviour-diff.v1.schema.json`) unchanged, so the CLI and the
 * Studio approval panel show the same document for the same inputs.
 *
 * Portfolio: read by [ReplayPortfolioJson] (openbank-libs-domain), the same reader the catalog's
 * approval-panel backtest uses, so a portfolio file means the same accounts on both surfaces. It takes
 * the golden scenario's state and `steps` grammar (`portfolios/<name>.json`) or plain dated `postings`; a step
 * with a `posting` is a customer movement, and scheduled steps are skipped because the replay runs the
 * schedules each revision registers in `onOpen`, which is the point of comparing two revisions.
 *
 * The range defaults to the earliest `openedOn` through the last fixture day; `from`/`to` override it,
 * and a resolved range that is empty or longer than [ReplayLimits.MAX_DAYS] is a usage error.
 */
object BehaviouralDiff {

    private val engine = ProductBehaviourDiff(ProductInterpreter(FinanceProductArithmetic))

    fun run(
        a: ProductRevisionIr,
        b: ProductRevisionIr,
        portfolioJson: String,
        from: LocalDate? = null,
        to: LocalDate? = null,
        options: DiffOptions = DiffOptions(),
    ): BehaviourDiff {
        val parsed = try {
            ReplayPortfolioJson.parse(IrJson.parse(portfolioJson))
        } catch (e: IllegalArgumentException) {
            throw CliInputException("portfolio: ${e.message}", e)
        }
        return engine.diff(a, b, parsed.portfolio, range(parsed, from, to), options)
    }

    /**
     * The calendar after defaults are applied. Every way it can be empty or too long is a usage error
     * (exit 64) naming the resolved dates — never an internal error from deep inside the replay.
     */
    private fun range(parsed: ReplayPortfolioJson.Parsed, from: LocalDate?, to: LocalDate?): ReplayRange {
        val start = from ?: parsed.firstDay
        val end = to ?: parsed.lastDay
        if (end.isBefore(start)) {
            throw CliUsageException(
                "resolved range $start..$end is empty" +
                    (if (from == null) " (--from defaults to the earliest openedOn, ${parsed.firstDay})" else "") +
                    (if (to == null) " (--to defaults to the last fixture day, ${parsed.lastDay})" else ""),
            )
        }
        val days = ChronoUnit.DAYS.between(start, end) + 1
        val limit = ReplayLimits.MAX_DAYS
        if (days > limit) throw CliUsageException("range of $days days exceeds $limit")
        return ReplayRange(start, end, parsed.zone)
    }
}
