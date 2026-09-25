// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.liquidity

import com.openbank.libs.lending.AmortizationMethod
import com.openbank.risk.domain.Fixtures
import com.openbank.risk.domain.curve.BigMath
import com.openbank.risk.domain.model.Instrument
import com.openbank.risk.domain.model.InstrumentKind
import com.openbank.risk.domain.model.LoanExtension
import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.PositionBuilder
import com.openbank.risk.domain.model.PositionKind
import com.openbank.risk.domain.model.ScheduledInstallment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * LCR / NSFR on hand-computed fixtures, with the SHIPPED parameter set. Each cap test states the
 * uncapped figure first — the number the test must NOT see — so removing a cap turns it red.
 */
class LiquidityTest {

    private val asOf = Fixtures.AS_OF
    private val shipped = LiquidityTestParameters.shipped()

    private fun gl(code: String, type: String, amount: String, ccy: String = "CZK") =
        Position(PositionKind.GL_ACCOUNT, code, type, ccy, null, BigDecimal(amount))

    private fun deposit(amount: String, ccy: String = "CZK") =
        Position(PositionKind.SUB_LEDGER, "2100", "LIABILITY", ccy, Fixtures.ALICE, BigDecimal(amount).negate())

    private fun run(
        positions: List<Position>,
        params: LiquidityParameters = shipped,
        instruments: List<Instrument> = emptyList(),
    ) = Liquidity.compute(positions, instruments, asOf, params)

    private fun mapped(vararg m: Pair<String, GlClass>) =
        LiquidityTestParameters.withClassification(glAccounts = shipped.classification.glAccounts + m)

    @Test
    fun `the tied-out fixture - nostro is an inflow at 0 percent, all retail less stable, LCR 0, NSFR 1_8`() {
        val r = run(PositionBuilder.build(Fixtures.tiedOut())).total!!
        // Nostro 1500 is NOT HQLA; operational balance at another bank: 0% inflow (d238 ¶156).
        assertThat(r.lcr.hqla.lines).isEmpty()
        assertThat(r.lcr.inflows.single().glAccountCode).isEqualTo("1001")
        assertThat(r.lcr.totalInflows).isEqualByComparingTo("0")
        // 1500 of deposits, all "less stable" by default (d238 ¶80): 10% run-off.
        assertThat(r.lcr.outflows.single().label).contains("less stable")
        assertThat(r.lcr.totalOutflows).isEqualByComparingTo("150")
        assertThat(r.lcr.ratio).isEqualByComparingTo("0")
        // ASF 1500 × 90% = 1350; RSF nostro 1500 × 50% (d295 ¶40(d)) = 750; NSFR = 1.8.
        assertThat(r.nsfr.totalAsf).isEqualByComparingTo("1350")
        assertThat(r.nsfr.totalRsf).isEqualByComparingTo("750")
        assertThat(r.nsfr.ratio).isEqualByComparingTo("1.8")
    }

    @Test
    fun `configuring a stable share moves that share to 5 percent run-off and 95 percent ASF`() {
        val p = LiquidityTestParameters.withClassification(retailStableShare = "0.4", operationalDepositShare = "0.5")
        val r = run(listOf(deposit("1000")), p).total!!
        // 500 operational, then 40% of the remaining 500 stable = 200, less stable 300.
        // Outflows 500·25% + 200·5% + 300·10% = 125 + 10 + 30; ASF 500·50% + 200·95% + 300·90%.
        assertThat(r.lcr.totalOutflows).isEqualByComparingTo("165")
        assertThat(r.nsfr.totalAsf).isEqualByComparingTo("710")
    }

