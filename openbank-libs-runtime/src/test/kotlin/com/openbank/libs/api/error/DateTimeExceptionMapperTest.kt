// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.api.error

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.DateTimeException
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeParseException

/**
 * The gap this closes is a type-hierarchy detail, so the tests are about the hierarchy.
 *
 * `DateTimeParseException` extends `DateTimeException` extends `RuntimeException` — **not**
 * `IllegalArgumentException`, which is what everyone assumes. So it never matched
 * [IllegalArgumentExceptionMapper] and fell through to [GenericExceptionMapper] as a 500. The first
 * full authenticated-fuzz run hit that on five money-path endpoints (#3038) with inputs as ordinary
 * as `?date=` and `?date=null`.
 */
class DateTimeExceptionMapperTest {

    private val mapper = DateTimeExceptionMapper()

    @Test
    fun `the assumption that made this a 500 — it is NOT an IllegalArgumentException`() {
        val thrown = runCatching { LocalDate.parse("") }.exceptionOrNull()!!

        assertThat(thrown).isInstanceOf(DateTimeParseException::class.java)
        assertThat(thrown).isInstanceOf(DateTimeException::class.java)
        assertThat(thrown).isNotInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `an empty date query parameter maps to 400, not 500`() {
        val thrown = runCatching { LocalDate.parse("") }.exceptionOrNull() as DateTimeException

        assertThat(mapper.toResponse(thrown).status).isEqualTo(400)
    }

    @Test
    fun `the literal string null maps to 400`() {
        // `?date=null` is a non-null String, so a `date?.let { LocalDate.parse(it) }` runs and throws.
        val thrown = runCatching { LocalDate.parse("null") }.exceptionOrNull() as DateTimeException

        assertThat(mapper.toResponse(thrown).status).isEqualTo(400)
    }

    /** `?year=0` produced `Invalid value for MonthOfYear (valid values 1 - 12): 0` as a 500. */
    @Test
    fun `an out-of-range temporal value maps to 400`() {
        val thrown = runCatching { MonthDay.of(0, 1) }.exceptionOrNull() as DateTimeException

        val response = mapper.toResponse(thrown)

        assertThat(response.status).isEqualTo(400)
        assertThat(response.entity.toString()).contains("MonthOfYear")
    }

    @Test
    fun `a message-less exception still yields a usable body`() {
        assertThat(mapper.toResponse(DateTimeException(null)).status).isEqualTo(400)
    }
}
