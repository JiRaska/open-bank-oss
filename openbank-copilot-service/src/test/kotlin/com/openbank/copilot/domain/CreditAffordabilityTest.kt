// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * ADR-0269 rule 5, L1.
 *
 * The defining property of the advisor is that it can say NO, so most of these tests are refusals.
 * A test suite that only checked the happy path would pass just as well on an advisor that always
 * says yes — which is exactly the failure this level exists to prevent.
 */
class CreditAffordabilityTest {

    private fun assess(income: String?, obligations: String?, instalment: String, net: String? = null) =
        CreditAffordability.assess(
            income?.let { BigDecimal(it) },
            obligations?.let { BigDecimal(it) },
            net?.let { BigDecimal(it) },
            BigDecimal(instalment),
        )

    @Test
    fun `a small instalment on a clean income is comfortable`() {
        val a = assess("50000", "0", "5000")
        assertThat(a.verdict).isEqualTo(AffordabilityVerdict.COMFORTABLE)
        assertThat(a.reasons).containsExactly("FITS")
    }

    @Test
    fun `an instalment that pushes debt service past the limit is refused`() {
        val a = assess("50000", "15000", "10000") // 25000/50000 = 0.50
        assertThat(a.verdict).isEqualTo(AffordabilityVerdict.UNAFFORDABLE)
        assertThat(a.reasons).contains("DSTI_ABOVE_LIMIT")
    }

    @Test
    fun `an instalment that leaves nothing over is refused even when DSTI passes`() {
        // 50,000 income, 5,000 of existing debt service, but only 6,000 actually left over after
        // rent and living costs. DSTI after a 6,000 instalment is 0.22 — comfortably inside the
        // limit — and the customer still ends the month at zero.
        //
        // This case is why the surplus rule measures the profile's NET and not income minus debt
        // service: the first version of this test could not fail, because inside the DSTI limit
        // income-minus-debt-service is always positive. The rule was dead code until the number it
        // reads changed.
        val a = assess(income = "50000", obligations = "5000", instalment = "6000", net = "6000")
        assertThat(a.verdict).isEqualTo(AffordabilityVerdict.UNAFFORDABLE)
        assertThat(a.reasons).contains("NO_MONTHLY_SURPLUS")
    }

    @Test
    fun `an unknown net is skipped, not treated as a surplus of zero`() {
        // Without the profile's net the second question simply cannot be asked. Treating null as 0
        // would refuse every customer whose spending the bank has not observed.
        val a = assess(income = "50000", obligations = "0", instalment = "5000", net = null)
        assertThat(a.verdict).isEqualTo(AffordabilityVerdict.COMFORTABLE)
        assertThat(a.monthlySurplusAfter).isNull()
    }

    @Test
    fun `an instalment inside the limit but past comfort is called tight, not refused`() {
        val a = assess("50000", "10000", "10000") // 0.40
        assertThat(a.verdict).isEqualTo(AffordabilityVerdict.TIGHT)
        assertThat(a.reasons).contains("DSTI_TIGHT")
    }

    @Test
    fun `unknown income answers UNKNOWN, never a cheerful yes`() {
        assertThat(assess(null, "0", "5000").verdict).isEqualTo(AffordabilityVerdict.UNKNOWN)
        assertThat(assess("0", "0", "5000").verdict).isEqualTo(AffordabilityVerdict.UNKNOWN)
    }

    @Test
    fun `an unknown answer carries no numbers to be mistaken for a calculation`() {
        val a = assess(null, null, "5000")
        assertThat(a.dstiAfter).isNull()
        assertThat(a.monthlySurplusAfter).isNull()
        assertThat(a.reasons).containsExactly("INCOME_UNKNOWN")
    }

    @Test
    fun `every answer carries at least one machine-readable reason`() {
        val cases = listOf(
            assess("50000", "0", "5000"),
            assess("50000", "15000", "10000"),
            assess("50000", "5000", "6000", net = "6000"),
            assess("50000", "10000", "10000"),
            assess(null, null, "1"),
        )
        assertThat(cases).allSatisfy { assertThat(it.reasons).isNotEmpty() }
    }

    @Test
    fun `missing obligations are treated as zero, not as unknown`() {
        // Deliberate asymmetry with income: an absent obligation list means the bank knows of no
        // debts, which is a real state. An absent income means the bank cannot see earnings, which
        // is not.
        val a = assess("50000", null, "5000")
        assertThat(a.verdict).isEqualTo(AffordabilityVerdict.COMFORTABLE)
    }

    @Test
    fun `the workings are returned so narration has nothing left to invent`() {
        val a = assess("40000", "4000", "6000")
        assertThat(a.dstiAfter).isEqualByComparingTo("0.2500")
        assertThat(a.monthlySurplusAfter).isNull() // no net supplied — nothing invented in its place
    }
}
