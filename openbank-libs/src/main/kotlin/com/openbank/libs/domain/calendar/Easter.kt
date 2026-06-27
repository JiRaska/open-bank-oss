// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.calendar

import java.time.LocalDate

/**
 * Gregorian Easter computation (Meeus/Jones/Butcher "Anonymous Gregorian algorithm").
 *
 * Western Easter drives the movable bank holidays used by both the TARGET2 (EUR) and
 * CERTIS (CZK) calendars: Good Friday (Easter Sunday − 2) and Easter Monday (Easter Sunday + 1).
 */
internal object Easter {

    fun sunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1
        return LocalDate.of(year, month, day)
    }

    fun goodFriday(year: Int): LocalDate = sunday(year).minusDays(2)

    fun monday(year: Int): LocalDate = sunday(year).plusDays(1)
}
