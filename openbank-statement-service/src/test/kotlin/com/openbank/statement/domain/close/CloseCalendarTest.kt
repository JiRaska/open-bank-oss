// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.domain.close

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Pure month-window arithmetic for the self-healing catch-up close (ADR-0069 D3 / issue #470). */
class CloseCalendarTest {

    @Test
    fun `prior month bounds span the full preceding calendar month`() {
        val (from, to) = CloseCalendar.priorMonthBounds(LocalDate.parse("2026-02-15"))
        assertThat(from).isEqualTo(LocalDate.parse("2026-01-01"))
        assertThat(to).isEqualTo(LocalDate.parse("2026-01-31"))
    }

    @Test
    fun `prior month bounds cross a year boundary`() {
        val (from, to) = CloseCalendar.priorMonthBounds(LocalDate.parse("2026-01-03"))
        assertThat(from).isEqualTo(LocalDate.parse("2025-12-01"))
        assertThat(to).isEqualTo(LocalDate.parse("2025-12-31"))
    }

    @Test
    fun `prior month bounds handle a leap February`() {
        val (from, to) = CloseCalendar.priorMonthBounds(LocalDate.parse("2024-03-10"))
        assertThat(from).isEqualTo(LocalDate.parse("2024-02-01"))
        assertThat(to).isEqualTo(LocalDate.parse("2024-02-29"))
    }

    @Test
    fun `never-closed pocket gets exactly the through month`() {
        val windows = CloseCalendar.monthsToClose(null, LocalDate.parse("2026-03-31"))
        assertThat(windows).containsExactly(
            LocalDate.parse("2026-03-01") to LocalDate.parse("2026-03-31"),
        )
    }

    @Test
    fun `already-current pocket owes nothing`() {
        // Last close == the through month → empty.
        val windows = CloseCalendar.monthsToClose(LocalDate.parse("2026-03-31"), LocalDate.parse("2026-03-31"))
        assertThat(windows).isEmpty()
    }

    @Test
    fun `a single missed month is healed`() {
        // Last closed Jan; through March → owes Feb and March.
        val windows = CloseCalendar.monthsToClose(LocalDate.parse("2026-01-31"), LocalDate.parse("2026-03-31"))
        assertThat(windows).containsExactly(
            LocalDate.parse("2026-02-01") to LocalDate.parse("2026-02-28"),
            LocalDate.parse("2026-03-01") to LocalDate.parse("2026-03-31"),
        )
    }

    @Test
    fun `catch-up spans a year boundary and leap February`() {
        val windows = CloseCalendar.monthsToClose(LocalDate.parse("2023-12-31"), LocalDate.parse("2024-02-29"))
        assertThat(windows).containsExactly(
            LocalDate.parse("2024-01-01") to LocalDate.parse("2024-01-31"),
            LocalDate.parse("2024-02-01") to LocalDate.parse("2024-02-29"),
        )
    }

    @Test
    fun `a large gap is capped at the lookback bound, keeping the OLDEST months`() {
        // Last closed Jan 2024; through Mar 2026 (26 months owed) capped to the 12 oldest. Keeping
        // the oldest is what lets successive runs heal the whole gap: the close cursor only moves
        // forward, so months dropped from the newest end would just wait for the next run, whereas
        // months dropped from the oldest end would be stranded forever.
        val windows = CloseCalendar.monthsToClose(
            LocalDate.parse("2024-01-31"),
            LocalDate.parse("2026-03-31"),
            maxLookbackMonths = 12,
        )
        assertThat(windows).hasSize(12)
        assertThat(windows.first()).isEqualTo(LocalDate.parse("2024-02-01") to LocalDate.parse("2024-02-29"))
        assertThat(windows.last()).isEqualTo(LocalDate.parse("2025-01-01") to LocalDate.parse("2025-01-31"))
    }

    @Test
    fun `successive capped runs converge on the full gap`() {
        // Simulate the healing loop: after the first capped run closes through Jan 2025, the next
        // run enumerates from there and reaches the months the cap deferred.
        val first = CloseCalendar.monthsToClose(
            LocalDate.parse("2024-01-31"),
            LocalDate.parse("2026-03-31"),
            maxLookbackMonths = 12,
        )
        val second = CloseCalendar.monthsToClose(first.last().second, LocalDate.parse("2026-03-31"), 12)
        assertThat(second.first()).isEqualTo(LocalDate.parse("2025-02-01") to LocalDate.parse("2025-02-28"))
        assertThat(second.last()).isEqualTo(LocalDate.parse("2026-01-01") to LocalDate.parse("2026-01-31"))
        val third = CloseCalendar.monthsToClose(second.last().second, LocalDate.parse("2026-03-31"), 12)
        assertThat(third).containsExactly(
            LocalDate.parse("2026-02-01") to LocalDate.parse("2026-02-28"),
            LocalDate.parse("2026-03-01") to LocalDate.parse("2026-03-31"),
        )
    }
}
