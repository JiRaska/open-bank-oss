// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class ClosedPeriodTest {

    private val frozenAt = Instant.parse("2026-08-01T06:00:00Z")

    private fun line(code: String, debit: String, credit: String, currency: String = "CZK") = TrialBalanceLine(
        glAccountId = UUID.nameUUIDFromBytes(code.toByteArray()),
        code = code,
        name = "Account $code",
        type = GlAccountType.ASSET,
        currency = currency,
        totalDebit = BigDecimal(debit),
        totalCredit = BigDecimal(credit),
    )

    private fun balancedTb(period: AccountingPeriod) = PeriodTrialBalance(
        period,
        listOf(line("1100", "1000.00", "0"), line("2100", "0", "1000.00")),
    )

    @Nested
    inner class PeriodBoundaries {

        @Test
        fun `month, quarter and year derive whole calendar periods from any date inside them`() {
            val d = LocalDate.of(2026, 5, 17)

            assertThat(PeriodType.MONTH.of(d)).isEqualTo(
                AccountingPeriod(PeriodType.MONTH, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31)),
            )
            assertThat(PeriodType.QUARTER.of(d)).isEqualTo(
                AccountingPeriod(PeriodType.QUARTER, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30)),
            )
            assertThat(PeriodType.YEAR.of(d)).isEqualTo(
                AccountingPeriod(PeriodType.YEAR, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)),
            )
        }

        @Test
        fun `february in a leap year ends on the 29th`() {
            assertThat(PeriodType.MONTH.of(LocalDate.of(2028, 2, 10)).to).isEqualTo(LocalDate.of(2028, 2, 29))
        }

        @Test
        fun `every quarter boundary is exact`() {
            listOf(
                LocalDate.of(2026, 1, 1) to (LocalDate.of(2026, 1, 1) to LocalDate.of(2026, 3, 31)),
                LocalDate.of(2026, 6, 30) to (LocalDate.of(2026, 4, 1) to LocalDate.of(2026, 6, 30)),
                LocalDate.of(2026, 9, 15) to (LocalDate.of(2026, 7, 1) to LocalDate.of(2026, 9, 30)),
                LocalDate.of(2026, 12, 31) to (LocalDate.of(2026, 10, 1) to LocalDate.of(2026, 12, 31)),
            ).forEach { (date, expected) ->
                val q = PeriodType.QUARTER.of(date)
                assertThat(q.from to q.to).describedAs(date.toString()).isEqualTo(expected)
            }
        }

        /**
         * A statutory period is a whole calendar period, never an arbitrary window — two closes over
         * overlapping partial ranges could disagree about the same journal.
         */
        @Test
        fun `a partial range is rejected`() {
            assertThatThrownBy {
                AccountingPeriod(PeriodType.MONTH, LocalDate.of(2026, 5, 2), LocalDate.of(2026, 5, 31))
            }.isInstanceOf(LedgerValidationException::class.java).hasMessageContaining("whole month")
        }

        @Test
        fun `labels are stable and sortable`() {
            assertThat(PeriodType.MONTH.of(LocalDate.of(2026, 5, 17)).label).isEqualTo("MONTH:2026-05")
            assertThat(PeriodType.QUARTER.of(LocalDate.of(2026, 5, 17)).label).isEqualTo("QUARTER:2026-Q2")
            assertThat(PeriodType.YEAR.of(LocalDate.of(2026, 5, 17)).label).isEqualTo("YEAR:2026")
        }

        @Test
        fun `contains covers the whole period inclusive`() {
            val may = PeriodType.MONTH.of(LocalDate.of(2026, 5, 17))

            assertThat(may.contains(LocalDate.of(2026, 5, 1))).isTrue()
            assertThat(may.contains(LocalDate.of(2026, 5, 31))).isTrue()
            assertThat(may.contains(LocalDate.of(2026, 4, 30))).isFalse()
            assertThat(may.contains(LocalDate.of(2026, 6, 1))).isFalse()
        }
    }

    @Nested
    inner class HashAnchor {

        @Test
        fun `the hash ignores line order and numeric scale, but not the numbers`() {
            val p = PeriodType.MONTH.of(LocalDate.of(2026, 5, 1))
            val a = PeriodTrialBalance(p, listOf(line("1100", "1000.00", "0"), line("2100", "0", "1000.00")))
            val b = PeriodTrialBalance(p, listOf(line("2100", "0", "1000"), line("1100", "1000", "0")))
            val different = PeriodTrialBalance(p, listOf(line("1100", "1000.01", "0"), line("2100", "0", "1000.01")))

            assertThat(b.contentHash()).isEqualTo(a.contentHash())
            assertThat(different.contentHash()).isNotEqualTo(a.contentHash())
        }

        @Test
        fun `the same numbers in a different period hash differently`() {
            val may = balancedTb(PeriodType.MONTH.of(LocalDate.of(2026, 5, 1)))
            val june = balancedTb(PeriodType.MONTH.of(LocalDate.of(2026, 6, 1)))

            assertThat(june.contentHash()).isNotEqualTo(may.contentHash())
        }

        @Test
        fun `balance is checked over the whole period`() {
            val p = PeriodType.MONTH.of(LocalDate.of(2026, 5, 1))

            assertThat(balancedTb(p).isBalanced).isTrue()
            assertThat(PeriodTrialBalance(p, listOf(line("1100", "1000", "0"))).isBalanced).isFalse()
        }
    }

    @Nested
    inner class FreezeLifecycle {

        private val period = PeriodType.MONTH.of(LocalDate.of(2026, 5, 1))

        private fun draft(by: String? = "maker") = ClosedPeriodRecord.draftOf(
            trialBalance = balancedTb(period),
            computedAt = Instant.parse("2026-06-01T06:00:00Z"),
            draftedBy = by,
        )

        @Test
        fun `a draft carries the trial balance totals and its hash`() {
            val d = draft()

            assertThat(d.status).isEqualTo(ClosedPeriodStatus.DRAFT)
            assertThat(d.totalDebits).isEqualByComparingTo("1000.00")
            assertThat(d.totalCredits).isEqualByComparingTo("1000.00")
            assertThat(d.accountCount).isEqualTo(2)
            assertThat(d.contentHash).isEqualTo(balancedTb(period).contentHash())
        }

        @Test
        fun `freezing seals it with the checker and timestamp`() {
            val frozen = draft().freeze("checker", frozenAt)

            assertThat(frozen.status).isEqualTo(ClosedPeriodStatus.FROZEN)
            assertThat(frozen.frozenBy).isEqualTo("checker")
            assertThat(frozen.frozenAt).isEqualTo(frozenAt)
            assertThat(frozen.contentHash).isEqualTo(draft().contentHash)
        }

        @Test
        fun `a frozen period is immutable`() {
            val frozen = draft().freeze("checker", frozenAt)

            assertThatThrownBy { frozen.freeze("someone-else", frozenAt) }
                .isInstanceOf(LedgerConflictException::class.java)
                .hasMessageContaining("not DRAFT")
        }

        @Test
        fun `four-eyes - the checker must differ from the maker`() {
            assertThatThrownBy { draft("maker").freeze("maker", frozenAt) }
                .isInstanceOf(LedgerConflictException::class.java)
                .hasMessageContaining("other than the draft author")
        }

        /**
         * Fail closed on a missing maker rather than waving it through: without a recorded author
         * there is nothing to separate the checker from, so separation of duties cannot be proven.
         */
        @Test
        fun `a draft with no recorded author can never be frozen`() {
            assertThatThrownBy { draft(by = null).freeze("checker", frozenAt) }
                .isInstanceOf(LedgerConflictException::class.java)
                .hasMessageContaining("no recorded author")
        }
    }
}
