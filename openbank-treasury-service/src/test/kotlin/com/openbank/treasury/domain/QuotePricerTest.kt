// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain

import com.openbank.treasury.domain.DealFixtures.placement
import com.openbank.treasury.domain.model.CurvePillar
import com.openbank.treasury.domain.model.CurveSetView
import com.openbank.treasury.domain.model.MarketCurve
import com.openbank.treasury.domain.model.ProductType
import com.openbank.treasury.domain.model.QuotePricer
import com.openbank.treasury.domain.model.QuoteUnavailableException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class QuotePricerTest {

    private val asOf = LocalDate.parse("2026-09-21")

    private fun curve(index: String = "CZEONIA", vararg pillars: Pair<Long, String>) = MarketCurve(
        index,
        if (index ==
            "ESTR"
        ) {
            "EUR"
        } else {
            "CZK"
        },
        pillars.map { (d, z) -> CurvePillar(asOf.plusDays(d), BigDecimal(z)) },
    )

    private fun set(vararg curves: MarketCurve) =
        CurveSetView(UUID.fromString("0191c0de-0000-7000-8000-00000000c5e7"), asOf, "synthetic", curves.toList())

    @Test
    fun `a flat 3,5 % continuous zero curve prices a 30-day ACT-360 simple mid of 3,4570 %`() {
        // (exp(0.035 x 30/365) - 1) x 360/30 x 100 = 3.457024...
        assertThat(QuotePricer.midRate(curve("CZEONIA", 1L to "0.035", 365L to "0.035"), asOf, 30))
            .isEqualByComparingTo("3.4570")
    }

    @Test
    fun `zero rates are linear between pillars and flat outside them`() {
        val c = curve("CZEONIA", 10L to "0.02", 30L to "0.04")
        val mid20 = QuotePricer.midRate(c, asOf, 20) // z = 0.03 exactly half-way
        val flat = QuotePricer.midRate(curve("CZEONIA", 1L to "0.03", 365L to "0.03"), asOf, 20)
        assertThat(mid20).isEqualByComparingTo(flat)
        // before the first pillar: its rate; past the last: the last pillar's, never extrapolated
        assertThat(QuotePricer.midRate(c, asOf, 5)).isEqualByComparingTo(
            QuotePricer.midRate(
                curve(
                    "CZEONIA",
                    1L to "0.02",
                ),
                asOf,
                5,
            ),
        )
        assertThat(QuotePricer.midRate(c, asOf, 90)).isEqualByComparingTo(
            QuotePricer.midRate(
                curve(
                    "CZEONIA",
                    1L to "0.04",
                ),
                asOf,
                90,
            ),
        )
    }

    @Test
    fun `a counterparty quotes the mid plus-minus its half-spread, labelled synthetic`() {
        val q = QuotePricer.quote(set(curve("CZEONIA", 1L to "0.035", 365L to "0.035")), "SIMBK-A", "CZK", 30, 5)
        assertThat(q.mid).isEqualByComparingTo("3.4570")
        assertThat(q.bid).isEqualByComparingTo("3.4070")
        assertThat(q.ask).isEqualByComparingTo("3.5070")
        assertThat(q.curveIndex).isEqualTo("CZEONIA")
        assertThat(q.curveProvenance).isEqualTo("synthetic")
        assertThat(q.synthetic).isTrue()
        assertThat(q.rateFor(ProductType.MM_PLACEMENT)).isEqualTo(q.bid)
        assertThat(q.rateFor(ProductType.MM_BORROWING)).isEqualTo(q.ask)
    }

    @Test
    fun `EUR quotes off ESTR, and a set without the currency's curve cannot quote`() {
        val eur = QuotePricer.quote(set(curve("ESTR", 1L to "0.02")), "SIMBK-B", "EUR", 7, 10)
        assertThat(eur.curveIndex).isEqualTo("ESTR")
        assertThatThrownBy { QuotePricer.quote(set(curve("ESTR", 1L to "0.02")), "SIMBK-A", "CZK", 7, 10) }
            .isInstanceOf(QuoteUnavailableException::class.java)
            .hasMessageContaining("no CZEONIA curve")
    }

    @Test
    fun `the bid is floored at zero - a deal rate may not be negative`() {
        val q = QuotePricer.quote(set(curve("CZEONIA", 1L to "0.0001")), "SIMBK-C", "CZK", 30, 50)
        assertThat(q.bid).isEqualByComparingTo("0")
        assertThat(q.ask).isGreaterThan(q.mid)
    }

    @Test
    fun `a counterparty accepts a placement at or under its bid and a borrowing at or over its ask`() {
        val q = QuotePricer.quote(set(curve("CZEONIA", 1L to "0.035", 365L to "0.035")), "SIMBK-A", "CZK", 30, 5)
        assertThat(q.accepts(placement(rate = "3.4070"))).isTrue()
        assertThat(q.accepts(placement(rate = "3.40"))).isTrue()
        assertThat(q.accepts(placement(rate = "3.4071"))).describedAs("above its bid: it would overpay").isFalse()
        assertThat(q.accepts(placement(rate = "3.5070", product = ProductType.MM_BORROWING))).isTrue()
        assertThat(q.accepts(placement(rate = "3.5069", product = ProductType.MM_BORROWING)))
            .describedAs("below its ask: it would underprice its own loan").isFalse()
    }

    @Test
    fun `only the money-market products are quoted, and a tenor is at least one day`() {
        val q = QuotePricer.quote(set(curve("CZEONIA", 1L to "0.035")), "SIMBK-A", "CZK", 1, 5)
        assertThatThrownBy { q.rateFor(ProductType.FX_SPOT) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { QuotePricer.midRate(curve("CZEONIA", 1L to "0.035"), asOf, 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { QuotePricer.quote(set(curve("CZEONIA", 1L to "0.035")), "SIMBK-A", "CZK", 30, -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
