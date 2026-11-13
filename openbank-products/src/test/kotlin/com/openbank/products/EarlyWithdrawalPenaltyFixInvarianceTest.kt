// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.products

import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.RoundingPolicy
import com.openbank.libs.finance.deposit.FinanceProductArithmetic
import com.openbank.libs.product.ir.Hook
import com.openbank.libs.product.ir.Instruction
import com.openbank.libs.product.ir.PenaltyKind
import com.openbank.libs.product.ir.PostingDirection
import com.openbank.libs.product.ir.ProductClock
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.product.ir.ProductStateTransition
import com.openbank.libs.testing.product.GoldenScenario
import com.openbank.libs.testing.product.GoldenScenarioRunner
import com.openbank.libs.testing.product.GoldenScenarios
import com.openbank.products.golden.ProductGoldens
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The v1 early-withdrawal fix changes outcomes only where the old outcome overdrew the deposit.
 *
 * `v1-pre-penalty-fix-outputs.tsv` is the canonical output of every golden step, produced by the
 * interpreter BEFORE the fix. Every step where `balance - amount - penalty >= 0` (or the condition
 * does not apply) must still produce those exact bytes; every step where the condition holds must
 * be a Reject and nothing else, and the only step whose bytes differ is the new 10 000.00 golden step.
 */
class EarlyWithdrawalPenaltyFixInvarianceTest {

    private val runner = GoldenScenarioRunner()
    private val interpreter = ProductInterpreter(FinanceProductArithmetic)

    private val preFix: Map<String, String> =
        checkNotNull(javaClass.getResourceAsStream("/v1-pre-penalty-fix-outputs.tsv")).reader().readLines()
            .filter { it.isNotBlank() }
            .associate { line ->
                val (name, at, canonical) = line.split('\t', limit = 3)
                "$name\t$at" to canonical
            }

    @Test
    fun `steps outside the overdraw condition are byte-identical to the pre-fix interpreter`() {
        val sets = GoldenScenarios.names().map { "libs-testing/$it" to GoldenScenarios.load(it) } +
            ProductGoldens.names().map { "openbank-products/$it" to ProductGoldens.load(it) }
        val steps = sets.flatMap { (name, scenario) -> replay(scenario).map { "$name\t${it.step.at}" to it } }
        val changed = steps.mapNotNull { (key, r) -> check(key, r) }
        assertThat(steps.map { it.first }.filter { it in preFix }).containsExactlyInAnyOrderElementsOf(preFix.keys)
        assertThat(changed).containsExactly("openbank-products/term-deposit-reference.json\t2026-06-01T09:30:00Z")
    }

    /** Asserts one step against the pre-fix output; returns its key when its bytes changed. */
    private fun check(key: String, r: Replayed): String? {
        val canonical = runner.stripHash(r.instructions)
        if (!r.overdraws) {
            // Outside the condition the fix must be invisible: same bytes as before it.
            assertThat(preFix).describedAs("pre-fix output for $key").containsKey(key)
            assertThat(canonical).describedAs(key).isEqualTo(preFix.getValue(key))
            return null
        }
        // Inside it the result is a Reject and nothing else, whichever rule refuses first.
        assertThat(r.instructions).describedAs(key).singleElement().isInstanceOf(Instruction.Reject::class.java)
        return key.takeIf { preFix[it] != canonical }
    }

    private data class Replayed(
        val step: GoldenScenario.Step,
        val overdraws: Boolean,
        val instructions: List<Instruction>,
    )

    private fun replay(scenario: GoldenScenario): List<Replayed> {
        var state = scenario.initialState
        return scenario.steps.map { step ->
            val clock = ProductClock(step.at, scenario.zone)
            val today = clock.today()
            val posting = step.event.posting
            val term = scenario.revision.termDeposit
            val penalty = term?.earlyWithdrawal
            val overdraws = step.event.hook == Hook.PRE_POSTING &&
                posting != null &&
                penalty != null &&
                penalty.kind == PenaltyKind.RATE &&
                posting.direction == PostingDirection.DEBIT &&
                today.isBefore(state.openedOn.plusMonths(term.termMonths.toLong())) &&
                (
                    state.balance - posting.amount - FinanceProductArithmetic.penaltyByRate(
                        posting.amount,
                        penalty.rate!!,
                        CurrencyCode.of(state.currency),
                        scenario.revision.interest?.postingRounding ?: RoundingPolicy.LEDGER_POSTING,
                    )
                    ).signum() < 0
            val out = interpreter.evaluate(scenario.revision, state, step.event, clock)
            state = ProductStateTransition.apply(state, out, today)
            if (posting != null && step.event.hook == Hook.PRE_POSTING && out.none { it is Instruction.Reject }) {
                state = ProductStateTransition.applyPosting(state, posting, today)
            }
            Replayed(step, overdraws, out)
        }
    }
}
