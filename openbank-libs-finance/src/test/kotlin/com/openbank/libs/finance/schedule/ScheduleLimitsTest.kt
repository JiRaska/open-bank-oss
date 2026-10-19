// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.schedule

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ScheduleLimitsTest {
    private val start = LocalDate.parse("2026-01-01")

    @Test
    fun `a daily schedule over centuries is refused with a typed exception, in either direction`() {
        listOf(StubType.SHORT_FINAL, StubType.SHORT_INITIAL).forEach { stub ->
            assertThatThrownBy { DateGrid.boundaries(start, start.plusYears(500), Frequency.Days(1), stub, false) }
                .isInstanceOfSatisfying(ScheduleTooLongException::class.java) {
                    assertThat(it.limit).isEqualTo(ScheduleLimits.MAX_PERIODS)
                    assertThat(it).isInstanceOf(IllegalArgumentException::class.java)
                }
        }
    }

    @Test
    fun `exactly the limit is allowed, one more period is not`() {
        val atLimit = DateGrid.boundaries(
            start,
            start.plusDays(ScheduleLimits.MAX_PERIODS.toLong()),
            Frequency.Days(1),
            StubType.SHORT_FINAL,
            false,
        )
        assertThat(atLimit).hasSize(ScheduleLimits.MAX_PERIODS + 1)
        assertThatThrownBy {
            DateGrid.boundaries(
                start,
                start.plusDays(ScheduleLimits.MAX_PERIODS + 1L),
                Frequency.Days(1),
                StubType.SHORT_FINAL,
                false,
            )
        }.isInstanceOf(ScheduleTooLongException::class.java)
        // A real 50-year monthly leg (600 periods) is far inside the cap.
        assertThat(
            DateGrid.boundaries(start, start.plusYears(50), Frequency.MONTHLY, StubType.SHORT_INITIAL, false),
        ).hasSize(601)
    }
}
