// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.capital

import com.openbank.risk.domain.capital.CapitalFactor.MIN_CET1_RATIO
import com.openbank.risk.domain.capital.CapitalFactor.MIN_TIER1_RATIO
import com.openbank.risk.domain.capital.CapitalFactor.MIN_TOTAL_CAPITAL_RATIO
import com.openbank.risk.domain.capital.CapitalFactor.RW_BANK_SCRA_GRADE_A
import com.openbank.risk.domain.capital.CapitalFactor.RW_BANK_SCRA_GRADE_B
import com.openbank.risk.domain.capital.CapitalFactor.RW_BANK_SCRA_GRADE_C
import com.openbank.risk.domain.capital.CapitalFactor.RW_CASH
import com.openbank.risk.domain.capital.CapitalFactor.RW_CASH_ITEMS_IN_COLLECTION
import com.openbank.risk.domain.capital.CapitalFactor.RW_CORPORATE_UNRATED
import com.openbank.risk.domain.capital.CapitalFactor.RW_DEFAULTED
import com.openbank.risk.domain.capital.CapitalFactor.RW_OTHER_ASSET
import com.openbank.risk.domain.capital.CapitalFactor.RW_RETAIL_OTHER
import com.openbank.risk.domain.capital.CapitalFactor.RW_RETAIL_REGULATORY
import com.openbank.risk.domain.capital.CapitalFactor.RW_SOVEREIGN_DOMESTIC_CURRENCY
import com.openbank.risk.domain.capital.CapitalFactor.RW_SOVEREIGN_UNRATED
import com.openbank.risk.domain.model.Instrument
import com.openbank.risk.domain.model.InstrumentKind
import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.PositionKind
import com.openbank.risk.infrastructure.toParameters
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The SHIPPED EU CRR set (`eu-crr3-sa`), read from application.yaml through the same config mapping
 * the service uses: the default, one test per exposure class asserting the weight AND the CRR
 * article it cites, and the sandbox-shaped book at exact RWA. Paragraphs the citation marks
 * UNVERIFIED are asserted as such, so a later confirmation has to change the test deliberately.
 */
class EuCapitalParametersTest {

    private val eu = CapitalTestParameters.eu()

    private fun assertFactor(f: CapitalFactor, value: String, vararg citation: String) {
        assertThat(eu[f]).describedAs("${f.key} (${eu.citation(f)})").isEqualByComparingTo(value)
        citation.forEach { assertThat(eu.citation(f)).describedAs(f.key).contains(it) }
    }

    @Test
    fun `the default set is the EU CRR set, version 1, and the BCBS set stays selectable`() {
        val default = CapitalTestParameters.shipped()
        assertThat(default.id).isEqualTo("eu-crr3-sa")
        assertThat(default.version).isEqualTo("2")
        assertThat(default.regime).isEqualTo(CapitalRegime.EU)
        assertThat(default.source).contains("575/2013").contains("2019/876").contains("2024/1623")

        val bcbs = CapitalTestParameters.bcbs()
        assertThat(bcbs.id).isEqualTo("bcbs-d424-sa")
        assertThat(bcbs.version).isEqualTo("3")
        assertThat(bcbs.regime).isEqualTo(CapitalRegime.BCBS)
        assertThat(bcbs.citation(RW_OTHER_ASSET)).startsWith("BCBS d424 ¶")
    }

    @Test
    fun `every EU citation names a CRR article, and every factor has one`() {
        CapitalFactor.entries.filter { it != RW_RETAIL_OTHER }.forEach {
            assertThat(eu.citation(it)).describedAs(it.key).startsWith("CRR Art. ")
        }
        assertThat(eu.citation(RW_RETAIL_OTHER)).startsWith("No CRR counterpart")
    }

    @Test
    fun `central governments and central banks - Art 114(1) 100 percent, Art 114(4) domestic currency 0 percent`() {
        assertFactor(RW_SOVEREIGN_UNRATED, "1.00", "CRR Art. 114(1)")
        assertFactor(RW_SOVEREIGN_DOMESTIC_CURRENCY, "0", "CRR Art. 114(4)", "domestic currency")
        assertThat(eu.classification.domesticCurrency).isEqualTo("CZK")
    }

    @Test
    fun `institutions - CRR3 Art 121 SCRA grades A 40, B 75, C 150, paragraph UNVERIFIED, grade C shipped`() {
        assertFactor(RW_BANK_SCRA_GRADE_A, "0.40", "CRR Art. 121", "UNVERIFIED")
        assertFactor(RW_BANK_SCRA_GRADE_B, "0.75", "CRR Art. 121", "UNVERIFIED")
        assertFactor(RW_BANK_SCRA_GRADE_C, "1.50", "CRR Art. 121", "UNVERIFIED")
        assertThat(eu.classification.bankScraGrade).isEqualTo(ScraGrade.C)
    }

