// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.products.cli

import com.openbank.libs.finance.deposit.FinanceProductArithmetic
import com.openbank.libs.product.ir.IrJson
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.product.ir.ProductRevisionIr
import com.openbank.libs.product.replay.BehaviourDiff
import com.openbank.libs.product.replay.BehaviourDiffInputs
import com.openbank.libs.product.replay.ProductBehaviourDiff
import com.openbank.libs.product.replay.ReplayPortfolioJson
import java.time.LocalDate

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
 * through [BehaviourDiffInputs], the resolution the catalog backtest also runs; a bad one is a usage error.
 */
object BehaviouralDiff {

    private val engine = ProductBehaviourDiff(ProductInterpreter(FinanceProductArithmetic))

    fun run(
        a: ProductRevisionIr,
        b: ProductRevisionIr,
        portfolioJson: String,
        from: LocalDate? = null,
        to: LocalDate? = null,
        threshold: String? = null,
    ): BehaviourDiff {
        val parsed = try {
            ReplayPortfolioJson.parse(IrJson.parse(portfolioJson))
        } catch (e: IllegalArgumentException) {
            throw CliInputException("portfolio: ${e.message}", e)
        }
        // The same resolution the catalog backtest runs; a bad window or threshold is a usage error.
        val (range, options) = try {
            BehaviourDiffInputs.range(parsed, from, to) to BehaviourDiffInputs.options(threshold)
        } catch (
            // A usage error is reported as its message (exit 64); there is no stack worth keeping.
            @Suppress("SwallowedException") e: IllegalArgumentException,
        ) {
            throw CliUsageException(e.message ?: "invalid diff window or threshold")
        }
        return engine.diff(a, b, parsed.portfolio, range, options)
    }
}
