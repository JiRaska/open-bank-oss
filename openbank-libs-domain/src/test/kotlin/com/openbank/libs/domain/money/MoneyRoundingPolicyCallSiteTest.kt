// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.money

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Pins every [RoundingPolicy] to the code it claims to describe (ADR-0318). Each case copies a
 * real call site's rounding expression LITERALLY, with its file:line on `main` as of 2026-09-27,
 * and requires the policy to produce the same result on inputs chosen to sit exactly on a tie
 * (or, for DOWN, just below the next unit), where HALF_UP, HALF_EVEN and DOWN disagree.
 *
 * If a call site changes its mode or scale, update the literal here and decide whether the policy
 * follows it; if a policy is edited, the literal still encodes what the code does, so this fails.
 */
class MoneyRoundingPolicyCallSiteTest {

    private val eur = CurrencyCode.of("EUR")
    private val czk = CurrencyCode.of("CZK")
    private val jpy = CurrencyCode.of("JPY")
    private val kwd = CurrencyCode.of("KWD")

    /** Ties that separate HALF_UP / HALF_EVEN / DOWN at 0, 2 and 3 decimals, both signs. */
    private fun tiesAt(scale: Int): List<BigDecimal> = listOf("0.5", "1.5", "2.5", "-2.5", "2.4999").flatMap { t ->
        listOf(BigDecimal(t).movePointLeft(scale), BigDecimal(t).movePointLeft(scale).add(BigDecimal.TEN))
    }

    private fun check(
        policy: RoundingPolicy,
        currency: CurrencyCode,
        inputs: List<BigDecimal>,
        site: (BigDecimal) -> BigDecimal,
    ) {
        inputs.forEach { v ->
            assertThat(policy.round(v, currency))
                .describedAs("$policy on $v (${currency.code})")
                .isEqualByComparingTo(site(v))
            assertThat(policy.round(v, currency).scale()).isEqualTo(site(v).scale())
        }
    }

    @Test
    fun `MONEY_SCALE is Money scale and the entity rehydration mappers`() {
        for (c in listOf(eur, jpy, kwd)) {
            val ins = tiesAt(c.defaultFractionDigits)
            // openbank-libs-domain Money.kt:43 scale(); ledger PanacheJournalRepository.kt:377/379,
            // transaction PanacheTransactionRepository.kt:211/214, delegation DelegationGrantEntity.kt:191
            check(RoundingPolicy.MONEY_SCALE, c, ins) { it.setScale(c.defaultFractionDigits, RoundingMode.HALF_EVEN) }
        }
    }

    @Test
    fun `LEDGER_POSTING is how amounts are normalised for booking`() {
        for (c in listOf(eur, jpy, kwd)) {
            val ins = tiesAt(c.defaultFractionDigits)
            // openbank-transaction-service TransactionService.kt:127 (also :344, :353)
            check(RoundingPolicy.LEDGER_POSTING, c, ins) { it.setScale(c.defaultFractionDigits, RoundingMode.HALF_UP) }
            // openbank-interest-service InterestService.kt:394 gross (and :406 net)
            check(RoundingPolicy.LEDGER_POSTING, c, ins) { it.setScale(c.defaultFractionDigits, RoundingMode.HALF_UP) }
            // openbank-sdd-service SddCollectionDebitConsumer.kt:127; domestic SettlementAdapter.kt:84
            check(RoundingPolicy.LEDGER_POSTING, c, ins) { it.setScale(c.defaultFractionDigits, RoundingMode.HALF_UP) }
        }
        // openbank-ledger-service FxRevaluationPosting.kt:119-120 — CZK, literal scale 2
        check(RoundingPolicy.LEDGER_POSTING, czk, tiesAt(2)) { it.setScale(2, RoundingMode.HALF_UP) }
    }