    @Test
    fun `retail - Art 123 75 percent only by configuration, the shipped treatment is corporate Art 122 100 percent`() {
        assertFactor(RW_RETAIL_REGULATORY, "0.75", "CRR Art. 123", "UNVERIFIED")
        assertFactor(RW_CORPORATE_UNRATED, "1.00", "CRR Art. 122", "UNVERIFIED")
        assertThat(eu.classification.retailTreatment).isEqualTo(RetailTreatment.CORPORATE_UNRATED)
    }

    @Test
    fun `an EU set refuses other-retail - CRR has no such class`() {
        assertThatThrownBy {
            eu.copy(classification = eu.classification.copy(retailTreatment = RetailTreatment.OTHER_RETAIL))
        }.hasMessageContaining("other-retail").hasMessageContaining("Art. 122")
    }

    @Test
    fun `defaulted - Art 127(1) 150 percent, the conservative bucket, since adjustments are not in the data`() =
        assertFactor(RW_DEFAULTED, "1.50", "CRR Art. 127(1)", "< 20%")

    @Test
    fun `other items - Art 134(1) 100 percent, cash and collection paragraphs UNVERIFIED, nothing mapped`() {
        assertFactor(RW_OTHER_ASSET, "1.00", "CRR Art. 134(1)")
        assertFactor(RW_CASH, "0", "CRR Art. 134", "UNVERIFIED")
        assertFactor(RW_CASH_ITEMS_IN_COLLECTION, "0.20", "CRR Art. 134", "UNVERIFIED")
        val mapped = eu.classification.glAccounts
        assertThat(mapped).describedAs("1000 is not known to be cash in hand").doesNotContainKey("1000")
        assertThat(mapped.filterValues { it == CapitalGlClass.CASH || it == CapitalGlClass.CASH_ITEMS_IN_COLLECTION })
            .isEmpty()
        assertThat(mapped["1100"]).describedAs("#11481 POLICY CHOICE kept").isEqualTo(CapitalGlClass.OTHER_ASSET)
    }

    @Test
    fun `minimum ratios - Art 92(1)(a) to (c)`() {
        assertFactor(MIN_CET1_RATIO, "0.045", "CRR Art. 92(1)(a)")
        assertFactor(MIN_TIER1_RATIO, "0.06", "CRR Art. 92(1)(b)")
        assertFactor(MIN_TOTAL_CAPITAL_RATIO, "0.08", "CRR Art. 92(1)(c)")
    }

    @Test
    fun `the #11481 POLICY CHOICE classifications are shared, so identical under both sets`() {
        val bcbs = CapitalTestParameters.bcbs()
        assertThat(eu.classification.glAccounts).isEqualTo(bcbs.classification.glAccounts)
        assertThat(eu.classification.glAccountTypes).isEqualTo(bcbs.classification.glAccountTypes)
    }

    @Test
    fun `an undeclared parameter-set-id is refused, naming the declared ones`() {
        assertThatThrownBy { CapitalTestParameters.config().toParameters("crr-made-up") }
            .hasMessageContaining("'crr-made-up' is not declared")
            .hasMessageContaining("bcbs-d424-sa, eu-crr3-sa")
    }

    // --- the sandbox-shaped book (#10896 / #11107 asset set, as in RiskCapitalApiIT) ----------------

    private val asOf = LocalDate.parse("2026-09-06")

    private fun gl(code: String, amount: String, ccy: String = "CZK", type: String = "ASSET") =
        Position(PositionKind.GL_ACCOUNT, code, type, ccy, null, BigDecimal(amount))

