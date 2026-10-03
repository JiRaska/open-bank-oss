// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.money

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Pins the [RoundingPolicy] registry itself (ADR-0318) and how a policy applies its rule.
 *
 * What this test deliberately does NOT do any more: the earlier version re-typed each call site's
 * `setScale(n, MODE)` expression here as a literal, with a `file:line` comment, and compared the
 * policy to it. That could not detect drift — the literal lived in this file, not at the call
 * site, so a service changing its own rounding left this green — and its line references went
 * stale as soon as the sites moved. Now that call sites USE the policies, the two checks that
 * matter are separate:
 *
 * - **call sites use a named policy, not a literal** — the `money-rounding-inline-ratchet` gate
 *   (`.github/scripts/check-money-rounding-inline.py`) counts inline `RoundingMode.`/`setScale(`
 *   in every money-path service and fails on any new one;
 * - **a policy's (scale, mode) does not change silently** — `registry is pinned` below. A policy
 *   edit changes every amount its call sites produce, so it must fail here and go through its own
 *   money-path review rather than ride along in a refactor.
 */
class MoneyRoundingPolicyCallSiteTest {

    private val eur = CurrencyCode.of("EUR")
    private val jpy = CurrencyCode.of("JPY")
    private val kwd = CurrencyCode.of("KWD")

    @Test
    fun `registry is pinned`() {
        val pinned = mapOf(
            RoundingPolicy.MONEY_SCALE to (null to RoundingMode.HALF_EVEN),
            RoundingPolicy.LEDGER_POSTING to (null to RoundingMode.HALF_UP),
            RoundingPolicy.INTEREST_DAILY_RATE to (10 to RoundingMode.HALF_UP),
            RoundingPolicy.INTEREST_ACCRUAL to (6 to RoundingMode.HALF_UP),
            RoundingPolicy.FX_RATE to (8 to RoundingMode.HALF_UP),
            RoundingPolicy.FX_AMOUNT to (null to RoundingMode.HALF_UP),
            RoundingPolicy.FEE to (null to RoundingMode.HALF_UP),
            RoundingPolicy.TAX_WITHHOLDING to (0 to RoundingMode.DOWN),
            RoundingPolicy.DISPLAY to (null to RoundingMode.HALF_UP),
            RoundingPolicy.RATIO_PERCENT to (2 to RoundingMode.HALF_UP),
            RoundingPolicy.RATE_PERCENT to (4 to RoundingMode.HALF_UP),
            RoundingPolicy.TREASURY_AMOUNT to (2 to RoundingMode.HALF_UP),
            RoundingPolicy.TREASURY_INTEREST_WORK to (12 to RoundingMode.HALF_UP),
        )
        assertThat(RoundingPolicy.entries).containsExactlyInAnyOrderElementsOf(pinned.keys)
        pinned.forEach { (policy, rule) ->
            assertThat(policy.fixedScale to policy.mode).describedAs(policy.name).isEqualTo(rule)
        }
    }

    @Test
    fun `a currency-scaled policy rounds to the currency's minor units`() {
        val v = BigDecimal("2.5005")
        assertThat(RoundingPolicy.LEDGER_POSTING.round(v, eur)).isEqualTo(BigDecimal("2.50"))
        assertThat(RoundingPolicy.LEDGER_POSTING.round(v, jpy)).isEqualTo(BigDecimal("3"))
        assertThat(RoundingPolicy.LEDGER_POSTING.round(v, kwd)).isEqualTo(BigDecimal("2.501"))
        // HALF_EVEN vs HALF_UP on an exact tie
        assertThat(RoundingPolicy.MONEY_SCALE.round(BigDecimal("2.125"), eur)).isEqualTo(BigDecimal("2.12"))
        assertThat(RoundingPolicy.LEDGER_POSTING.round(BigDecimal("2.125"), eur)).isEqualTo(BigDecimal("2.13"))
    }

    @Test
    fun `a fixed-scale policy ignores the currency`() {
        assertThat(RoundingPolicy.FX_RATE.round(BigDecimal("0.123456785"), jpy)).isEqualTo(BigDecimal("0.12345679"))
        assertThat(RoundingPolicy.TAX_WITHHOLDING.round(BigDecimal("150.99"))).isEqualTo(BigDecimal("150"))
        assertThat(RoundingPolicy.TAX_WITHHOLDING.round(BigDecimal("-2.5"))).isEqualTo(BigDecimal("-2"))
    }

    @Test
    fun `divide rounds once, at the policy's scale`() {
        assertThat(RoundingPolicy.RATIO_PERCENT.divide(BigDecimal(2), BigDecimal(3))).isEqualTo(BigDecimal("0.67"))
        // 0.0445 rounded via scale 3 then 2 would give 0.05; a single HALF_UP rounding gives 0.04.
        assertThat(RoundingPolicy.TREASURY_AMOUNT.divide(BigDecimal("0.0445"), BigDecimal.ONE))
            .isEqualTo(BigDecimal("0.04"))
    }

    @Test
    fun `the two-step interest accrual is daily rate then accrual`() {
        val dailyRate = RoundingPolicy.INTEREST_DAILY_RATE.divide(BigDecimal("0.05"), BigDecimal(365))
        assertThat(dailyRate).isEqualTo(BigDecimal("0.0001369863"))
        assertThat(RoundingPolicy.INTEREST_ACCRUAL.round(BigDecimal("12345.67").multiply(dailyRate)))
            .isEqualTo(BigDecimal("1.691188"))
    }

    @Test
    fun `fixed-scale operations refuse a currency-scaled policy`() {
        assertThatThrownBy { RoundingPolicy.FEE.divide(BigDecimal.ONE, BigDecimal.TEN) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { RoundingPolicy.LEDGER_POSTING.round(BigDecimal.ONE) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
