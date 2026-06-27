// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.domain.tax

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class WithholdingTaxPolicyTest {

    private val asOf = LocalDate.of(2026, 5, 30)

    private fun compute(
        gross: String,
        currency: String = "CZK",
        profile: TaxProfile = TaxProfile.FAIL_SAFE_DEFAULT
    ) = WithholdingTaxPolicy.compute(BigDecimal(gross), currency, profile, asOf)

    @Test
    fun `resident individual is withheld at 15 percent`() {
        val result = compute("1000.0000")

        assertThat(result.treatment).isEqualTo(WithholdingTreatment.WITHHELD)
        assertThat(result.rate).isEqualByComparingTo("0.15")
        assertThat(result.taxableBase).isEqualByComparingTo("1000")
        assertThat(result.taxAmount).isEqualByComparingTo("150")
        assertThat(result.netAmount).isEqualByComparingTo("850.0000")
        assertThat(result.exemptCode).isNull()
    }

    @Test
    fun `tax base is rounded down to whole CZK before applying the rate`() {
        // base floors 1234.9999 -> 1234; tax = 1234 * 0.15 = 185.10 -> floors to 185.
        val result = compute("1234.9999")

        assertThat(result.taxableBase).isEqualByComparingTo("1234")
        assertThat(result.taxAmount).isEqualByComparingTo("185")
        assertThat(result.netAmount).isEqualByComparingTo("1049.9999")
    }

    @Test
    fun `tax amount itself is rounded down to whole CZK`() {
        // base 101 -> tax = 101 * 0.15 = 15.15 -> floors to 15 (not 16, not 15.15).
        val result = compute("101.0000")

        assertThat(result.taxAmount).isEqualByComparingTo("15")
        assertThat(result.netAmount).isEqualByComparingTo("86.0000")
    }

    @Test
    fun `non-resident with no treaty defaults to 15 percent`() {
        val result = compute(
            "1000",
            profile = TaxProfile(TaxpayerType.INDIVIDUAL, TaxResidency.NON_RESIDENT)
        )

        assertThat(result.rate).isEqualByComparingTo("0.15")
        assertThat(result.taxAmount).isEqualByComparingTo("150")
        assertThat(result.treatment).isEqualTo(WithholdingTreatment.WITHHELD)
    }

    @Test
    fun `non-resident in a non-cooperating state is withheld at 35 percent`() {
        val result = compute(
            "1000",
            profile = TaxProfile(
                TaxpayerType.INDIVIDUAL,
                TaxResidency.NON_RESIDENT,
                nonCooperatingState = true
            )
        )

        assertThat(result.rate).isEqualByComparingTo("0.35")
        assertThat(result.taxAmount).isEqualByComparingTo("350")
        assertThat(result.netAmount).isEqualByComparingTo("650")
    }

    @Test
    fun `a treaty rate overrides both the default and the non-cooperating rate`() {
        val result = compute(
            "1000",
            profile = TaxProfile(
                TaxpayerType.INDIVIDUAL,
                TaxResidency.NON_RESIDENT,
                treatyRate = BigDecimal("0.10"),
                nonCooperatingState = true
            )
        )

        assertThat(result.rate).isEqualByComparingTo("0.10")
        assertThat(result.taxAmount).isEqualByComparingTo("100")
    }

    @Test
    fun `legal entity is not withheld and is credited gross`() {
        val result = compute(
            "1000",
            profile = TaxProfile(TaxpayerType.LEGAL_ENTITY, TaxResidency.RESIDENT)
        )

        assertThat(result.treatment).isEqualTo(WithholdingTreatment.NOT_WITHHELD)
        assertThat(result.taxAmount).isEqualByComparingTo("0")
        assertThat(result.netAmount).isEqualByComparingTo("1000")
        assertThat(result.taxableBase).isEqualByComparingTo("0")
    }

    @Test
    fun `an exempt code yields EXEMPT credited gross with the code recorded`() {
        val result = compute(
            "1000",
            profile = TaxProfile(
                TaxpayerType.INDIVIDUAL,
                TaxResidency.RESIDENT,
                exemptCode = "TREATY_ART11_ZERO"
            )
        )

        assertThat(result.treatment).isEqualTo(WithholdingTreatment.EXEMPT)
        assertThat(result.taxAmount).isEqualByComparingTo("0")
        assertThat(result.netAmount).isEqualByComparingTo("1000")
        assertThat(result.exemptCode).isEqualTo("TREATY_ART11_ZERO")
    }

    @Test
    fun `exemption takes precedence over the individual withholding path`() {
        // A resident individual WITH an exempt code must not be withheld.
        val result = compute(
            "1000",
            profile = TaxProfile.FAIL_SAFE_DEFAULT.copy(exemptCode = "STATUTORY_X")
        )

        assertThat(result.treatment).isEqualTo(WithholdingTreatment.EXEMPT)
        assertThat(result.taxAmount).isEqualByComparingTo("0")
    }

    @Test
    fun `non-CZK interest is deferred and not withheld`() {
        val result = compute("1000.0000", currency = "EUR")

        assertThat(result.treatment).isEqualTo(WithholdingTreatment.DEFERRED_FX)
        assertThat(result.taxAmount).isEqualByComparingTo("0")
        assertThat(result.netAmount).isEqualByComparingTo("1000.0000")
    }

    @Test
    fun `currency match is case-insensitive`() {
        val result = compute("1000", currency = "czk")

        assertThat(result.treatment).isEqualTo(WithholdingTreatment.WITHHELD)
        assertThat(result.taxAmount).isEqualByComparingTo("150")
    }

    @Test
    fun `the fail-safe default withholds at the resident individual rate`() {
        // ADR-0033 §C: an unresolved profile must never under-withhold.
        val result = compute("1000", profile = TaxProfile.FAIL_SAFE_DEFAULT)

        assertThat(result.treatment).isEqualTo(WithholdingTreatment.WITHHELD)
        assertThat(result.rate).isEqualByComparingTo("0.15")
        assertThat(result.taxAmount).isEqualByComparingTo("150")
    }

    @Test
    fun `zero gross interest produces zero tax`() {
        val result = compute("0.0000")

        assertThat(result.taxAmount).isEqualByComparingTo("0")
        assertThat(result.netAmount).isEqualByComparingTo("0.0000")
        assertThat(result.treatment).isEqualTo(WithholdingTreatment.WITHHELD)
    }

    @Test
    fun `sub-CZK interest floors the base to zero and withholds nothing`() {
        // 0.9999 CZK gross -> base floors to 0 -> tax 0 -> net unchanged.
        val result = compute("0.9999")

        assertThat(result.taxableBase).isEqualByComparingTo("0")
        assertThat(result.taxAmount).isEqualByComparingTo("0")
        assertThat(result.netAmount).isEqualByComparingTo("0.9999")
    }
}
