// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.calendar

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The Meeus/Jones/Butcher algorithm drives Good Friday and Easter Monday on both the TARGET2 (EUR)
 * and CERTIS (CZK) calendars, so an off-by-one here silently moves two bank holidays a year. The
 * dates below are the published Western Easter Sundays, not values read back off the implementation.
 */
class EasterTest {

    @Test
    fun `computes the published Western Easter Sunday for a spread of years`() {
        val expected = mapOf(
            1997 to LocalDate.of(1997, 3, 30),
            2000 to LocalDate.of(2000, 4, 23),
            2008 to LocalDate.of(2008, 3, 23), // earliest in the modern era
            2011 to LocalDate.of(2011, 4, 24),
            2018 to LocalDate.of(2018, 4, 1),
            2024 to LocalDate.of(2024, 3, 31),
            2025 to LocalDate.of(2025, 4, 20),
            2026 to LocalDate.of(2026, 4, 5),
            2027 to LocalDate.of(2027, 3, 28),
            2030 to LocalDate.of(2030, 4, 21),
            2038 to LocalDate.of(2038, 4, 25), // latest possible date
        )
        expected.forEach { (year, date) -> assertThat(Easter.sunday(year)).isEqualTo(date) }
    }

    @Test
    fun `Easter Sunday always falls on a Sunday, across a century`() {
        (1970..2070).forEach { year ->
            assertThat(Easter.sunday(year).dayOfWeek)
                .describedAs("Easter %d", year)
                .isEqualTo(DayOfWeek.SUNDAY)
        }
    }

    @Test
    fun `Easter Sunday is always inside the canonical 22 March to 25 April window`() {
        (1970..2070).forEach { year ->
            val d = Easter.sunday(year)
            assertThat(d).isBetween(LocalDate.of(year, 3, 22), LocalDate.of(year, 4, 25))
        }
    }

    @Test
    fun `Good Friday is two days before and lands on a Friday`() {
        assertThat(Easter.goodFriday(2026)).isEqualTo(LocalDate.of(2026, 4, 3))
        (2020..2040).forEach { year ->
            assertThat(Easter.goodFriday(year)).isEqualTo(Easter.sunday(year).minusDays(2))
            assertThat(Easter.goodFriday(year).dayOfWeek).isEqualTo(DayOfWeek.FRIDAY)
        }
    }

    @Test
    fun `Easter Monday is the day after and lands on a Monday`() {
        assertThat(Easter.monday(2026)).isEqualTo(LocalDate.of(2026, 4, 6))
        (2020..2040).forEach { year ->
            assertThat(Easter.monday(year).dayOfWeek).isEqualTo(DayOfWeek.MONDAY)
        }
    }

    @Test
    fun `Good Friday can cross the month boundary backwards from an early April Easter`() {
        // 2018: Easter Sunday 1 April, so Good Friday is 30 March — the case a naive
        // same-month assumption gets wrong.
        assertThat(Easter.sunday(2018)).isEqualTo(LocalDate.of(2018, 4, 1))
        assertThat(Easter.goodFriday(2018)).isEqualTo(LocalDate.of(2018, 3, 30))
    }
}
