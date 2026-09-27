// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.capital

import com.openbank.risk.domain.Fixtures
import com.openbank.risk.domain.model.Instrument
import com.openbank.risk.domain.model.InstrumentKind
import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.PositionBuilder
import com.openbank.risk.domain.model.PositionKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Credit-risk RWA on hand-computed fixtures, with the SHIPPED (conservative, unconfigured)
 * parameter set. Every expected figure is written out as arithmetic next to its paragraph.
 */
class CreditRiskCapitalTest {

    private val shipped = CapitalTestParameters.shipped()
    private val asOf = Fixtures.AS_OF

    private fun gl(code: String, type: String, amount: String, ccy: String = "CZK") =
        Position(PositionKind.GL_ACCOUNT, code, type, ccy, null, BigDecimal(amount))

    private fun loan(id: String, amount: String, ccy: String = "CZK") =
        Position(PositionKind.LOAN, "1200", "ASSET", ccy, null, BigDecimal(amount), id)

    private fun instrument(id: String, stage: String?, amount: String) = Instrument(
        id, InstrumentKind.AMORTISING_LOAN, "1200", "CZK", BigDecimal(amount), asOf.minusYears(1),
        asOf.plusYears(2), null, null, stage, null,
    )

    private fun run(
        positions: List<Position>,
        instruments: List<Instrument> = emptyList(),
        params: CapitalParameters = shipped,
    ) = CreditRiskCapital.compute(positions, instruments, params)

    /** The six fixture exposures, plus own funds, of one CZK bank. */
    private val book = listOf(
        loan("L-RETAIL", "10000"), // stage 1: other retail 100% (¶57)      → 10 000
        loan("L-DEFAULT", "2000"), // stage 3: defaulted 150% (¶92)         →  3 000
        gl("1500", "ASSET", "5000"), // placement, unrated bank, Grade C 150% → 7 500
        gl("1510", "ASSET", "20000"), // ČNB facility in CZK, ¶8 at 0%       →      0
        gl("1001", "ASSET", "1500"), // nostro, Grade C 150%                 →  2 250
        gl("1300", "ASSET", "400"), // other asset 100% (¶95)                →    400
        gl("6000", "EQUITY", "-3000"),
        gl("6020", "EQUITY", "-500"),
        gl("6040", "EQUITY", "200"), // debit: deducted from CET1
        gl("6050", "EQUITY", "-300"),
        gl("6060", "EQUITY", "-400"),
        gl("2100", "LIABILITY", "-24000"),
        gl("4100", "INCOME", "-700"),
    )
    private val bookInstruments =
        listOf(instrument("L-RETAIL", "STAGE_1", "10000"), instrument("L-DEFAULT", "STAGE_3", "2000"))

    @Test
    fun `the fixture book - exact RWA per class, total, 8 percent requirement and ratios`() {
        val r = run(book, bookInstruments)
        val t = r.total!!
        val byClass = t.classes.associate { it.exposureClass to it.rwa }
        assertThat(byClass[ExposureClass.RETAIL]).isEqualByComparingTo("10000")
        assertThat(byClass[ExposureClass.DEFAULTED]).isEqualByComparingTo("3000")
        assertThat(byClass[ExposureClass.BANK]).isEqualByComparingTo("9750") // (5000 + 1500) × 1.5
        assertThat(byClass[ExposureClass.SOVEREIGN]).isEqualByComparingTo("0")
        assertThat(byClass[ExposureClass.OTHER_ASSET]).isEqualByComparingTo("400")
        assertThat(t.totalEad).isEqualByComparingTo("38900")
        assertThat(t.totalRwa).isEqualByComparingTo("23150")
        assertThat(r.ownFundsRequirement).isEqualByComparingTo("1852") // 23 150 × 8%

        val of = t.ownFunds!!
        assertThat(of.cet1BeforeDeductions).isEqualByComparingTo("3500")
        assertThat(of.cet1Deductions).isEqualByComparingTo("-200")
        assertThat(of.cet1).isEqualByComparingTo("3300")
        assertThat(of.tier1).isEqualByComparingTo("3600")
        assertThat(of.total).isEqualByComparingTo("4000")
        val ratios = r.ratios!!
        assertThat(ratios.cet1.ratio).isEqualByComparingTo("0.142549") // 3300 / 23150
        assertThat(ratios.tier1.ratio).isEqualByComparingTo("0.155508") // 3600 / 23150
        assertThat(ratios.total.ratio).isEqualByComparingTo("0.172786") // 4000 / 23150
        assertThat(ratios.total.minimum).isEqualByComparingTo("0.08")
        assertThat(ratios.total.meetsMinimum).isTrue()
        assertThat(r.ratiosNotComputable).isNull()
        assertThat(r.unclassified).isEmpty()
        assertThat(r.notes).anyMatch { it.contains("UPPER BOUND") }
    }

