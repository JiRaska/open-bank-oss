// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.libs.product.ir.Predicate
import com.openbank.libs.product.ir.ProductRevisionIr
import com.openbank.libs.product.replay.ReplayPortfolio
import com.openbank.libs.product.replay.ReplayRange
import com.openbank.libs.productrevision.HmacIrSigner
import com.openbank.libs.productrevision.PinnedRevisionUnavailableException
import com.openbank.libs.testing.product.PortfolioShape
import com.openbank.libs.testing.product.SyntheticPortfolio
import com.openbank.simulation.model.ProductShadowBook
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * ADR-0352 phase 4: every reference product, loaded signed, replayed over a generated portfolio for
 * three simulated years (2027–2029, so a leap year and 36 month ends), with the shadow ledger
 * proving a zero trial balance at every day end. Two-sided like every scenario here: the harness
 * also finds a book that loses a line, and refuses a revision whose signature does not verify.
 */
class ProductReplayScenarioTest {

    private val range = ReplayRange(LocalDate.of(2027, 1, 1), LocalDate.of(2029, 12, 31), ZoneId.of("Europe/Prague"))

    private fun portfolio(case: ProductReplayScenario.ReferenceCase, seed: Long, size: Int): ReplayPortfolio {
        val rev = ProductReplayScenario.loadSigned(case.name)
        return SyntheticPortfolio.generate(
            seed,
            size,
            PortfolioShape(
                rev.currency,
                range.from,
                range.to,
                thresholds(rev),
                overdraftAllowed = case.overdraftFloor.signum() < 0,
            ),
        )
    }

    @Test
    fun `every reference product replays three years with all invariants holding`() {
        for (case in ProductReplayScenario.CASES) {
            val report = ProductReplayScenario.run(case, portfolio(case, 20_270_101L, 200), range)
            val (known, unexpected) = report.violations.partition { it.substringBefore(':') in case.knownViolations }
            assertThat(unexpected).describedAs(case.name).isEmpty()
            assertThat(
                known.map {
                    it.substringBefore(':')
                }.toSet(),
            ).describedAs(case.name).isEqualTo(case.knownViolations)
            assertThat(report.daysChecked).isEqualTo(range.days.toInt())
            assertThat(report.result.evaluations).isPositive().isLessThanOrEqualTo(report.evaluationBound)
            assertThat(report.result.accounts.sumOf { it.counts.values.sum() }).describedAs(case.name).isPositive()
            // Only the product's own debits may take a balance below the floor: the monthly fee draining
            // a dormant account under its waiver threshold, and the term deposit's early-withdrawal
            // penalty on an account its known overdraw already took below zero.
            assertThat(report.findings.flatMap { it.rules }.toSet()).describedAs(case.name)
                .isSubsetOf("scheduled.fee", "posting.early-withdrawal")
        }
    }

    @Test
    fun `a dormant savings account under the waiver threshold is drained below zero by the fee alone`() {
        val case = ProductReplayScenario.CASES.first { it.name == "savings-fixed" }
        val report = ProductReplayScenario.run(case, portfolio(case, 20_270_101L, 200), range)
        assertThat(report.findings).isNotEmpty.allMatch {
            it.minBalance.signum() < 0 &&
                it.rules == listOf("scheduled.fee")
        }
    }

    @Test
    fun `the same seed reproduces the same replay`() {
        val case = ProductReplayScenario.CASES.first()
        val a = ProductReplayScenario.run(case, portfolio(case, 99L, 50), range).result
        val b = ProductReplayScenario.run(case, portfolio(case, 99L, 50), range).result
        assertThat(b.accounts).isEqualTo(a.accounts)
        assertThat(b.conservation).isEqualTo(a.conservation)
    }

    @Test
    fun `a shadow book that loses a line is caught at the first day end it unbalances`() {
        val case = ProductReplayScenario.CASES.first()
        val report = ProductReplayScenario.run(
            case,
            portfolio(case, 7L, 20),
            range,
            ProductShadowBook(dropLineEvery = 97),
        )
        assertThat(report.violations).anyMatch { it.startsWith("shadow-trial-balance:") }
        assertThat(report.violations).anyMatch { it.startsWith("shadow-deposit-reconciles:") }
    }

    @Test
    fun `a revision whose signature does not verify is refused before it is replayed`() {
        val otherKey = HmacIrSigner("a-different-simulation-signing-key".toByteArray(), "simulation-hmac-1")
        assertThatThrownBy { ProductReplayScenario.loadSigned("savings-fixed", otherKey) }
            .isInstanceOf(PinnedRevisionUnavailableException::class.java)
    }

    @Test
    fun `an overdraft floor tighter than the product's is reported`() {
        val case = ProductReplayScenario.CASES.first { it.name == "current-account" }
        val strict = case.copy(overdraftFloor = BigDecimal.ZERO)
        val report = ProductReplayScenario.run(strict, portfolio(case, 5L, 200), range)
        assertThat(report.violations).anyMatch { it.startsWith("no-negative-balance:") }
        assertThat(ProductReplayScenario.run(case, portfolio(case, 5L, 200), range).violations).isEmpty()
    }

    companion object {
        /** Every balance the revision changes behaviour at: tier edges, waiver and bonus thresholds. */
        fun thresholds(rev: ProductRevisionIr): List<BigDecimal> {
            fun walk(p: Predicate?): List<BigDecimal> = when (p) {
                null -> emptyList()
                is Predicate.Comparison -> listOfNotNull(p.threshold)
                is Predicate.BalanceMin -> listOf(p.amount)
                is Predicate.And -> p.operands.flatMap { walk(it) }
                is Predicate.Or -> p.operands.flatMap { walk(it) }
                is Predicate.Not -> walk(p.operand)
                else -> emptyList()
            }
            val tiers = rev.interest?.tiers.orEmpty().flatMap { listOfNotNull(it.from, it.to) }
            return (tiers + walk(rev.interest?.bonus?.predicate) + rev.fees.flatMap { walk(it.waiver) })
                .filter { it.signum() > 0 }.distinct().sorted()
        }
    }
}
