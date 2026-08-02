// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class TaxFilingTest {

    private val july = FilingPeriod(2026, 7)
    private val at = Instant.parse("2026-08-05T09:00:00Z")

    private fun assembled(by: String = "maker") = TaxFilingRecord.open(july, "CZK")
        .assemble(BigDecimal("12345"), remittanceCount = 3, itemCount = 480, by = by, at = at)

    @Nested
    inner class StatutoryDeadline {

        /**
         * §38d odst. 3: the return is due the last day of the month FOLLOWING the withholding
         * month — the same deadline ADR-0038's remittance policy uses for the payment, so the
         * return and the cash leg cannot describe different months.
         */
        @Test
        fun `the deadline is the last day of the following month`() {
            assertThat(FilingPeriod(2026, 7).dueDate).isEqualTo(LocalDate.of(2026, 8, 31))
            assertThat(FilingPeriod(2026, 1).dueDate).isEqualTo(LocalDate.of(2026, 2, 28))
            assertThat(FilingPeriod(2028, 1).dueDate).isEqualTo(LocalDate.of(2028, 2, 29))
            assertThat(FilingPeriod(2026, 11).dueDate).isEqualTo(LocalDate.of(2026, 12, 31))
        }

        @Test
        fun `a december period rolls into the next year`() {
            assertThat(FilingPeriod(2026, 12).dueDate).isEqualTo(LocalDate.of(2027, 1, 31))
        }

        @Test
        fun `period bounds are the calendar month`() {
            assertThat(july.firstDay).isEqualTo(LocalDate.of(2026, 7, 1))
            assertThat(july.lastDay).isEqualTo(LocalDate.of(2026, 7, 31))
            assertThat(july.label).isEqualTo("2026-07")
        }

        @Test
        fun `an out-of-range month is rejected`() {
            assertThatThrownBy { FilingPeriod(2026, 13) }
                .isInstanceOf(TaxValidationException::class.java)
                .hasMessageContaining("month out of range")
        }

        @Test
        fun `overdue means past the deadline and not filed`() {
            val open = TaxFilingRecord.open(july, "CZK")

            assertThat(open.isOverdueAt(LocalDate.of(2026, 8, 31))).isFalse()
            assertThat(open.isOverdueAt(LocalDate.of(2026, 9, 1))).isTrue()

            val filed = assembled().markFiled("FU-2026-07-001", "checker", at)
            assertThat(filed.isOverdueAt(LocalDate.of(2027, 1, 1))).isFalse()
        }
    }

    @Nested
    inner class Lifecycle {

        @Test
        fun `a fresh filing is OPEN, empty and accepting remittances`() {
            val f = TaxFilingRecord.open(july, "CZK")

            assertThat(f.status).isEqualTo(FilingStatus.OPEN)
            assertThat(f.status.acceptsRemittances).isTrue()
            assertThat(f.totalTaxAmount).isEqualByComparingTo("0")
            assertThat(f.remittanceCount).isZero()
        }

        @Test
        fun `assembling freezes the totals and stops accepting remittances`() {
            val f = assembled()

            assertThat(f.status).isEqualTo(FilingStatus.ASSEMBLED)
            assertThat(f.status.acceptsRemittances).isFalse()
            assertThat(f.totalTaxAmount).isEqualByComparingTo("12345")
            assertThat(f.remittanceCount).isEqualTo(3)
            assertThat(f.itemCount).isEqualTo(480)
            assertThat(f.assembledBy).isEqualTo("maker")
        }

        /**
         * Not idempotent by design. Re-assembling with different totals would mean a remittance
         * arrived late — which must reach an operator, not be quietly re-totalled underneath a
         * return that may already have been submitted.
         */
        @Test
        fun `a period cannot be assembled twice`() {
            assertThatThrownBy { assembled().assemble(BigDecimal("999"), 1, 1, "maker", at) }
                .isInstanceOf(TaxConflictException::class.java)
                .hasMessageContaining("not OPEN")
        }

        @Test
        fun `filing records the submission reference`() {
            val filed = assembled().markFiled("FU-2026-07-001", "checker", at)

            assertThat(filed.status).isEqualTo(FilingStatus.FILED)
            assertThat(filed.filingReference).isEqualTo("FU-2026-07-001")
            assertThat(filed.filedBy).isEqualTo("checker")
        }

        @Test
        fun `an OPEN period cannot be filed without being assembled`() {
            assertThatThrownBy { TaxFilingRecord.open(july, "CZK").markFiled("ref", "checker", at) }
                .isInstanceOf(TaxConflictException::class.java)
                .hasMessageContaining("assemble it before filing")
        }

        /** A filing recorded without a reference cannot be evidenced later — which defeats recording it. */
        @Test
        fun `a blank reference is refused`() {
            assertThatThrownBy { assembled().markFiled("  ", "checker", at) }
                .isInstanceOf(TaxValidationException::class.java)
                .hasMessageContaining("filing reference is required")
        }
    }

    @Nested
    inner class FourEyes {

        @Test
        fun `the assembler may not also file`() {
            assertThatThrownBy { assembled(by = "same-person").markFiled("ref", "same-person", at) }
                .isInstanceOf(TaxConflictException::class.java)
                .hasMessageContaining("may not also file it")
        }

        @Test
        fun `a different actor may file`() {
            assertThat(assembled(by = "maker").markFiled("ref", "checker", at).status)
                .isEqualTo(FilingStatus.FILED)
        }

        @Test
        fun `assembly requires an actor`() {
            assertThatThrownBy { TaxFilingRecord.open(july, "CZK").assemble(BigDecimal.ONE, 1, 1, " ", at) }
                .isInstanceOf(TaxValidationException::class.java)
                .hasMessageContaining("Assembly requires an actor")
        }
    }

    @Nested
    inner class ObservedRemittances {

        @Test
        fun `a negative tax amount is rejected`() {
            assertThatThrownBy {
                ObservedRemittance(
                    remittanceId = java.util.UUID.randomUUID(),
                    period = july,
                    currency = "CZK",
                    totalTaxAmount = BigDecimal("-1"),
                    itemCount = 1,
                    dueDate = july.dueDate,
                    observedAt = at,
                )
            }.isInstanceOf(TaxValidationException::class.java).hasMessageContaining("must not be negative")
        }

        /**
         * Zero is legitimate, not a decode artefact: tax is assessed in whole CZK with
         * RoundingMode.DOWN (ADR-0033 §E), so any gross below the rounding threshold yields zero
         * withheld while still producing a WITHHELD row and therefore a batch.
         */
        @Test
        fun `a zero tax amount is accepted`() {
            val r = ObservedRemittance(
                remittanceId = java.util.UUID.randomUUID(),
                period = july,
                currency = "CZK",
                totalTaxAmount = BigDecimal.ZERO,
                itemCount = 4,
                dueDate = july.dueDate,
                observedAt = at,
            )

            assertThat(r.totalTaxAmount).isEqualByComparingTo("0")
        }

        @Test
        fun `a non-ISO currency code is rejected`() {
            assertThatThrownBy {
                ObservedRemittance(
                    remittanceId = java.util.UUID.randomUUID(),
                    period = july,
                    currency = "CZECH",
                    totalTaxAmount = BigDecimal.ONE,
                    itemCount = 1,
                    dueDate = july.dueDate,
                    observedAt = at,
                )
            }.isInstanceOf(TaxValidationException::class.java).hasMessageContaining("ISO-4217")
        }
    }
}
