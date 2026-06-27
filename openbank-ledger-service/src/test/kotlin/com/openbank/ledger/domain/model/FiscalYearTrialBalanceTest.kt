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
import java.util.UUID

class FiscalYearTrialBalanceTest {

    private fun line(
        code: String,
        type: GlAccountType,
        debit: String,
        credit: String,
        currency: String = "CZK",
        id: UUID = UUID.randomUUID(),
        name: String = "Account $code",
    ) = TrialBalanceLine(
        glAccountId = id,
        code = code,
        name = name,
        type = type,
        currency = currency,
        totalDebit = BigDecimal(debit),
        totalCredit = BigDecimal(credit),
    )

    private fun balancedLines() = listOf(
        line("1100", GlAccountType.ASSET, "1500.00", "200.00"),
        line("2100", GlAccountType.LIABILITY, "100.00", "1200.00"),
        line("3000", GlAccountType.EQUITY, "0.00", "50.00"),
        line("4002", GlAccountType.INCOME, "0.00", "200.00"),
        line("5900", GlAccountType.EXPENSE, "50.00", "0.00"),
    )

    @Nested
    inner class Balancing {

        @Test
        fun `balanced GL has equal debit and credit totals`() {
            val tb = FiscalYearTrialBalance(2025, balancedLines())
            assertThat(tb.totalDebit).isEqualByComparingTo("1650.00")
            assertThat(tb.totalCredit).isEqualByComparingTo("1650.00")
            assertThat(tb.isBalanced).isTrue()
        }

        @Test
        fun `unbalanced GL is detected`() {
            val tb = FiscalYearTrialBalance(
                2025,
                listOf(
                    line("1100", GlAccountType.ASSET, "1000.00", "0.00"),
                    line("2100", GlAccountType.LIABILITY, "0.00", "999.99"),
                ),
            )
            assertThat(tb.isBalanced).isFalse()
        }

        @Test
        fun `scale differences do not break the balance comparison`() {
            val tb = FiscalYearTrialBalance(
                2025,
                listOf(
                    line("1100", GlAccountType.ASSET, "100", "0"),
                    line("2100", GlAccountType.LIABILITY, "0.00", "100.00"),
                ),
            )
            assertThat(tb.isBalanced).isTrue()
        }

        @Test
        fun `empty fiscal year is trivially balanced with zero accounts`() {
            val tb = FiscalYearTrialBalance(2025, emptyList())
            assertThat(tb.isBalanced).isTrue()
            assertThat(tb.accountCount).isZero()
            assertThat(tb.sections).isEmpty()
        }
    }

    @Nested
    inner class Grouping {

        @Test
        fun `sections group lines by account type in declaration order, omitting empty types`() {
            val tb = FiscalYearTrialBalance(
                2025,
                listOf(
                    line("5900", GlAccountType.EXPENSE, "50.00", "0.00"),
                    line("1100", GlAccountType.ASSET, "100.00", "0.00"),
                    line("1200", GlAccountType.ASSET, "20.00", "0.00"),
                    line("2100", GlAccountType.LIABILITY, "0.00", "170.00"),
                ),
            )
            assertThat(tb.sections.map { it.type })
                .containsExactly(GlAccountType.ASSET, GlAccountType.LIABILITY, GlAccountType.EXPENSE)

            val assets = tb.sections.first { it.type == GlAccountType.ASSET }
            assertThat(assets.lines).hasSize(2)
            assertThat(assets.totalDebit).isEqualByComparingTo("120.00")
            assertThat(assets.totalCredit).isEqualByComparingTo("0.00")
            assertThat(assets.net).isEqualByComparingTo("120.00")
        }

        @Test
        fun `accountCount counts distinct GL accounts, not currency lines`() {
            val sharedId = UUID.randomUUID()
            val tb = FiscalYearTrialBalance(
                2025,
                listOf(
                    line("1100", GlAccountType.ASSET, "100.00", "0.00", currency = "CZK", id = sharedId),
                    line("1100", GlAccountType.ASSET, "10.00", "0.00", currency = "EUR", id = sharedId),
                    line("2100", GlAccountType.LIABILITY, "0.00", "110.00"),
                ),
            )
            assertThat(tb.accountCount).isEqualTo(2)
        }
    }