    @Test
    fun `INTEREST_DAILY_RATE then INTEREST_ACCRUAL reproduce the two-step accrual`() {
        val divisor = BigDecimal(365)
        val rates = listOf("0.0365", "0.05", "0.0123456789", "0.00000000365").map(::BigDecimal)
        val balances = listOf("1000.00", "12345.67", "0.01", "-2500.00").map(::BigDecimal)
        for (annualRate in rates) {
            for (balance in balances) {
                // openbank-interest-service InterestService.kt:137
                val dailyRate = annualRate.divide(divisor, 10, RoundingMode.HALF_UP)
                // openbank-interest-service InterestService.kt:138
                val accruedAmount = balance.multiply(dailyRate).setScale(6, RoundingMode.HALF_UP)

                val policyRate = RoundingPolicy.INTEREST_DAILY_RATE.round(
                    annualRate.divide(divisor, java.math.MathContext.DECIMAL128),
                    eur,
                )
                assertThat(policyRate).isEqualTo(dailyRate)
                assertThat(
                    RoundingPolicy.INTEREST_ACCRUAL.round(balance.multiply(policyRate), eur),
                ).isEqualTo(accruedAmount)
            }
        }
        // Tie inputs directly at each scale.
        check(RoundingPolicy.INTEREST_DAILY_RATE, eur, tiesAt(10)) { it.setScale(10, RoundingMode.HALF_UP) }
        check(RoundingPolicy.INTEREST_ACCRUAL, eur, tiesAt(6)) { it.setScale(6, RoundingMode.HALF_UP) }
    }

    @Test
    fun `FX_RATE is the scale-8 rate arithmetic`() {
        // openbank-fx-service FxRate.kt:53 / CnbFixing.kt:23 / transaction TransactionService.kt:345
        check(RoundingPolicy.FX_RATE, eur, tiesAt(8)) { it.setScale(8, RoundingMode.HALF_UP) }
        val ask = BigDecimal("24.6875")
        assertThat(RoundingPolicy.FX_RATE.round(BigDecimal.ONE.divide(ask, java.math.MathContext.DECIMAL128), eur))
            .isEqualTo(BigDecimal.ONE.divide(ask, 8, RoundingMode.HALF_UP))
    }

    @Test
    fun `FX_AMOUNT and FEE round the minor-unit product HALF_UP`() {
        for (c in listOf(eur, jpy, kwd)) {
            val d = c.defaultFractionDigits
            tiesAt(d).forEach { v ->
                // openbank-fx-service FxRate.kt:92 (convert) and :96 (fee), in minor units
                val siteMinor = v.movePointRight(d).setScale(0, RoundingMode.HALF_UP)
                assertThat(RoundingPolicy.FX_AMOUNT.round(v, c).movePointRight(d)).isEqualByComparingTo(siteMinor)
                assertThat(RoundingPolicy.FEE.round(v, c).movePointRight(d)).isEqualByComparingTo(siteMinor)
            }
        }
    }

    @Test
    fun `TAX_WITHHOLDING truncates to whole units`() {
        // openbank-interest-service WithholdingTaxPolicy.kt:67-68 (TAX_SCALE = 0, DOWN)
        check(RoundingPolicy.TAX_WITHHOLDING, czk, tiesAt(0) + BigDecimal("150.99")) {
            it.setScale(0, RoundingMode.DOWN)
        }
    }

    @Test
    fun `DISPLAY is the statement renderers' fixed scale 2`() {
        for (c in listOf(eur, jpy, kwd)) {
            // openbank-statement-service PdfRenderer.kt:82, Camt053Renderer.kt:90, Mt940Renderer.kt:60
            check(RoundingPolicy.DISPLAY, c, tiesAt(2)) { it.setScale(2, RoundingMode.HALF_UP) }
        }
    }

    @Test
    fun `every policy is pinned by a case above`() {
        assertThat(RoundingPolicy.entries.map { it.name }).containsExactlyInAnyOrder(
            "MONEY_SCALE", "LEDGER_POSTING", "INTEREST_DAILY_RATE", "INTEREST_ACCRUAL",
            "FX_RATE", "FX_AMOUNT", "FEE", "TAX_WITHHOLDING", "DISPLAY",
        )
    }
}
