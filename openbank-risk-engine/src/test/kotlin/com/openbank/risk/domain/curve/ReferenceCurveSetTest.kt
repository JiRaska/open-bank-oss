// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.curve

import com.openbank.risk.domain.model.Provenance
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class ReferenceCurveSetTest {

    private val asOf = LocalDate.parse("2026-09-30")

    @Test
    fun `the reference set covers every index, so no floating loan is left without a curve`() {
        val set = ReferenceCurveSet.build(asOf, Instant.EPOCH)
        assertThat(set.curves.keys).containsExactlyInAnyOrderElementsOf(CurveIndex.entries)
        assertThat(set.discountCurveFor("CZK")).isNotNull
        assertThat(set.discountCurveFor("EUR")).isNotNull
    }

    @Test
    fun `it is labelled as demo data, never as production`() {
        val set = ReferenceCurveSet.build(asOf, Instant.EPOCH)
        assertThat(set.provenance).isEqualTo(Provenance.SYNTHETIC)
        assertThat(set.source).contains("NOT market data")
        assertThat(set.source.length).isLessThanOrEqualTo(256)
    }

    @Test
    fun `the id is a function of the date alone, so a second write for the same date collides`() {
        assertThat(ReferenceCurveSet.idFor(asOf)).isEqualTo(ReferenceCurveSet.idFor(LocalDate.parse("2026-09-30")))
        assertThat(ReferenceCurveSet.idFor(asOf)).isNotEqualTo(ReferenceCurveSet.idFor(asOf.plusDays(1)))
        assertThat(ReferenceCurveSet.build(asOf, Instant.EPOCH).id).isEqualTo(ReferenceCurveSet.idFor(asOf))
    }

    @Test
    fun `every quote is a plausible fraction, not a percentage typed as a number`() {
        ReferenceCurveSet.QUOTES.values.flatten().forEach {
            assertThat(it.simpleRate).isBetween(BigDecimal("0.001"), BigDecimal("0.10"))
        }
    }
}
