// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.calendar

import com.openbank.libs.domain.calendar.BusinessCalendar
import com.openbank.libs.finance.CERTIS
import com.openbank.libs.finance.TARGET2
import com.openbank.libs.finance.WEEKENDS
import com.openbank.libs.finance.d
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class FinanceCalendarTest {
    private val joint = Calendars.joint(BusinessCalendar.certis(), BusinessCalendar.target2())

    @Test
    fun `joint calendar is closed when any member is closed`() {
        // 2026-05-08 (Fri) Victory Day: CERTIS closed, TARGET2 open.
        assertThat(TARGET2.isBusinessDay(d("2026-05-08"))).isTrue()
        assertThat(CERTIS.isBusinessDay(d("2026-05-08"))).isFalse()
        assertThat(joint.isBusinessDay(d("2026-05-08"))).isFalse()
        // 2026-12-24 (Thu) Christmas Eve: CERTIS only.
        assertThat(TARGET2.isBusinessDay(d("2026-12-24"))).isTrue()
        assertThat(joint.isBusinessDay(d("2026-12-24"))).isFalse()
        // An ordinary Wednesday is open in both.
        assertThat(joint.isBusinessDay(d("2026-12-23"))).isTrue()
        assertThat(joint.name).isEqualTo("CERTIS+TARGET2")
        assertThat(Calendars.forCurrencies("CZK", "EUR").name).isEqualTo("CERTIS+TARGET2")
        assertThat(joint.toString()).contains("CERTIS+TARGET2")
    }

    @Test
    fun `duplicate members collapse and an empty member list is rejected`() {
        assertThat(Calendars.joint(BusinessCalendar.target2(), BusinessCalendar.target2()).name).isEqualTo("TARGET2")
        assertThatThrownBy { Calendars.joint(emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `bank-maintained extra holiday closes an otherwise open day`() {
        val cal = Calendars.joint(listOf(BusinessCalendar.target2()), setOf(d("2026-06-17")))
        assertThat(cal.isBusinessDay(d("2026-06-17"))).isFalse()
        assertThat(cal.roll(d("2026-06-17"), DateRoll.FOLLOWING)).isEqualTo(d("2026-06-18"))
        assertThat(cal.name).isEqualTo("TARGET2+EXTRA(2026-06-17)")
    }

    @ParameterizedTest(name = "{0} {1} on {2} -> {3}")
    @CsvSource(
        // Christmas 2026 on CERTIS: Thu 24, Fri 25, Sat 26, Sun 27 closed -> Mon 28.
        "2026-12-24, FOLLOWING, CERTIS, 2026-12-28",
        "2026-12-24, MODIFIED_FOLLOWING, CERTIS, 2026-12-28",
        "2026-12-24, PRECEDING, CERTIS, 2026-12-23",
        "2026-12-24, FOLLOWING, TARGET2, 2026-12-24",
        "2026-12-25, FOLLOWING, TARGET2, 2026-12-28",
        // Easter 2026: Good Friday 3 Apr, Easter Monday 6 Apr.
        "2026-04-03, FOLLOWING, TARGET2, 2026-04-07",
        "2026-04-06, PRECEDING, TARGET2, 2026-04-02",
        // Month-end: Sat 31 Oct 2026 following crosses into November -> back to Fri 30 Oct.
        "2026-10-31, FOLLOWING, WEEKENDS, 2026-11-02",
        "2026-10-31, MODIFIED_FOLLOWING, WEEKENDS, 2026-10-30",
        // Sun 1 Nov 2026 preceding crosses into October -> forward to Mon 2 Nov.
        "2026-11-01, PRECEDING, WEEKENDS, 2026-10-30",
        "2026-11-01, MODIFIED_PRECEDING, WEEKENDS, 2026-11-02",
        "2026-11-01, UNADJUSTED, WEEKENDS, 2026-11-01",
        "2026-11-03, MODIFIED_PRECEDING, WEEKENDS, 2026-11-03",
    )
    fun `rolls per convention`(date: String, roll: DateRoll, cal: String, expected: String) {
        val calendar = when (cal) {
            "CERTIS" -> CERTIS
            "TARGET2" -> TARGET2
            else -> WEEKENDS
        }
        assertThat(calendar.roll(d(date), roll)).isEqualTo(d(expected))
    }

    @Test
    fun `adds business days in both directions across Easter`() {
        // Tue 7 Apr 2026 minus 2 TARGET2 days: Mon 6 and Fri 3 closed -> Thu 2 (1), Wed 1 (2).
        assertThat(TARGET2.addBusinessDays(d("2026-04-07"), -2)).isEqualTo(d("2026-04-01"))
        assertThat(TARGET2.addBusinessDays(d("2026-04-02"), 1)).isEqualTo(d("2026-04-07"))
        assertThat(TARGET2.addBusinessDays(d("2026-04-04"), 0)).isEqualTo(d("2026-04-04"))
        // Sun 2026-03-15 plus one business day is Mon 16 Mar, the first business day after it.
        assertThat(WEEKENDS.addBusinessDays(d("2026-03-15"), 1)).isEqualTo(d("2026-03-16"))
    }

    @Test
    fun `fingerprint lists closed weekdays in range and is stable`() {
        assertThat(CERTIS.fingerprint(d("2026-12-20"), d("2026-12-31")))
            .isEqualTo("CERTIS[2026-12-24,2026-12-25]")
        assertThat(joint.fingerprint(d("2026-05-01"), d("2026-05-10")))
            .isEqualTo("CERTIS+TARGET2[2026-05-01,2026-05-08]")
    }
}