    @Test
    fun `the 40 percent Level 2 cap binds - hand computed`() {
        val p = mapped("9001" to GlClass.HQLA_L1_CASH_OR_RESERVES, "9002" to GlClass.HQLA_L2A)
        // L1 100, L2A 117.647… market → 100 after the 15% haircut. Uncapped stock would be 200.
        val l2aMarket = BigDecimal(100).divide(BigDecimal("0.85"), BigMath.MC)
        val r = run(listOf(gl("9001", "ASSET", "100"), gl("9002", "ASSET", l2aMarket.toPlainString())), p).total!!
        val h = r.lcr.hqla
        assertThat(h.level2a).isEqualByComparingTo("100")
        assertThat(h.stock).isNotEqualByComparingTo("200")
        assertThat(h.level2CapBinding).isTrue()
        // adj40 = 100 − 2/3 · 100 = 33.33…; stock = 166.66…, of which L2 is exactly 40%.
        assertThat(h.adjustmentFor40Cap.setScale(6, java.math.RoundingMode.HALF_EVEN)).isEqualByComparingTo("33.333333")
        val l2Share = h.level2a.subtract(h.adjustmentFor40Cap).divide(h.stock, BigMath.MC)
        assertThat(l2Share.setScale(10, java.math.RoundingMode.HALF_EVEN)).isEqualByComparingTo("0.4")
    }

    @Test
    fun `the 15 percent Level 2B cap binds - hand computed`() {
        val p = mapped("9001" to GlClass.HQLA_L1_CASH_OR_RESERVES, "9003" to GlClass.HQLA_L2B_OTHER)
        // L1 100, L2B 100 market → 50 after the 50% haircut. Uncapped stock would be 150.
        val r = run(listOf(gl("9001", "ASSET", "100"), gl("9003", "ASSET", "100")), p).total!!
        val h = r.lcr.hqla
        assertThat(h.level2b).isEqualByComparingTo("50")
        assertThat(h.stock).isNotEqualByComparingTo("150")
        assertThat(h.level2bCapBinding).isTrue()
        // adj15 = max(50 − 15/85·100, 50 − 15/60·100, 0) = 50 − 17.647… = 32.352941…
        assertThat(h.adjustmentFor15Cap.setScale(6, java.math.RoundingMode.HALF_EVEN)).isEqualByComparingTo("32.352941")
        assertThat(h.adjustmentFor40Cap).isEqualByComparingTo("0")
        val l2bShare = h.level2b.subtract(h.adjustmentFor15Cap).divide(h.stock, BigMath.MC)
        assertThat(l2bShare.setScale(10, java.math.RoundingMode.HALF_EVEN)).isEqualByComparingTo("0.15")
    }

    @Test
    fun `inflows are capped at 75 percent of outflows - hand computed`() {
        val p = mapped(
            "9001" to GlClass.HQLA_L1_CASH_OR_RESERVES,
            "9004" to GlClass.DEPOSIT_AT_FI_NON_OPERATIONAL,
            "9005" to GlClass.OTHER_LIABILITY,
        )
        // HQLA 50; outflow 100 (other liability, 100%); inflow 200 (FI, 100%). Uncapped: net −100.
        val r = run(listOf(gl("9001", "ASSET", "50"), gl("9004", "ASSET", "200"), gl("9005", "LIABILITY", "-100")), p)
            .total!!.lcr
        assertThat(r.totalOutflows).isEqualByComparingTo("100")
        assertThat(r.totalInflows).isEqualByComparingTo("200")
        assertThat(r.inflowCapBinding).isTrue()
        assertThat(r.cappedInflows).isEqualByComparingTo("75")
        assertThat(r.netOutflows).isEqualByComparingTo("25")
        assertThat(r.ratio).isEqualByComparingTo("2")
    }