    private val sandbox = listOf(
        gl("1001", "1500.00"), //   nostro: institution, SCRA Grade C 150% (Art. 121)  → 2 250.00
        gl("1100", "16000.00"), //  clearing: POLICY CHOICE other item 100% (134(1))   → 16 000.00
        Position(PositionKind.LOAN, "1200", "ASSET", "CZK", null, BigDecimal("9000.00"), "L-1"),
        //                          stage 1 loan: corporate 100% (Art. 122)            → 9 000.00
        gl("1300", "50.00"), //     interest receivable: other item 100% (134(1))      → 50.00
        gl("1400", "-80.00"), //    allowance: not an exposure (POLICY CHOICE)         → —
        gl("1520", "10.00"), //     accrued interest on placements: Grade C 150%       → 15.00
        gl("1990", "-132.00"), //   FX position: not an exposure (POLICY CHOICE)       → —
        gl("1995", "-126.00"), //   FX position: not an exposure                       → —
        gl("1991", "5.19", "EUR"), // FX position in EUR: not an exposure              → —
        gl("4100", "-5.19", "EUR", "INCOME"),
    )
    private val sandboxLoan = Instrument(
        "L-1", InstrumentKind.AMORTISING_LOAN, "1200", "CZK", BigDecimal("9000.00"), asOf.minusYears(1),
        asOf.plusYears(1), null, null, "STAGE_1", null,
    )
    private val eurFixing = mapOf("EUR" to FxRateUsed("EUR", BigDecimal("24.40"), asOf, "CNB"))

    private fun sandbox(p: CapitalParameters) =
        CreditRiskCapital.compute(sandbox, listOf(sandboxLoan), p, eurFixing, asOf)

    @Test
    fun `the sandbox-shaped book under EU weights - exact CZK RWA per class and total`() {
        val r = sandbox(eu)
        assertThat(r.unclassified).isEmpty()
        val total = r.total!!
        assertThat(total.currency).isEqualTo("CZK")
        val byClass = total.classes.associate { it.exposureClass to it.rwa }
        assertThat(byClass.keys).containsExactlyInAnyOrder(
            ExposureClass.BANK,
            ExposureClass.CORPORATE,
            ExposureClass.OTHER_ASSET,
        )
        assertThat(byClass[ExposureClass.BANK]).isEqualByComparingTo("2265.00") // 2 250 + 15
        assertThat(byClass[ExposureClass.CORPORATE]).isEqualByComparingTo("9000.00")
        assertThat(byClass[ExposureClass.OTHER_ASSET]).isEqualByComparingTo("16050.00") // 16 000 + 50
        assertThat(total.totalRwa).isEqualByComparingTo("27315.00")
        assertThat(r.ownFundsRequirement).isEqualByComparingTo("2185.20") // 8% × 27 315 (Art. 92(1)(c))
        assertThat(total.lines.map { it.glAccountCode }).doesNotContain("1400", "1990", "1991", "1995")
        assertThat(total.lines.map { it.citation }).allMatch { it.startsWith("CRR Art. ") }
        assertThat(total.lines.single { it.instrumentId == "L-1" }.citation).contains("Art. 122")
    }

    /**
     * NEGATIVE CHECK: the same book under the BCBS set. The total happens to be equal (d424 ¶57
     * other retail and CRR Art. 122 corporate are both 100%), which is exactly why the EU assertions
     * above pin the CLASS and the CITATION, not only the number: both flip here.
     */
    @Test
    fun `the same book under the BCBS set has the same total but not the EU class or citations`() {
        val total = sandbox(CapitalTestParameters.bcbs()).total!!
        assertThat(total.totalRwa).isEqualByComparingTo("27315.00")
        val classes = total.classes.map { it.exposureClass }
        assertThat(classes).contains(ExposureClass.RETAIL).doesNotContain(ExposureClass.CORPORATE)
        assertThat(total.lines.map { it.citation }).noneMatch { it.startsWith("CRR Art. ") }
    }

    @Test
    fun `a defaulted loan and a central-bank claim under EU weights`() {
        val default = Position(PositionKind.LOAN, "1200", "ASSET", "CZK", null, BigDecimal("2000"), "L-D")
        val stage3 = sandboxLoan.copy(id = "L-D", ifrs9Stage = "STAGE_3")
        val r = CreditRiskCapital.compute(
            listOf(default, gl("1510", "20000"), gl("1510", "100", "EUR")),
            listOf(stage3),
            eu,
            eurFixing,
            asOf,
        ).total!!
        val d = r.lines.single { it.instrumentId == "L-D" }
        assertThat(d.exposureClass).isEqualTo(ExposureClass.DEFAULTED)
        assertThat(d.rwa).isEqualByComparingTo("3000") // 2 000 × 150% (Art. 127(1))
        val cb = r.lines.filter { it.exposureClass == ExposureClass.SOVEREIGN }
        // ČNB in CZK 20 000 × 0% (Art. 114(4)); in EUR 100 × 24.40 × 100% (Art. 114(1)) = 2 440
        assertThat(cb.sumOf { it.rwa }).isEqualByComparingTo("2440")
        assertThat(cb.map { it.citation }).anyMatch { it.startsWith("CRR Art. 114(4)") }
            .anyMatch { it.startsWith("CRR Art. 114(1)") }
    }
}
