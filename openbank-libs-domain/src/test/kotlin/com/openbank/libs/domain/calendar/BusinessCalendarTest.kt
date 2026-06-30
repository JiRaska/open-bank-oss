// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.calendar

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate

class BusinessCalendarTest {

    private val target2 = BusinessCalendar.target2()
    private val certis = BusinessCalendar.certis()

    @Nested
    inner class Weekends {
        @Test
        fun `Saturday and Sunday are never business days`() {
            assertThat(target2.isBusinessDay(LocalDate.of(2026, 6, 6))).isFalse() // Saturday
            assertThat(target2.isBusinessDay(LocalDate.of(2026, 6, 7))).isFalse() // Sunday
            assertThat(certis.isBusinessDay(LocalDate.of(2026, 6, 6))).isFalse()
            assertThat(target2.isBusinessDay(LocalDate.of(2026, 6, 8))).isTrue() // Monday
        }
    }

    @Nested
    inner class EasterDerivedHolidays {
        @Test
        fun `Good Friday and Easter Monday 2026 are holidays in both calendars`() {
            val goodFriday = LocalDate.of(2026, 4, 3)
            val easterMonday = LocalDate.of(2026, 4, 6)
            assertThat(target2.isHoliday(goodFriday)).isTrue()
            assertThat(target2.isHoliday(easterMonday)).isTrue()
            assertThat(certis.isHoliday(goodFriday)).isTrue()
            assertThat(certis.isHoliday(easterMonday)).isTrue()
        }

        @Test
        fun `Easter floats correctly across years`() {
            // Easter Sunday: 2024-03-31, 2025-04-20, 2026-04-05 -> Good Friday is two days prior
            assertThat(target2.isHoliday(LocalDate.of(2024, 3, 29))).isTrue()
            assertThat(target2.isHoliday(LocalDate.of(2025, 4, 18))).isTrue()
            assertThat(target2.isHoliday(LocalDate.of(2026, 4, 3))).isTrue()
        }
    }

    @Nested
    inner class Target2Holidays {
        @Test
        fun `TARGET2 closes only on its six fixed-or-Easter days`() {
            assertThat(target2.isBusinessDay(LocalDate.of(2026, 1, 1))).isFalse() // New Year
            assertThat(target2.isBusinessDay(LocalDate.of(2026, 5, 1))).isFalse() // Labour Day (Fri)
            assertThat(target2.isBusinessDay(LocalDate.of(2026, 12, 25))).isFalse() // Christmas (Fri)
            assertThat(target2.isBusinessDay(LocalDate.of(2026, 12, 24))).isTrue() // Christmas Eve is open
        }

        @Test
        fun `TARGET2 stays open on Czech-only public holidays`() {
            assertThat(target2.isBusinessDay(LocalDate.of(2026, 7, 6))).isTrue() // Jan Hus (Mon)
            assertThat(target2.isBusinessDay(LocalDate.of(2026, 9, 28))).isTrue() // Czech Statehood (Mon)
            assertThat(target2.isBusinessDay(LocalDate.of(2026, 5, 8))).isTrue() // Victory Day (Fri)
        }
    }

    @Nested
    inner class CertisHolidays {
        @Test
        fun `CERTIS closes on the full Czech public-holiday set`() {
            assertThat(certis.isBusinessDay(LocalDate.of(2026, 7, 6))).isFalse() // Jan Hus
            assertThat(certis.isBusinessDay(LocalDate.of(2026, 9, 28))).isFalse() // Czech Statehood
            assertThat(certis.isBusinessDay(LocalDate.of(2026, 10, 28))).isFalse() // Independence
            assertThat(certis.isBusinessDay(LocalDate.of(2026, 11, 17))).isFalse() // Freedom & Democracy
            assertThat(certis.isBusinessDay(LocalDate.of(2026, 12, 24))).isFalse() // Christmas Eve
        }
    }

