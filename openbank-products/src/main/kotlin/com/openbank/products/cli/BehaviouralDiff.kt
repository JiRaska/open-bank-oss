// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.products.cli

import com.openbank.libs.audit.CanonicalJson
import com.openbank.libs.finance.deposit.FinanceProductArithmetic
import com.openbank.libs.product.ir.IrJson
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.product.ir.ProductRevisionIr
import com.openbank.libs.product.replay.BehaviourDiff
import com.openbank.libs.product.replay.DiffOptions
import com.openbank.libs.product.replay.ProductBehaviourDiff
import com.openbank.libs.product.replay.ReplayAccount
import com.openbank.libs.product.replay.ReplayPortfolio
import com.openbank.libs.product.replay.ReplayPosting
import com.openbank.libs.product.replay.ReplayRange
import com.openbank.libs.testing.product.GoldenScenario
import java.time.LocalDate
import java.time.ZoneId

/**
 * `obp diff --behavioural` (ADR-0352 §11.1, §11.4): the repricing backtest. It replays a portfolio
 * under two revisions with the shared [ProductBehaviourDiff] (openbank-libs-domain) and prints that
 * object's canonical JSON (`product-ir/behaviour-diff.v1.schema.json`) unchanged, so the CLI and the
 * Studio approval panel show the same document for the same inputs.
 *
 * Portfolio fixture: `{"zone": "UTC", "accounts": [{"id", "state": <golden state>, "steps": [<golden
 * step>]}]}`, the golden scenario's own state and step grammar. A step that carries a `posting` becomes
 * a customer movement on its calendar day in `zone`. Scheduled steps are not needed: the replay runs
 * the schedules each revision registers in `onOpen`, which is the point of comparing two revisions.
 *
 * The range defaults to the earliest `openedOn` through the latest step day; `from`/`to` override it.
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
        val (portfolio, zone, defaultRange) = portfolio(a, portfolioJson)
        val range = ReplayRange(from ?: defaultRange.first, to ?: defaultRange.second, zone)
        return engine.diff(a, b, portfolio, range, options)
    }

    private fun portfolio(
        rev: ProductRevisionIr,
        json: String,
    ): Triple<ReplayPortfolio, ZoneId, Pair<LocalDate, LocalDate>> {
        @Suppress("UNCHECKED_CAST")
        val root = IrJson.parse(json) as? Map<String, Any?> ?: emptyMap()
        val zoneId = root["zone"] as? String ?: "UTC"

        @Suppress("UNCHECKED_CAST")
        val accounts = (root["accounts"] as? List<Map<String, Any?>> ?: emptyList()).map { account ->
            val id = account["id"] as? String ?: throw CliInputException("portfolio account without an id")
            // The golden parser owns the state and step grammar; reuse it rather than re-reading either.
            val scenario = GoldenScenario.parse(
                CanonicalJson.write(
                    mapOf(
                        "name" to id,
                        "revision" to Obp.revisionTree(rev),
                        "clock" to mapOf("zone" to zoneId),
                        "state" to account["state"],
                        "steps" to (account["steps"] as? List<Any?> ?: emptyList()),
                    ),
                ),
            )
            val postings = scenario.steps.mapNotNull { step ->
                step.event.posting?.let { ReplayPosting(step.at.atZone(scenario.zone).toLocalDate(), it) }
            }
            scenario to ReplayAccount(id, scenario.initialState, postings)
        }
        if (accounts.isEmpty()) throw CliInputException("portfolio must be a JSON object with at least one account")
        val zone = ZoneId.of(zoneId)
        val first = accounts.minOf { it.second.initialState.openedOn }
        val last = accounts.flatMap { (scenario, _) -> scenario.steps.map { it.at.atZone(zone).toLocalDate() } }
            .maxOrNull() ?: first
        return Triple(ReplayPortfolio(accounts.map { it.second }), zone, first to maxOf(first, last))
    }
}
