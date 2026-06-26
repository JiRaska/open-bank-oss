// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.anacredit.domain.eligibility

import com.openbank.anacredit.Fixtures
import com.openbank.anacredit.domain.model.CounterpartyType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class AnaCreditEligibilityPolicyTest {

    @Test
    fun `a legal-entity exposure at or above the threshold is reportable`() {
        val e = AnaCreditEligibilityPolicy.assess(Fixtures.exposure(), BigDecimal("40000"))
        assertThat(e).isEqualTo(Eligibility.Reportable)
    }

    @Test
    fun `a natural-person debtor is out of scope`() {
        val e = AnaCreditEligibilityPolicy.assess(
            Fixtures.exposure(debtorType = CounterpartyType.NATURAL_PERSON), BigDecimal("40000"),
        )
        assertThat(e).isInstanceOf(Eligibility.Excluded::class.java)
        assertThat((e as Eligibility.Excluded).reason).isEqualTo("HOUSEHOLD_OUT_OF_SCOPE")
    }

    @Test
    fun `a debtor below the 25k total commitment threshold is excluded`() {
        val e = AnaCreditEligibilityPolicy.assess(Fixtures.exposure(), BigDecimal("24999.99"))
        assertThat(e).isInstanceOf(Eligibility.Excluded::class.java)
        assertThat((e as Eligibility.Excluded).reason).isEqualTo("BELOW_THRESHOLD")
    }

    @Test
    fun `exactly 25k clears the threshold`() {
        val e = AnaCreditEligibilityPolicy.assess(Fixtures.exposure(), BigDecimal("25000"))
        assertThat(e).isEqualTo(Eligibility.Reportable)
    }

    @Test
    fun `an instrument with neither commitment nor drawing has nothing to report`() {
        val empty = Fixtures.exposure(committedAmount = BigDecimal.ZERO, drawnAmount = BigDecimal.ZERO)
        val e = AnaCreditEligibilityPolicy.assess(empty, BigDecimal("40000"))
        assertThat(e).isInstanceOf(Eligibility.Excluded::class.java)
        assertThat((e as Eligibility.Excluded).reason).isEqualTo("NO_EXPOSURE")
    }

    @Test
    fun `scope is checked before the threshold`() {
        // Natural person AND below threshold -> scope reason wins (it is evaluated first).
        val e = AnaCreditEligibilityPolicy.assess(
            Fixtures.exposure(debtorType = CounterpartyType.NATURAL_PERSON), BigDecimal("100"),
        )
        assertThat((e as Eligibility.Excluded).reason).isEqualTo("HOUSEHOLD_OUT_OF_SCOPE")
    }
}
