// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.identity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class RodneCisloTest {

    @Test
    fun `parses a valid 10-digit male number`() {
        val r = RodneCislo.parse("760506/0342")
        assertThat(r).isInstanceOf(RodneCislo.Parsed::class.java)
        r as RodneCislo.Parsed
        assertThat(r.canonical).isEqualTo("7605060342")
        assertThat(r.birthdate).isEqualTo(LocalDate.of(1976, 5, 6))
        assertThat(r.gender).isEqualTo(RodneCislo.Gender.MALE)
    }

    @Test
    fun `slash and spaces are normalized away`() {
        val a = RodneCislo.parse(" 760506 / 0342 ")
        val b = RodneCislo.parse("7605060342")
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `decodes female month offset of 50`() {
        val r = RodneCislo.parse("8555230453") as RodneCislo.Parsed
        assertThat(r.gender).isEqualTo(RodneCislo.Gender.FEMALE)
        assertThat(r.birthdate).isEqualTo(LocalDate.of(1985, 5, 23))
    }

    @Test
    fun `decodes post-2004 plus-20 overflow as a 2000s male`() {
        val r = RodneCislo.parse("0921150120") as RodneCislo.Parsed
        assertThat(r.gender).isEqualTo(RodneCislo.Gender.MALE)
        assertThat(r.birthdate).isEqualTo(LocalDate.of(2009, 1, 15))
    }

    @Test
    fun `applies the historical checksum-remainder-10 to check-digit-0 quirk`() {
        // firstNine % 11 == 10 → check digit must be 0
        assertThat(RodneCislo.isValid("5401010000")).isTrue()
    }

    @Test
    fun `nine-digit pre-1954 form has no checksum and maps to the 1900s`() {
        val r = RodneCislo.parse("480312123") as RodneCislo.Parsed
        assertThat(r.birthdate).isEqualTo(LocalDate.of(1948, 3, 12))
        assertThat(r.gender).isEqualTo(RodneCislo.Gender.MALE)
    }

    @Test
    fun `rejects a wrong check digit`() {
        val r = RodneCislo.parse("7605060343")
        assertThat(r).isInstanceOf(RodneCislo.Invalid::class.java)
        assertThat((r as RodneCislo.Invalid).reason).contains("checksum")
    }

    @Test
    fun `rejects an impossible date`() {
        assertThat(RodneCislo.parse("7602300342")).isInstanceOf(RodneCislo.Invalid::class.java)
    }

    @Test
    fun `rejects an out-of-range month component`() {
        assertThat(RodneCislo.parse("7613060342")).isInstanceOf(RodneCislo.Invalid::class.java)
    }

    @Test
    fun `rejects wrong length`() {
        assertThat(RodneCislo.parse("12345")).isInstanceOf(RodneCislo.Invalid::class.java)
    }

    @Test
    fun `rejects non-digit characters`() {
        assertThat(RodneCislo.parse("76050x0342")).isInstanceOf(RodneCislo.Invalid::class.java)
    }

    @Test
    fun `rejects empty input`() {
        assertThat(RodneCislo.parse("   ")).isInstanceOf(RodneCislo.Invalid::class.java)
    }

    // Never-issued numbers: structurally plausible, but no authority ever assigned them.

    @Test
    fun `rejects a nine-digit number for a birth year after 1953`() {
        val r = RodneCislo.parse("600101123")
        assertThat(r).isInstanceOf(RodneCislo.Invalid::class.java)
        assertThat((r as RodneCislo.Invalid).reason).contains("before 1954")
    }

    @Test
    fun `accepts a nine-digit number for 1953, the last year it was issued`() {
        assertThat(RodneCislo.isValid("530101123")).isTrue()
    }

    @Test
    fun `rejects the remainder-10 quirk for a birth year from 1986`() {
        val r = RodneCislo.parse("8601010100")
        assertThat(r).isInstanceOf(RodneCislo.Invalid::class.java)
        assertThat((r as RodneCislo.Invalid).reason).contains("1985")
    }

    @Test
    fun `accepts the remainder-10 quirk for 1985, the last year it was issued`() {
        assertThat(RodneCislo.isValid("8501010090")).isTrue()
    }

    @Test
    fun `rejects the plus-20 month overflow before 2004`() {
        val r = RodneCislo.parse("0121010010")
        assertThat(r).isInstanceOf(RodneCislo.Invalid::class.java)
        assertThat((r as RodneCislo.Invalid).reason).contains("2004")
    }

    @Test
    fun `accepts the plus-20 month overflow from 2004`() {
        val r = RodneCislo.parse("0421010007") as RodneCislo.Parsed
        assertThat(r.birthdate).isEqualTo(LocalDate.of(2004, 1, 1))
    }

    @Test
    fun `rejects a nine-digit number carrying the plus-20 overflow`() {
        assertThat(RodneCislo.parse("042101000")).isInstanceOf(RodneCislo.Invalid::class.java)
    }

    @Test
    fun `rejects non-ASCII digits so the blind index cannot fork`() {
        // Arabic-Indic digits for 7605060342: Char.isDigit() is true for them
        val arabic = "7605060342".map { (it - '0' + 0x0660).toChar() }.joinToString("")
        assertThat(RodneCislo.parse(arabic)).isInstanceOf(RodneCislo.Invalid::class.java)
    }

    // ── future-birthdate rule (overload with a Clock; business zone Europe/Prague) ──

    private fun clockAt(instant: String): Clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)

    private val today = LocalDate.of(2026, 11, 19)
    private val noon = clockAt("2026-11-19T12:00:00Z")

    // Synthetic checksum-valid number for [d], built from parts (no literal birth number in source).
    private fun tenDigit(d: LocalDate, checkOffset: Int = 0): String {
        val head = "%02d%02d%02d".format(d.year % 100, d.monthValue, d.dayOfMonth)
        for (suffix in 100..999) {
            val nine = head + suffix
            val r = (nine.toLong() % 11).toInt()
            if (r != 10) return nine + (r + checkOffset) % 10
        }
        error("no valid suffix")
    }

    private fun nineDigit(d: LocalDate): String =
        "%02d%02d%02d".format(d.year % 100, d.monthValue, d.dayOfMonth) + "123"

    @Test
    fun `10-digit number dated tomorrow is Invalid with the future reason`() {
        val r = RodneCislo.parse(tenDigit(today.plusDays(1)), noon)
        assertThat(r).isEqualTo(RodneCislo.Invalid("birthdate is in the future"))
        assertThat(RodneCislo.isValid(tenDigit(today.plusDays(1)), noon)).isFalse()
    }

    @Test
    fun `10-digit number dated today or in the distant past is Parsed`() {
        assertThat(RodneCislo.parse(tenDigit(today), noon)).isInstanceOf(RodneCislo.Parsed::class.java)
        assertThat(RodneCislo.parse(tenDigit(LocalDate.of(1960, 3, 4)), noon))
            .isInstanceOf(RodneCislo.Parsed::class.java)
        assertThat(RodneCislo.isValid(tenDigit(today), noon)).isTrue()
        assertThat(RodneCislo.isValid(tenDigit(LocalDate.of(1960, 3, 4)), noon)).isTrue()
    }

    @Test
    fun `9-digit number is judged against the clock too`() {
        // 9-digit numbers exist only for birth years up to 1953, so with a 1950 clock the date is future.
        val early = clockAt("1950-06-15T12:00:00Z")
        val earlyToday = LocalDate.of(1950, 6, 15)
        assertThat(RodneCislo.parse(nineDigit(earlyToday.plusDays(1)), early))
            .isEqualTo(RodneCislo.Invalid("birthdate is in the future"))
        assertThat(RodneCislo.parse(nineDigit(earlyToday), early)).isInstanceOf(RodneCislo.Parsed::class.java)
        assertThat(RodneCislo.parse(nineDigit(LocalDate.of(1930, 1, 2)), noon))
            .isInstanceOf(RodneCislo.Parsed::class.java)
    }

    @Test
    fun `winter - a child born in Prague today at 00-30 CET is valid while the UTC date is still yesterday`() {
        val clock = clockAt("2026-11-19T23:30:00Z") // 00:30 CET on 2026-11-20
        assertThat(RodneCislo.isValid(tenDigit(LocalDate.of(2026, 11, 20)), clock)).isTrue()
        assertThat(RodneCislo.parse(tenDigit(LocalDate.of(2026, 11, 21)), clock))
            .isEqualTo(RodneCislo.Invalid("birthdate is in the future"))
    }

    @Test
    fun `summer - a child born in Prague today at 00-30 CEST is valid while the UTC date is still yesterday`() {
        val clock = clockAt("2026-07-19T22:30:00Z") // 00:30 CEST on 2026-07-20
        assertThat(RodneCislo.isValid(tenDigit(LocalDate.of(2026, 7, 20)), clock)).isTrue()
        assertThat(RodneCislo.isValid(tenDigit(LocalDate.of(2026, 7, 21)), clock)).isFalse()
    }

    @Test
    fun `structure is checked before the date - a future number with a broken checksum keeps the checksum reason`() {
        val future = today.plusDays(1)
        assertThat(RodneCislo.parse(tenDigit(future, checkOffset = 1), noon))
            .isEqualTo(RodneCislo.Invalid("checksum mismatch"))
    }

    @Test
    fun `the one-argument form is structure only and still accepts a future birthdate`() {
        assertThat(RodneCislo.parse(tenDigit(LocalDate.of(2040, 5, 6)))).isInstanceOf(RodneCislo.Parsed::class.java)
        assertThat(RodneCislo.isValid(tenDigit(LocalDate.of(2040, 5, 6)))).isTrue()
    }
}