    @Nested
    inner class HashDeterminism {

        @Test
        fun `hash is independent of line ordering`() {
            val lines = balancedLines()
            val a = FiscalYearTrialBalance(2025, lines)
            val b = FiscalYearTrialBalance(2025, lines.reversed())
            assertThat(a.contentHash()).isEqualTo(b.contentHash())
        }

        @Test
        fun `hash is independent of BigDecimal scale`() {
            val id = UUID.randomUUID()
            val a = FiscalYearTrialBalance(2025, listOf(line("1100", GlAccountType.ASSET, "100.00", "0.00", id = id)))
            val b = FiscalYearTrialBalance(2025, listOf(line("1100", GlAccountType.ASSET, "100", "0", id = id)))
            assertThat(a.contentHash()).isEqualTo(b.contentHash())
        }

        @Test
        fun `hash ignores cosmetic attributes but reacts to accounting content`() {
            val id = UUID.randomUUID()
            val base = FiscalYearTrialBalance(
                2025,
                listOf(line("1100", GlAccountType.ASSET, "100.00", "0.00", id = id, name = "Cash")),
            )
            val renamed = FiscalYearTrialBalance(
                2025,
                listOf(line("1100", GlAccountType.ASSET, "100.00", "0.00", id = id, name = "Cash & Equivalents")),
            )
            val amountChanged = FiscalYearTrialBalance(
                2025,
                listOf(line("1100", GlAccountType.ASSET, "100.01", "0.00", id = id, name = "Cash")),
            )
            val yearChanged = FiscalYearTrialBalance(
                2024,
                listOf(line("1100", GlAccountType.ASSET, "100.00", "0.00", id = id, name = "Cash")),
            )
            assertThat(renamed.contentHash()).isEqualTo(base.contentHash())
            assertThat(amountChanged.contentHash()).isNotEqualTo(base.contentHash())
            assertThat(yearChanged.contentHash()).isNotEqualTo(base.contentHash())
        }

        @Test
        fun `hash is 64 lowercase hex chars and canonical json is stable`() {
            val tb = FiscalYearTrialBalance(
                2025,
                listOf(line("1100", GlAccountType.ASSET, "100.50", "0.00")),
            )
            assertThat(tb.contentHash()).matches("[0-9a-f]{64}")
            assertThat(tb.canonicalJson()).isEqualTo(
                """{"fiscalYear":2025,"totalDebit":"100.5","totalCredit":"0","lines":[""" +
                    """{"code":"1100","type":"ASSET","currency":"CZK","debit":"100.5","credit":"0"}]}""",
            )
        }
    }

    @Nested
    inner class YearCloseRecordLifecycle {

        private fun draft() = YearCloseRecord.draftOf(
            trialBalance = FiscalYearTrialBalance(2025, balancedLines()),
            computedAt = Instant.parse("2026-01-05T10:00:00Z"),
            draftedBy = "maker-sub",
        )

        @Test
        fun `draftOf snapshots totals, account count and content hash`() {
            val tb = FiscalYearTrialBalance(2025, balancedLines())
            val record = YearCloseRecord.draftOf(tb, Instant.parse("2026-01-05T10:00:00Z"))
            assertThat(record.status).isEqualTo(YearCloseStatus.DRAFT)
            assertThat(record.fiscalYear).isEqualTo(2025)
            assertThat(record.totalDebits).isEqualByComparingTo("1650.00")
            assertThat(record.totalCredits).isEqualByComparingTo("1650.00")
            assertThat(record.accountCount).isEqualTo(5)
            assertThat(record.contentHash).isEqualTo(tb.contentHash())
            assertThat(record.attestedBy).isNull()
            assertThat(record.attestedAt).isNull()
        }

        @Test
        fun `attest flips DRAFT to ATTESTED with the audit trail`() {
            val at = Instant.parse("2026-02-01T08:00:00Z")
            val attested = draft().attest("operator-sub", at)
            assertThat(attested.status).isEqualTo(YearCloseStatus.ATTESTED)
            assertThat(attested.attestedBy).isEqualTo("operator-sub")
            assertThat(attested.attestedAt).isEqualTo(at)
        }

        @Test
        fun `attesting an already attested record fails`() {
            val attested = draft().attest("operator-sub", Instant.now())
            assertThatThrownBy { attested.attest("someone-else", Instant.now()) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("not DRAFT")
        }

        @Test
        fun `four-eyes - the maker cannot self-attest (domain check)`() {
            assertThatThrownBy { draft().attest("maker-sub", Instant.now()) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Four-eyes")
        }

        @Test
        fun `four-eyes - a draft with no recorded author cannot be attested (domain check)`() {
            val nullMaker = YearCloseRecord.draftOf(
                trialBalance = FiscalYearTrialBalance(2025, balancedLines()),
                computedAt = Instant.parse("2026-01-05T10:00:00Z"),
                draftedBy = null,
            )
            assertThatThrownBy { nullMaker.attest("checker-sub", Instant.now()) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("no recorded author")
        }

        @Test
        fun `fiscal year outside the supported range is rejected`() {
            assertThatThrownBy {
                YearCloseRecord.draftOf(FiscalYearTrialBalance(1999, emptyList()), Instant.now())
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
