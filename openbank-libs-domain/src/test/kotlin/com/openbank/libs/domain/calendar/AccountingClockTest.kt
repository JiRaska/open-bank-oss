// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.calendar

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class AccountingClockTest {

    @Nested
    inner class TheDefectThisTypeReplaces {

        /**
         * The bug ADR-0207 fixes, stated as a test: at 22:30 UTC in summer, a UTC clock says one
         * date and a Prague clock says the next. Both answers are individually plausible, which is
         * why nothing detected it for the life of the service — so this test asserts the
         * disagreement exists rather than pretending it is theoretical.
         */
        @Test
        fun `UTC and Prague disagree about the date for two hours a day in summer`() {
            val lateEvening = Instant.parse("2026-07-31T22:30:00Z")

            val utcDate = LocalDate.ofInstant(lateEvening, ZoneOffset.UTC)
            val pragueDate = LocalDate.ofInstant(lateEvening, ZoneId.of("Europe/Prague"))

            assertThat(utcDate).isEqualTo(LocalDate.of(2026, 7, 31))
            assertThat(pragueDate).isEqualTo(LocalDate.of(2026, 8, 1))
            assertThat(utcDate).isNotEqualTo(pragueDate)
        }

        @Test
        fun `the accounting clock answers with the bank zone regardless of the wall clock's zone`() {
            val lateEvening = Instant.parse("2026-07-31T22:30:00Z")

            // Same instant, two differently-zoned wall clocks — one accounting day.
            val fromUtcClock = AccountingClock.bank(Clock.fixed(lateEvening, ZoneOffset.UTC))
            val fromPragueClock = AccountingClock.bank(Clock.fixed(lateEvening, ZoneId.of("Europe/Prague")))

            assertThat(fromUtcClock.today()).isEqualTo(LocalDate.of(2026, 8, 1))
            assertThat(fromPragueClock.today()).isEqualTo(fromUtcClock.today())
        }
    }

    @Nested
    inner class DefaultCutoff {

        /**
         * Adopting this type must not move a single existing date, or the migration is a silent
         * repricing of history. With the default midnight cutoff the accounting day is exactly the
         * calendar date in the bank zone — which is what every existing caller already assumed.
         */
        @Test
        fun `midnight cutoff makes the accounting day the calendar day in the bank zone`() {
            listOf(
                "2026-07-31T00:00:00Z" to LocalDate.of(2026, 7, 31),
                "2026-07-31T12:00:00Z" to LocalDate.of(2026, 7, 31),
                "2026-07-31T21:59:59Z" to LocalDate.of(2026, 7, 31),
                "2026-07-31T22:00:00Z" to LocalDate.of(2026, 8, 1),
            ).forEach { (instant, expected) ->
                val clock = AccountingClock.bank(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC))
                assertThat(clock.today()).describedAs(instant).isEqualTo(expected)
            }
        }

        @Test
        fun `winter time shifts the rollover by an hour`() {
            // CET (UTC+1) in January: the day rolls at 23:00 UTC, not 22:00.
            fun at(instant: String) = AccountingClock.bank(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC))
            val beforeRollover = at("2026-01-15T22:59:59Z")
            val afterRollover = at("2026-01-15T23:00:00Z")

            assertThat(beforeRollover.today()).isEqualTo(LocalDate.of(2026, 1, 15))
            assertThat(afterRollover.today()).isEqualTo(LocalDate.of(2026, 1, 16))
        }
    }

    @Nested
    inner class OperationalCutoff {

        @Test
        fun `a later cutoff books post-midnight work to the previous accounting day`() {
            val cutoffAtOne = AccountingClock(
                Clock.fixed(Instant.parse("2026-07-31T22:30:00Z"), ZoneOffset.UTC), // 00:30 Prague, 1 Aug
                AccountingClock.BANK_ZONE,
                LocalTime.of(1, 0),
            )

            // 00:30 Prague on 1 August is before the 01:00 cutoff, so it is still 31 July's book.
            assertThat(cutoffAtOne.today()).isEqualTo(LocalDate.of(2026, 7, 31))
        }

        @Test
        fun `after the cutoff the new accounting day has started`() {
            val cutoffAtOne = AccountingClock(
                Clock.fixed(Instant.parse("2026-07-31T23:30:00Z"), ZoneOffset.UTC), // 01:30 Prague, 1 Aug
                AccountingClock.BANK_ZONE,
                LocalTime.of(1, 0),
            )

            assertThat(cutoffAtOne.today()).isEqualTo(LocalDate.of(2026, 8, 1))
        }
    }

    @Nested
    inner class FutureDates {

        @Test
        fun `a date after today is future, today and yesterday are not`() {
            val clock = AccountingClock.bank(Clock.fixed(Instant.parse("2026-07-31T09:00:00Z"), ZoneOffset.UTC))

            assertThat(clock.isFuture(LocalDate.of(2026, 8, 1))).isTrue()
            assertThat(clock.isFuture(LocalDate.of(2026, 7, 31))).isFalse()
            assertThat(clock.isFuture(LocalDate.of(2026, 7, 30))).isFalse()
        }
    }
}
