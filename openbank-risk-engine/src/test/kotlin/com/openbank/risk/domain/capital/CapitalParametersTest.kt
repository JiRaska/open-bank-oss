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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The SHIPPED parameter set, read from application.yaml through the same config mapping the
 * service uses, holds the value its citation names. One test per factor family: a changed weight
 * without a changed citation (and a version bump) goes red here.
 */
class CapitalParametersTest {

    private val p = CapitalTestParameters.shipped()

    private fun assertFactors(vararg expected: Pair<CapitalFactor, String>) = expected.forEach { (f, v) ->
        assertThat(p[f]).describedAs("${f.key} (${f.citation})").isEqualByComparingTo(v)
    }

    @Test
    fun `the parameter set is identified, versioned and scoped to BCBS d424`() {
        assertThat(p.id).isEqualTo("bcbs-d424-sa")
        assertThat(p.version).isEqualTo("1")
        assertThat(p.source).contains("d424").contains("EU CRR Part Three Title II Chapter 2 not applied")
        assertThat(CapitalFactor.entries.filter { it.kind == FactorKind.RISK_WEIGHT }.map { it.citation })
            .allMatch { it.startsWith("BCBS d424 ¶") }
        assertThat(CapitalFactor.entries.filter { it.kind == FactorKind.MINIMUM_RATIO }.map { it.citation })
            .allMatch { it.startsWith("BCBS bcbs189 ¶50") }
    }

    @Test
    fun `sovereign and central bank weights are d424 7 Table 1 unrated and the 8 discretion`() = assertFactors(
        RW_SOVEREIGN_UNRATED to "1.00",
        RW_SOVEREIGN_DOMESTIC_CURRENCY to "0",
    )

    @Test
    fun `bank weights are the d424 21 Table 7 SCRA base weights`() = assertFactors(
        RW_BANK_SCRA_GRADE_A to "0.40",
        RW_BANK_SCRA_GRADE_B to "0.75",
        RW_BANK_SCRA_GRADE_C to "1.50",
    )

    @Test
    fun `retail and corporate weights are d424 55, 57 and 40`() = assertFactors(
        RW_RETAIL_REGULATORY to "0.75",
        RW_RETAIL_OTHER to "1.00",
        RW_CORPORATE_UNRATED to "1.00",
    )

    @Test
    fun `the defaulted weight is d424 92 with specific provisions under 20 percent`() =
        assertFactors(RW_DEFAULTED to "1.50")

    @Test
    fun `cash and other assets are d424 95 to 97`() = assertFactors(
        RW_CASH to "0",
        RW_CASH_ITEMS_IN_COLLECTION to "0.20",
        RW_OTHER_ASSET to "1.00",
    )

    @Test
    fun `minimum ratios are bcbs189 50`() = assertFactors(
        MIN_CET1_RATIO to "0.045",
        MIN_TIER1_RATIO to "0.06",
        MIN_TOTAL_CAPITAL_RATIO to "0.08",
    )

    @Test
    fun `the shipped classification is the conservative one`() {
        val c = p.classification
        assertThat(c.retailTreatment).isEqualTo(RetailTreatment.OTHER_RETAIL)
        assertThat(c.bankScraGrade).describedAs("unrated banks: Grade C (d424 ¶23, ¶26)").isEqualTo(ScraGrade.C)
        assertThat(c.domesticCurrency).isEqualTo("CZK")
        assertThat(c.glAccounts["1510"]).isEqualTo(CapitalGlClass.CENTRAL_BANK)
        assertThat(listOf("1001", "1002", "1500", "1501").map { c.glAccounts[it] }).containsOnly(CapitalGlClass.BANK)
        assertThat(c.glAccounts["6040"]).isEqualTo(CapitalGlClass.OWN_FUNDS_CET1_DEDUCTION)
        assertThat(c.glAccounts["6060"]).isEqualTo(CapitalGlClass.OWN_FUNDS_TIER2)
        assertThat(
            c.glAccounts.filterValues {
                it == CapitalGlClass.CASH
            },
        ).describedAs("nothing is known to be ¶96 cash")
            .isEmpty()
        assertThat(c.glAccounts).doesNotContainKeys("1000", "1400", "1100", "1110", "1990")
        assertThat(c.glAccountTypes).doesNotContainKey("ASSET")
    }

    @Test
    fun `a missing, unknown or out-of-range factor is refused, never defaulted`() {
        val keys = p.factors.mapKeys { it.key.key }
        assertThatThrownBy { CapitalParameters.fromKeys("x", "1", "s", keys - RW_DEFAULTED.key, p.classification) }
            .hasMessageContaining("missing factors").hasMessageContaining("rw-defaulted")
        assertThatThrownBy {
            CapitalParameters.fromKeys("x", "1", "s", keys + ("rw-made-up" to BigDecimal.ONE), p.classification)
        }.hasMessageContaining("unknown capital factor keys")
        assertThatThrownBy {
            CapitalParameters.fromKeys("x", "1", "s", keys + (RW_OTHER_ASSET.key to BigDecimal("13")), p.classification)
        }.hasMessageContaining("[0, 12.5]")
        assertThatThrownBy {
            CapitalParameters.fromKeys(
                "x",
                "1",
                "s",
                keys + (MIN_TOTAL_CAPITAL_RATIO.key to BigDecimal("8")),
                p.classification,
            )
        }.hasMessageContaining("(0, 1]")
    }

    @Test
    fun `an unknown class, grade or treatment is refused, and own funds are never mapped by type`() {
        assertThatThrownBy { CapitalGlClass.parse("hqla") }.hasMessageContaining("unknown capital GL class")
        assertThatThrownBy { ScraGrade.parse("D") }.hasMessageContaining("bank-scra-grade")
        assertThatThrownBy { RetailTreatment.parse("mortgage") }.hasMessageContaining("retail-treatment")
        assertThatThrownBy {
            p.classification.copy(glAccountTypes = mapOf("EQUITY" to CapitalGlClass.OWN_FUNDS_CET1))
        }.hasMessageContaining("never by account type")
    }
}
