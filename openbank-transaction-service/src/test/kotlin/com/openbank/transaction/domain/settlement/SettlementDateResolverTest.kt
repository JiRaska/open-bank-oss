// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.domain.settlement

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class SettlementDateResolverTest {

    // 2026 weekday anchors used below:
    //   Wed 2026-06-03, Fri 2026-06-05, Sat 2026-06-06, Mon 2026-06-08, Sat 2026-06-13, Mon 2026-06-15
    //   Fri 2026-05-01 (Labour Day, TARGET2 holiday), Mon 2026-05-04, Sun 2026-05-31

    /** An [java.time.Instant] for the given wall-clock time in the bank zone (Europe/Prague). */
    private fun pragueInstant(date: LocalDate, time: LocalTime) =
        LocalDateTime.of(date, time).atZone(SettlementDateResolver.BANK_ZONE).toInstant()

    @Nested
    inner class SameCurrencyBooking {
        @Test
        fun `before cut-off books and values today`() {
            val dates = SettlementDateResolver.resolve(
                now = pragueInstant(LocalDate.of(2026, 6, 3), LocalTime.of(10, 0)),
                paymentCurrency = "CZK",
                settlementCurrency = "CZK",
            )
            assertThat(dates.bookingDate).isEqualTo(LocalDate.of(2026, 6, 3))
            assertThat(dates.valueDate).isEqualTo(LocalDate.of(2026, 6, 3))
        }

        @Test
        fun `at or after cut-off rolls to the next business day`() {
            // Exactly 16:00 counts as after cut-off.
            val dates = SettlementDateResolver.resolve(
                now = pragueInstant(LocalDate.of(2026, 6, 3), LocalTime.of(16, 0)),
                paymentCurrency = "CZK",
                settlementCurrency = "CZK",
            )
            assertThat(dates.bookingDate).isEqualTo(LocalDate.of(2026, 6, 4))
            assertThat(dates.valueDate).isEqualTo(LocalDate.of(2026, 6, 4))
        }

        @Test
        fun `Friday after cut-off rolls across the weekend to Monday`() {
            val dates = SettlementDateResolver.resolve(
                now = pragueInstant(LocalDate.of(2026, 6, 5), LocalTime.of(17, 0)),
                paymentCurrency = "CZK",
                settlementCurrency = "CZK",
            )
            assertThat(dates.bookingDate).isEqualTo(LocalDate.of(2026, 6, 8))
            assertThat(dates.valueDate).isEqualTo(LocalDate.of(2026, 6, 8))
        }

        @Test
        fun `settlement-calendar holiday rolls booking forward`() {
            // Fri 2026-05-01 is Labour Day (TARGET2 holiday) -> next TARGET2 business day is Mon 05-04.
            val dates = SettlementDateResolver.resolve(
                now = pragueInstant(LocalDate.of(2026, 5, 1), LocalTime.of(10, 0)),
                paymentCurrency = "EUR",
                settlementCurrency = "EUR",
            )
            assertThat(dates.bookingDate).isEqualTo(LocalDate.of(2026, 5, 4))
            assertThat(dates.valueDate).isEqualTo(LocalDate.of(2026, 5, 4))
        }
    }

    @Nested
    inner class CrossCurrencyValueDate {
        @Test
        fun `EUR to CZK settles spot T+2 in joint business days`() {
            // Booking Wed 06-03; +2 joint TARGET2/CERTIS business days -> Thu 06-04, Fri 06-05.
            val dates = SettlementDateResolver.resolve(
                now = pragueInstant(LocalDate.of(2026, 6, 3), LocalTime.of(10, 0)),
                paymentCurrency = "EUR",
                settlementCurrency = "CZK",
            )
            assertThat(dates.bookingDate).isEqualTo(LocalDate.of(2026, 6, 3))
            assertThat(dates.valueDate).isEqualTo(LocalDate.of(2026, 6, 5))
        }
    }

    @Nested
    inner class RequestedValueDate {
        @Test
        fun `a future requested date is honoured`() {
            val dates = SettlementDateResolver.resolve(
                now = pragueInstant(LocalDate.of(2026, 6, 3), LocalTime.of(10, 0)),
                paymentCurrency = "CZK",
                settlementCurrency = "CZK",
                requestedValueDate = LocalDate.of(2026, 6, 10),
            )
            assertThat(dates.valueDate).isEqualTo(LocalDate.of(2026, 6, 10))
        }

        @Test
        fun `a requested date earlier than the earliest is bumped up`() {
            val dates = SettlementDateResolver.resolve(
                now = pragueInstant(LocalDate.of(2026, 6, 3), LocalTime.of(10, 0)),
                paymentCurrency = "CZK",
                settlementCurrency = "CZK",
                requestedValueDate = LocalDate.of(2026, 6, 1),
            )
            assertThat(dates.valueDate).isEqualTo(LocalDate.of(2026, 6, 3))
        }

        @Test
        fun `a requested weekend date is rolled FOLLOWING to a business day`() {
            // Requested Sat 2026-06-13 -> rolls to Mon 2026-06-15 (after the earliest 06-03).
            val dates = SettlementDateResolver.resolve(
                now = pragueInstant(LocalDate.of(2026, 6, 3), LocalTime.of(10, 0)),
                paymentCurrency = "CZK",
                settlementCurrency = "CZK",
                requestedValueDate = LocalDate.of(2026, 6, 13),
            )
            assertThat(dates.valueDate).isEqualTo(LocalDate.of(2026, 6, 15))
        }
    }
}