    @Test
    fun `every line carries its factor key and d424 citation`() {
        val lines = run(book, bookInstruments).total!!.lines
        assertThat(lines.single { it.instrumentId == "L-DEFAULT" }.citation).contains("d424 ¶90, ¶92")
        assertThat(lines.single { it.glAccountCode == "1500" }.factorKey).isEqualTo("rw-bank-scra-grade-c")
        assertThat(lines.single { it.glAccountCode == "1510" }.factorKey).isEqualTo("rw-sovereign-domestic-currency")
        assertThat(lines.map { it.citation }).allMatch { it.startsWith("BCBS d424 ¶") }
    }

    @Test
    fun `an unmapped GL balance is listed and counted nowhere`() {
        val base = run(book, bookInstruments).total!!.totalRwa
        val r = run(book + gl("1000", "ASSET", "999"), bookInstruments)
        assertThat(r.total!!.totalRwa).describedAs("1000 must NOT be weighted").isEqualByComparingTo(base)
        assertThat(r.total!!.totalEad).isEqualByComparingTo("38900")
        assertThat(r.unclassified.single().glAccountCode).isEqualTo("1000")
        assertThat(r.unclassified.single().amount).isEqualByComparingTo("999")
    }

    @Test
    fun `ratios are absent, with the reason, when no own-funds account is in the snapshot`() {
        val r = run(book.filterNot { it.glAccountCode!!.startsWith("60") }, bookInstruments)
        assertThat(r.total!!.ownFunds).isNull()
        assertThat(r.ratios).isNull()
        assertThat(r.ratiosNotComputable).contains("no own-funds GL account")
        assertThat(r.ownFundsRequirement).describedAs("the requirement needs only RWA").isEqualByComparingTo("1852")
    }

    @Test
    fun `a central-bank claim outside the domestic currency takes the unrated d424 7 weight`() {
        val r = run(listOf(gl("1510", "ASSET", "100", "EUR")))
        assertThat(r.total!!.totalRwa).isEqualByComparingTo("100")
        assertThat(r.total!!.lines.single().factorKey).isEqualTo("rw-sovereign-unrated")
    }

    @Test
    fun `configured grade A and regulatory retail change only what they name`() {
        val p = CapitalTestParameters.withClassification(
            retailTreatment = RetailTreatment.REGULATORY_RETAIL,
            bankScraGrade = ScraGrade.A,
        )
        // 10 000 × 75% + 3 000 (stage 3 unchanged) + 6 500 × 40% + 0 + 400 = 7 500 + 3 000 + 2 600 + 400
        assertThat(run(book, bookInstruments, p).total!!.totalRwa).isEqualByComparingTo("13500")
    }

    @Test
    fun `corporate-unrated moves loans to the corporate class at 100 percent`() {
        val p = CapitalTestParameters.withClassification(retailTreatment = RetailTreatment.CORPORATE_UNRATED)
        val t = run(book, bookInstruments, p).total!!
        assertThat(
            t.classes.map {
                it.exposureClass
            },
        ).contains(ExposureClass.CORPORATE).doesNotContain(ExposureClass.RETAIL)
        assertThat(t.totalRwa).isEqualByComparingTo("23150")
    }

    @Test
    fun `overdrafts are retail, deposits are not exposures`() {
        val r = run(
            PositionBuilder.build(Fixtures.tiedOut()) +
                Position(PositionKind.SUB_LEDGER, "2100", "LIABILITY", "CZK", Fixtures.ALICE, BigDecimal("50")),
        )
        val t = r.total!!
        // nostro 1500 × 150% + overdraft 50 × 100%
        assertThat(t.totalRwa).isEqualByComparingTo("2300")
        assertThat(t.lines.single { it.exposureClass == ExposureClass.RETAIL }.ead).isEqualByComparingTo("50")
    }

    @Test
    fun `a credit balance on an exposure or deduction account is listed, never netted or added`() {
        val r = run(listOf(gl("1001", "ASSET", "-10"), gl("6000", "EQUITY", "-100"), gl("6040", "EQUITY", "-5")))
        assertThat(r.total!!.totalRwa).isEqualByComparingTo("0")
        assertThat(r.total!!.ownFunds!!.cet1).isEqualByComparingTo("100")
        assertThat(r.unclassified.map { it.glAccountCode }).containsExactly("1001", "6040")
        assertThat(r.ratiosNotComputable).contains("RWA is zero")
    }

    @Test
    fun `a two-currency book has per-currency results, no total and no ratios`() {
        val r = run(book + gl("1002", "ASSET", "100", "EUR"), bookInstruments)
        assertThat(r.currencies.map { it.currency }).containsExactly("CZK", "EUR")
        assertThat(r.total).isNull()
        assertThat(r.ownFundsRequirement).isNull()
        assertThat(r.ratios).isNull()
        assertThat(r.ratiosNotComputable).contains("multi-currency")
        assertThat(r.currencies.single { it.currency == "EUR" }.totalRwa).isEqualByComparingTo("150")
    }
}
