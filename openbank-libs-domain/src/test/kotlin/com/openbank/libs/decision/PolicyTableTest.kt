// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.decision

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Effective-dating and version selection for credit policy tables (ADR-0213). The boundaries are
 * the whole contract: [PolicyTable.effectiveFrom] is inclusive and [PolicyTable.effectiveTo] is
 * exclusive, so a table replaced at midnight must not be active on the same day as its successor —
 * two active tables of one kind is how an evaluation silently pins the wrong version.
 */
class PolicyTableTest {

    private fun table(
        version: Int,
        from: LocalDate,
        to: LocalDate? = null,
        kind: PolicyTableKind = PolicyTableKind.ELIGIBILITY,
    ) = PolicyTable(
        kind = kind,
        name = "eligibility",
        version = version,
        effectiveFrom = from,
        effectiveTo = to,
        rules = listOf(
            PolicyRule(
                id = "R$version",
                attribute = PolicyAttribute.AGE_YEARS,
                operator = PolicyOperator.GTE,
                threshold = BigDecimal("18"),
            ),
        ),
    )

    private val jan1 = LocalDate.of(2026, 1, 1)
    private val feb1 = LocalDate.of(2026, 2, 1)

    @Test
    fun `effectiveFrom is inclusive - the table is active on its own start date`() {
        assertThat(table(1, jan1).isActive(jan1)).isTrue()
        assertThat(table(1, jan1).isActive(jan1.minusDays(1))).isFalse()
    }

    @Test
    fun `effectiveTo is exclusive - the table is dead on its end date, not the day after`() {
        val t = table(1, jan1, to = feb1)
        assertThat(t.isActive(feb1.minusDays(1))).isTrue()
        assertThat(t.isActive(feb1)).isFalse()
        assertThat(t.isActive(feb1.plusDays(1))).isFalse()
    }

    @Test
    fun `an open-ended table stays active indefinitely`() {
        val t = table(1, jan1)
        assertThat(t.isActive(jan1.plusYears(50))).isTrue()
    }

    @Test
    fun `a successor and its predecessor are never both active on the handover date`() {
        val old = table(1, jan1, to = feb1)
        val new = table(2, feb1)
        assertThat(old.isActive(feb1)).isFalse()
        assertThat(new.isActive(feb1)).isTrue()

        val bundle = PolicyBundle(listOf(old, new))
        assertThat(bundle.active(PolicyTableKind.ELIGIBILITY, feb1)?.version).isEqualTo(2)
        assertThat(bundle.active(PolicyTableKind.ELIGIBILITY, jan1)?.version).isEqualTo(1)
    }

    @Test
    fun `when two versions of a kind overlap the highest version wins, whatever the list order`() {
        val v1 = table(1, jan1)
        val v3 = table(3, jan1)
        val v2 = table(2, jan1)
        assertThat(PolicyBundle(listOf(v1, v3, v2)).active(PolicyTableKind.ELIGIBILITY, feb1)?.version).isEqualTo(3)
        assertThat(PolicyBundle(listOf(v3, v2, v1)).active(PolicyTableKind.ELIGIBILITY, feb1)?.version).isEqualTo(3)
    }

    @Test
    fun `active is scoped to the requested kind and never leaks another kind's table`() {
        val bundle = PolicyBundle(
            listOf(
                table(9, jan1, kind = PolicyTableKind.EXCLUSION),
                table(1, jan1, kind = PolicyTableKind.ELIGIBILITY),
            ),
        )
        assertThat(bundle.active(PolicyTableKind.ELIGIBILITY, feb1)?.version).isEqualTo(1)
        assertThat(bundle.active(PolicyTableKind.EXCLUSION, feb1)?.version).isEqualTo(9)
        assertThat(bundle.active(PolicyTableKind.AFFORDABILITY, feb1)).isNull()
    }

    @Test
    fun `a bundle with only a future table has nothing active - it must fail closed, not fall back`() {
        val bundle = PolicyBundle(listOf(table(2, feb1)))
        assertThat(bundle.active(PolicyTableKind.ELIGIBILITY, jan1)).isNull()
    }

    @Test
    fun `an empty bundle resolves nothing for every kind`() {
        val bundle = PolicyBundle(emptyList())
        PolicyTableKind.entries.forEach { assertThat(bundle.active(it, jan1)).isNull() }
    }

    @Test
    fun `a numeric value renders without trailing zeros and a text value verbatim`() {
        assertThat(PolicyValue.Numeric(BigDecimal("1200.00")).render()).isEqualTo("1200")
        assertThat(PolicyValue.Numeric(BigDecimal("0.4500")).render()).isEqualTo("0.45")
        assertThat(PolicyValue.Text("CZ").render()).isEqualTo("CZ")
    }

    @Test
    fun `numeric rendering never falls back to scientific notation`() {
        // stripTrailingZeros on a large integral value yields 1E+8 unless toPlainString is used —
        // an input snapshot hash over that string would then differ from the same value written flat.
        assertThat(PolicyValue.Numeric(BigDecimal("100000000")).render()).isEqualTo("100000000")
        assertThat(PolicyValue.Numeric(BigDecimal("0.00001")).render()).isEqualTo("0.00001")
    }

    @Test
    fun `the table kinds are the four documented ones, in evaluation order`() {
        assertThat(PolicyTableKind.entries).containsExactly(
            PolicyTableKind.EXCLUSION,
            PolicyTableKind.ELIGIBILITY,
            PolicyTableKind.AFFORDABILITY,
            PolicyTableKind.PRICING_BAND,
        )
    }
}
