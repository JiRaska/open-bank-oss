// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.products.cli

import com.openbank.libs.audit.CanonicalJson
import com.openbank.libs.product.ir.AccountingClass
import com.openbank.libs.product.ir.Instruction
import com.openbank.libs.product.ir.IrJson
import com.openbank.libs.product.ir.PostingDirection
import com.openbank.libs.product.ir.ProductIrCanonical
import com.openbank.libs.product.ir.ProductRevisionIr
import com.openbank.libs.testing.product.GoldenScenario
import com.openbank.libs.testing.product.GoldenScenarioRunner
import java.math.BigDecimal

/**
 * `obp diff --behavioural` (ADR-0352 §11.1, §11.4): replays a portfolio fixture under two revisions
 * through the one pure interpreter and reports, per `ruleId`, how the postings differ — the
 * instructions, not the JSON paths. This is the backtest the repricing path attaches to a Studio
 * approval, so nobody approves a reprice without seeing which accounts move by how much.
 *
 * Portfolio fixture: `{"zone": "UTC", "accounts": [{"id", "state": <golden state>, "steps": [<golden
 * step without "expected">]}]}` — the golden scenario's own state and step grammar, so one fixture
 * format serves tests and backtests. Each account is replayed as a golden scenario with no
 * expectations; the state transition between steps is the shared [com.openbank.libs.product.ir.ProductStateTransition].
 */
object BehaviouralDiff {

    fun run(a: ProductRevisionIr, b: ProductRevisionIr, portfolioJson: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val portfolio = IrJson.parse(portfolioJson) as Map<String, Any?>
        val zone = portfolio["zone"] as? String ?: "UTC"

        @Suppress("UNCHECKED_CAST")
        val accounts = portfolio["accounts"] as? List<Map<String, Any?>> ?: emptyList()
        val runner = GoldenScenarioRunner()
        val perRule = sortedMapOf<String, MutableMap<String, Any?>>()
        for (account in accounts) {
            val id = account["id"] as String
            val under = { rev: ProductRevisionIr -> replay(runner, rev, id, zone, account) }
            val outA = under(a)
            val outB = under(b)
            for (ruleId in (outA.keys + outB.keys).toSortedSet()) {
                val ra = outA[ruleId] ?: RuleTotals()
                val rb = outB[ruleId] ?: RuleTotals()
                if (ra == rb) continue
                val entry = perRule.getOrPut(ruleId) {
                    linkedMapOf("ruleId" to ruleId, "accounts" to ArrayList<Any?>())
                }
                @Suppress("UNCHECKED_CAST")
                (entry["accounts"] as ArrayList<Any?>) += mapOf(
                    "account" to id,
                    "a" to ra.tree(),
                    "b" to rb.tree(),
                    "customerDepositDelta" to
                        ProductIrCanonical.instructionAmount(rb.customerDeposit - ra.customerDeposit),
                )
            }
        }
        return mapOf(
            "revisionA" to mapOf("productCode" to a.productCode, "revisionHash" to a.revisionHash),
            "revisionB" to mapOf("productCode" to b.productCode, "revisionHash" to b.revisionHash),
            "accounts" to accounts.size,
            "changedRules" to perRule.size,
            "rules" to perRule.values.toList(),
        )
    }

    /** Per-rule totals over one account's replay: signed customer-deposit movement, posting and reject counts. */
    private data class RuleTotals(
        var customerDeposit: BigDecimal = BigDecimal.ZERO,
        var posts: Int = 0,
        var rejects: Int = 0,
        var other: Int = 0,
    ) {
        fun tree(): Map<String, Any?> = mapOf(
            "customerDeposit" to ProductIrCanonical.instructionAmount(customerDeposit),
            "posts" to posts,
            "rejects" to rejects,
            "other" to other,
        )

        fun accumulate(instruction: Instruction) {
            when (instruction) {
                is Instruction.Post -> {
                    posts++
                    customerDeposit += customerDepositMovement(instruction)
                }
                is Instruction.Reject -> rejects++
                else -> other++
            }
        }
    }

    /** Signed movement of the customer deposit in one `Post`: credits positive, debits negative. */
    private fun customerDepositMovement(post: Instruction.Post): BigDecimal =
        post.lines.filter { it.accountingClass == AccountingClass.CUSTOMER_DEPOSIT }
            .fold(BigDecimal.ZERO) { acc, line ->
                acc + if (line.direction == PostingDirection.CREDIT) line.amount else line.amount.negate()
            }

    private fun replay(
        runner: GoldenScenarioRunner,
        rev: ProductRevisionIr,
        id: String,
        zone: String,
        account: Map<String, Any?>,
    ): Map<String, RuleTotals> {
        @Suppress("UNCHECKED_CAST")
        val steps = (account["steps"] as? List<Map<String, Any?>> ?: emptyList()).map {
            it +
                ("expected" to emptyList<Any?>())
        }
        val document = mapOf(
            "name" to id,
            "revision" to Obp.revisionTree(rev),
            "clock" to mapOf("zone" to zone),
            "state" to account["state"],
            "steps" to steps,
        )
        val scenario = GoldenScenario.parse(CanonicalJson.write(document))
        val totals = HashMap<String, RuleTotals>()
        for (result in runner.run(scenario)) {
            for (instruction in result.instructions) {
                totals.getOrPut(instruction.ruleId) { RuleTotals() }.accumulate(instruction)
            }
        }
        return totals
    }
}