    @Test
    fun `an unmapped GL balance is listed as not classified and counted nowhere`() {
        val base = run(PositionBuilder.build(Fixtures.tiedOut())).total!!
        val withStray = run(PositionBuilder.build(Fixtures.tiedOut()) + gl("1002", "ASSET", "500"))
        assertThat(withStray.unclassified.map { it.glAccountCode to it.amount }).containsExactly(
            "1002" to BigDecimal("500"),
        )
        val t = withStray.total!!
        assertThat(t.nsfr.totalRsf).isEqualByComparingTo(base.nsfr.totalRsf)
        assertThat(t.lcr.hqla.stock).isEqualByComparingTo(base.lcr.hqla.stock)
        assertThat(t.lcr.totalInflows).isEqualByComparingTo(base.lcr.totalInflows)
        assertThat(
            t.nsfr.rsf.none {
                it.glAccountCode == "1002"
            } &&
                t.lcr.inflows.none { it.glAccountCode == "1002" },
        ).isTrue()
    }

    @Test
    fun `nostro becomes HQLA only when it is explicitly mapped as such`() {
        val positions = PositionBuilder.build(Fixtures.tiedOut())
        assertThat(run(positions).total!!.lcr.hqla.stock).isEqualByComparingTo("0")
        val r = run(positions, mapped("1001" to GlClass.HQLA_L1_CASH_OR_RESERVES)).total!!
        assertThat(r.lcr.hqla.stock).isEqualByComparingTo("1500")
        assertThat(r.lcr.ratio).isEqualByComparingTo("10") // 1500 / 150
    }

    @Test
    fun `capital counts 100 percent ASF, deductions and income are not stable funding`() {
        val r = run(
            listOf(
                gl("6000", "EQUITY", "-1000"),
                gl("6040", "EQUITY", "200"),
                gl("6060", "EQUITY", "-300"),
                gl("4100", "INCOME", "-50"),
            ),
        ).total!!.nsfr
        assertThat(r.totalAsf).isEqualByComparingTo("1000")
        assertThat(r.asf.single { it.glAccountCode == "6040" }.factor).isNull()
    }

    private fun loan(stage: String = "STAGE_1"): Instrument {
        val inst = listOf(
            ScheduledInstallment(1, asOf.plusDays(15), BigDecimal("100"), BigDecimal("10")),
            ScheduledInstallment(2, asOf.plusDays(45), BigDecimal("100"), BigDecimal("9")),
            ScheduledInstallment(3, asOf.plusYears(2), BigDecimal("800"), BigDecimal("8")),
        )
        return Instrument(
            "L1", InstrumentKind.AMORTISING_LOAN, "1200", "CZK", BigDecimal("1000"), asOf.minusYears(1),
            asOf.plusYears(2), null, null, stage, LoanExtension(AmortizationMethod.ANNUITY, 12, inst),
        )
    }

    private fun loanPosition() = Position(PositionKind.LOAN, "1200", "ASSET", "CZK", null, BigDecimal("1000"), "L1")

    @Test
    fun `a performing loan - 50 percent of payments due within 30 days, amortising RSF split`() {
        val r = run(listOf(loanPosition()), instruments = listOf(loan())).total!!
        assertThat(r.lcr.inflows.single().amount).isEqualByComparingTo("110") // 100 + 10 due on day 15
        assertThat(r.lcr.totalInflows).isEqualByComparingTo("55")
        // < 1y principal 200 × 50% + ≥ 1y 800 × 85% = 100 + 680
        assertThat(r.nsfr.totalRsf).isEqualByComparingTo("780")
    }

    @Test
    fun `a stage 3 loan gives no inflow and 100 percent RSF`() {
        val r = run(listOf(loanPosition()), instruments = listOf(loan("STAGE_3"))).total!!
        assertThat(r.lcr.inflows).isEmpty()
        assertThat(r.nsfr.totalRsf).isEqualByComparingTo("1000")
    }

    @Test
    fun `a two-currency book has per-currency results and no total`() {
        val r = run(listOf(deposit("100", "CZK"), deposit("100", "EUR")))
        assertThat(r.currencies.map { it.currency }).containsExactly("CZK", "EUR")
        assertThat(r.total).isNull()
        assertThat(r.notes.single()).isEqualTo(Liquidity.AGGREGATION_NOTE)
    }
}