    @Nested
    inner class BusinessDayArithmetic {
        @Test
        fun `nextBusinessDay skips the weekend`() {
            // Friday 2026-06-05 -> Monday 2026-06-08
            assertThat(certis.nextBusinessDay(LocalDate.of(2026, 6, 5))).isEqualTo(LocalDate.of(2026, 6, 8))
        }

        @Test
        fun `nextBusinessDay skips a holiday and weekend run`() {
            // Thursday 2026-04-30 -> 05-01 Labour Day (Fri), 05-02/03 weekend -> Monday 2026-05-04
            assertThat(target2.nextBusinessDay(LocalDate.of(2026, 4, 30))).isEqualTo(LocalDate.of(2026, 5, 4))
        }

        @Test
        fun `previousBusinessDay skips the weekend`() {
            // Monday 2026-06-08 -> Friday 2026-06-05
            assertThat(certis.previousBusinessDay(LocalDate.of(2026, 6, 8))).isEqualTo(LocalDate.of(2026, 6, 5))
        }

        @Test
        fun `addBusinessDays counts only business days`() {
            // From Wednesday 2026-06-03: +2 -> Friday 2026-06-05
            assertThat(certis.addBusinessDays(LocalDate.of(2026, 6, 3), 2)).isEqualTo(LocalDate.of(2026, 6, 5))
            // +3 crosses the weekend -> Monday 2026-06-08
            assertThat(certis.addBusinessDays(LocalDate.of(2026, 6, 3), 3)).isEqualTo(LocalDate.of(2026, 6, 8))
            // +0 is identity
            assertThat(certis.addBusinessDays(LocalDate.of(2026, 6, 3), 0)).isEqualTo(LocalDate.of(2026, 6, 3))
        }
    }

    @Nested
    inner class Rolling {
        @Test
        fun `FOLLOWING and PRECEDING roll off a weekend`() {
            val sunday = LocalDate.of(2026, 5, 31)
            assertThat(certis.roll(sunday, BusinessDayConvention.FOLLOWING)).isEqualTo(LocalDate.of(2026, 6, 1))
            assertThat(certis.roll(sunday, BusinessDayConvention.PRECEDING)).isEqualTo(LocalDate.of(2026, 5, 29))
        }

        @Test
        fun `MODIFIED_FOLLOWING stays inside the month`() {
            // Sunday 2026-05-31: FOLLOWING would jump to June, so MODIFIED_FOLLOWING falls back to Fri 05-29
            val sunday = LocalDate.of(2026, 5, 31)
            assertThat(
                certis.roll(sunday, BusinessDayConvention.MODIFIED_FOLLOWING),
            ).isEqualTo(LocalDate.of(2026, 5, 29))
        }

        @Test
        fun `roll is a no-op on a business day`() {
            val wednesday = LocalDate.of(2026, 6, 3)
            assertThat(certis.roll(wednesday, BusinessDayConvention.FOLLOWING)).isEqualTo(wednesday)
        }
    }

    @Nested
    inner class CurrencyRegistry {
        @Test
        fun `forCurrency maps EUR to TARGET2, CZK to CERTIS, others to weekend-only`() {
            assertThat(BusinessCalendar.forCurrency("EUR").name).isEqualTo("TARGET2")
            assertThat(BusinessCalendar.forCurrency("czk").name).isEqualTo("CERTIS")
            assertThat(BusinessCalendar.forCurrency("USD").name).isEqualTo("WEEKEND-ONLY")
        }

        @Test
        fun `weekend-only calendar treats public holidays as business days`() {
            val weekendOnly = BusinessCalendar.forCurrency("USD")
            assertThat(weekendOnly.isBusinessDay(LocalDate.of(2026, 12, 25))).isTrue()
            assertThat(weekendOnly.isBusinessDay(LocalDate.of(2026, 12, 26))).isFalse() // Saturday
        }
    }
}
