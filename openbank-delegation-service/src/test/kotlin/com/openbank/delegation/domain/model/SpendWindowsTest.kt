// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * The windows are Europe/Prague calendar windows, not UTC ones — see [SpendWindows.ZONE]. The two
 * tests below are the difference: in summer Prague is UTC+2, so 23:30 UTC is already tomorrow
 * locally, and a UTC-keyed counter would hand the delegate a second full daily ceiling at what the
 * customer calls the middle of the night.
 */
class SpendWindowsTest {

    @Test
    fun `the daily window starts at local midnight, not at UTC midnight`() {
        val window = SpendWindows.windowAt(OffsetDateTime.parse("2026-08-08T12:00:00Z"))

        // 2026-08-08 00:00 Prague (UTC+2) == 2026-08-07T22:00Z
        assertThat(window.dayStart.toInstant()).isEqualTo(OffsetDateTime.parse("2026-08-07T22:00:00Z").toInstant())
    }

    @Test
    fun `an instant late in the UTC evening already belongs to the next local day`() {
        val lateEveningUtc = OffsetDateTime.parse("2026-08-08T23:30:00Z")

        val window = SpendWindows.windowAt(lateEveningUtc)

        // Locally it is 2026-08-09 01:30, so the day started at 2026-08-08T22:00Z.
        assertThat(window.dayStart.toInstant()).isEqualTo(OffsetDateTime.parse("2026-08-08T22:00:00Z").toInstant())
    }

    @Test
    fun `the monthly window starts at local midnight on the first of the local month`() {
        val window = SpendWindows.windowAt(OffsetDateTime.parse("2026-08-08T12:00:00Z"))

        assertThat(window.monthStart.toInstant()).isEqualTo(OffsetDateTime.parse("2026-07-31T22:00:00Z").toInstant())
    }

    /** Winter is UTC+1, so the same computation must not hard-code a two-hour offset. */
    @Test
    fun `the window follows the local offset across daylight saving`() {
        val window = SpendWindows.windowAt(OffsetDateTime.parse("2026-01-15T12:00:00Z"))

        assertThat(window.dayStart.toInstant()).isEqualTo(OffsetDateTime.parse("2026-01-14T23:00:00Z").toInstant())
        assertThat(window.monthStart.toInstant()).isEqualTo(OffsetDateTime.parse("2025-12-31T23:00:00Z").toInstant())
    }
}
