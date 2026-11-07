// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.cashflow

import com.openbank.libs.domain.calendar.BusinessCalendar
import com.openbank.libs.finance.TARGET2
import com.openbank.libs.finance.bd
import com.openbank.libs.finance.calendar.Calendars
import com.openbank.libs.finance.d
import com.openbank.libs.finance.fixedLeg
import com.openbank.libs.finance.leg.AmortisationSpec
import com.openbank.libs.finance.leg.DateRules
import com.openbank.libs.finance.leg.FixingConvention
import com.openbank.libs.finance.leg.NotionalSpec
import com.openbank.libs.finance.leg.RateBounds
import com.openbank.libs.finance.leg.RateSpec
import com.openbank.libs.finance.schedule.Frequency
import com.openbank.libs.finance.schedule.ScheduleGenerator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ScheduleHashTest {
    private val base = fixedLeg("2026-01-15", "2027-01-15")

    private fun hash(leg: com.openbank.libs.finance.leg.LegSpec, fixings: List<UsedFixing> = emptyList()) =
        ScheduleHash.of(ScheduleGenerator.generate(leg), fixings)

    @Test
    fun `hash is deterministic, hex SHA-256, and canonical over decimal scale`() {
        val h = hash(base)
        assertThat(h).hasSize(64).matches("[0-9a-f]{64}")
        assertThat(hash(base)).isEqualTo(h)
        assertThat(hash(base.copy(rate = RateSpec.Fixed(bd("0.0360"))))).isEqualTo(h)
        assertThat(hash(base.copy(rate = RateSpec.Fixed(bd("0.0361"))))).isNotEqualTo(h)
        assertThat(
            ScheduleHash.canonical(ScheduleGenerator.generate(base), emptyList()),
        ).startsWith("v1|RECEIVE|EUR|1000000/BULLET")
    }

    @Test
    fun `a holiday inside the range changes the hash, one far outside does not`() {
        val inside = Calendars.joint(listOf(BusinessCalendar.target2()), setOf(d("2026-06-17")))
        val outside = Calendars.joint(listOf(BusinessCalendar.target2()), setOf(d("2030-06-17")))
        val h = hash(base)
        assertThat(hash(base.copy(dates = DateRules(inside)))).isNotEqualTo(h)
        assertThat(hash(base.copy(dates = DateRules(outside)))).isEqualTo(h)
    }

    @Test
    fun `fixings used and every rate field are hashed`() {
        val floating = base.copy(
            rate = RateSpec.Floating(
                "EURIBOR-3M",
                bd("0.001"),
                FixingConvention(2, TARGET2),
                Frequency.QUARTERLY,
                RateBounds(bd("0.05"), bd("0")),
                bd("0.02"),
            ),
        )
        val h = hash(floating)
        assertThat(hash(floating, listOf(UsedFixing("EURIBOR-3M", d("2026-04-13"), bd("0.021"))))).isNotEqualTo(h)
        assertThat(
            hash(
                floating.copy(
                    notional = NotionalSpec(
                        bd("1000000"),
                        AmortisationSpec.Custom(
                            List(4) {
                                bd("250000")
                            },
                        ),
                    ),
                ),
            ),
        ).isNotEqualTo(h)
        assertThat(
            hash(base.copy(notional = NotionalSpec(bd("1000000"), AmortisationSpec.Linear))),
        ).isNotEqualTo(hash(base))
        assertThat(
            hash(base.copy(notional = NotionalSpec(bd("1000000"), AmortisationSpec.Annuity))),
        ).isNotEqualTo(hash(base))
        assertThat(hash(base.copy(rate = RateSpec.None))).isNotEqualTo(hash(base))
        val projection = CashFlowProjector.project(ScheduleGenerator.generate(floating))
        assertThat(ScheduleHash.of(ScheduleGenerator.generate(floating), projection)).isEqualTo(h)
    }
}
