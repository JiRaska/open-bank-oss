// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.capital

import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.PositionKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** The CZK total of a multi-currency book at the ČNB fixing, with hand-computed figures. */
class ReportingCurrencyTotalTest {

    private val shipped = CapitalTestParameters.shipped()
    private val asOf = LocalDate.parse("2026-09-25")

    private fun gl(code: String, amount: String, ccy: String, type: String = "ASSET") =
        Position(PositionKind.GL_ACCOUNT, code, type, ccy, null, BigDecimal(amount))

    private fun fx(ccy: String, rate: String) = ccy to FxRateUsed(ccy, BigDecimal(rate), asOf, "CNB")

    private fun run(positions: List<Position>, vararg rates: Pair<String, FxRateUsed>) =
        CreditRiskCapital.compute(positions, emptyList(), shipped, mapOf(*rates), asOf)

    /** The sandbox shape: CZK nostro + EUR nostro 1002 + a CZK-own-funds bank. */
    private val book = listOf(
        gl("1001", "1500", "CZK"), // bank, Grade C 150%       → 2 250 CZK
        gl("1300", "400", "CZK"), // other asset 100%          →   400 CZK
        gl("1002", "100", "EUR"), // bank, Grade C 150%        →   150 EUR
        gl("6000", "-3000", "CZK", "EQUITY"),
    )

    @Test
    fun `a EUR and CZK book converts EUR at the fixing into a CZK total per class`() {
        val r = run(book, fx("EUR", "24.335"))
        val t = r.total!!
        assertThat(t.currency).isEqualTo("CZK")
        // EAD: 1500 + 400 + 100 × 24.335 = 4333.5; RWA: 2250 + 400 + 150 × 24.335 = 6300.25
        assertThat(t.totalEad).isEqualByComparingTo("4333.5")
        assertThat(t.totalRwa).isEqualByComparingTo("6300.25")
        val classes = t.classes.associate { it.exposureClass to it.rwa }
        assertThat(classes[ExposureClass.BANK]).isEqualByComparingTo("5900.25") // 2250 + 3650.25
        assertThat(classes[ExposureClass.OTHER_ASSET]).isEqualByComparingTo("400")
        assertThat(r.ownFundsRequirement).isEqualByComparingTo("504.02") // 8% × 6300.25
        assertThat(r.totalNotStated).isNull()
        assertThat(r.fxRates).containsExactly(FxRateUsed("EUR", BigDecimal("24.335"), asOf, "CNB"))
        // per-currency results are untouched
        assertThat(r.currencies.single { it.currency == "EUR" }.totalRwa).isEqualByComparingTo("150")
        // own funds booked only in CZK: they stand in the total; ratios stay single-currency only
        assertThat(t.ownFunds!!.cet1).isEqualByComparingTo("3000")
        assertThat(r.ratios).isNull()
        assertThat(r.notes).contains(CreditRiskCapital.AGGREGATION_NOTE)
    }

    @Test
    fun `a per-100 ČNB quote arrives per unit and is applied per unit`() {
        // ČNB quotes JPY per 100: 14.512 CZK / 100 JPY → fx-service publishes 0.14512 per unit.
        val r = run(listOf(gl("1002", "10000", "JPY")), fx("JPY", "0.14512"))
        // 10 000 JPY × 0.14512 = 1451.2 CZK EAD; × 150% = 2176.8 RWA
        assertThat(r.total!!.totalEad).isEqualByComparingTo("1451.2")
        assertThat(r.total!!.totalRwa).isEqualByComparingTo("2176.8")
    }

    @Test
    fun `a missing rate leaves no total, names the currency and the date, and never totals the rest`() {
        val r = run(book + gl("1002", "50", "USD"), fx("EUR", "24.335"))
        assertThat(r.total).isNull()
        assertThat(r.ownFundsRequirement).isNull()
        assertThat(r.fxRates).isEmpty()
        assertThat(r.totalNotStated).contains("USD").contains("2026-09-25").doesNotContain("EUR")
    }

    @Test
    fun `an all-CZK book needs no rate and is its own total`() {
        val r = run(book.filter { it.currency == "CZK" })
        assertThat(r.total!!.totalRwa).isEqualByComparingTo("2650")
        assertThat(r.fxRates).isEmpty()
        assertThat(r.notes).doesNotContain(CreditRiskCapital.AGGREGATION_NOTE)
    }

    @Test
    fun `a foreign currency with no exposure needs no rate, and foreign own funds keep own funds out`() {
        val r = run(book.filter { it.currency == "CZK" } + gl("6000", "-10", "EUR", "EQUITY"))
        assertThat(r.total!!.totalRwa).isEqualByComparingTo("2650")
        assertThat(r.total!!.ownFunds).describedAs("EUR own funds are not converted").isNull()
    }
}
