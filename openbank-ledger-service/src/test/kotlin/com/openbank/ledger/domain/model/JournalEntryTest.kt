// SPDX-License-Identifier: Apache-2.0\n// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.\n// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.\n
package com.openbank.ledger.domain.model

import com.openbank.libs.domain.money.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class JournalEntryTest {

    private val accountAsset = UUID.randomUUID()
    private val accountLiability = UUID.randomUUID()
    private val accountIncome = UUID.randomUUID()
    private val userId = UUID.randomUUID()

    private fun czk(amount: String) = Money.of(BigDecimal(amount), "CZK")

    private fun line(
        glAccountId: UUID,
        side: JournalSide,
        amount: String,
        currency: String = "CZK",
        sequence: Int = 1,
    ) = JournalLine(
        id = UUID.randomUUID(),
        journalId = UUID.randomUUID(),
        glAccountId = glAccountId,
        side = side,
        amount = Money.of(BigDecimal(amount), currency),
        fxRate = null,
        baseAmount = Money.of(BigDecimal(amount), currency),
        sequence = sequence,
    )

    private fun balancedEntry(
        amount: String = "1000.00",
        status: JournalStatus = JournalStatus.PENDING,
        lines: List<JournalLine>? = null,
        synthetic: Boolean = false,
    ): JournalEntry {
        val journalId = UUID.randomUUID()
        val entryLines = lines ?: listOf(
            line(accountAsset, JournalSide.DEBIT, amount, sequence = 1),
            line(accountLiability, JournalSide.CREDIT, amount, sequence = 2),
        )
        return JournalEntry(
            id = journalId,
            entryNumber = 1L,
            transactionId = UUID.randomUUID(),
            entryDate = LocalDate.of(2026, 1, 15),
            valueDate = LocalDate.of(2026, 1, 15),
            description = "Test entry",
            status = status,
            lines = entryLines,
            createdAt = Instant.now(),
            createdBy = userId,
            version = 0L,
            synthetic = synthetic,
        )
    }

    @Nested
    inner class Creation {

        @Test
        fun `creates balanced journal entry with two lines`() {
            val entry = balancedEntry("500.00")

            assertThat(entry.lines).hasSize(2)
            assertThat(entry.status).isEqualTo(JournalStatus.PENDING)
            assertThat(entry.entryNumber).isEqualTo(1L)
        }

        @Test
        fun `creates entry with multiple debit and credit lines`() {
            val entry = balancedEntry(
                lines = listOf(
                    line(accountAsset, JournalSide.DEBIT, "300.00", sequence = 1),
                    line(accountIncome, JournalSide.DEBIT, "200.00", sequence = 2),
                    line(accountLiability, JournalSide.CREDIT, "500.00", sequence = 3),
                ),
            )

            assertThat(entry.lines).hasSize(3)
        }

        @Test
        fun `rejects entry with fewer than two lines`() {
            assertThatThrownBy {
                JournalEntry(
                    id = UUID.randomUUID(),
                    entryNumber = 1L,
                    transactionId = UUID.randomUUID(),
                    entryDate = LocalDate.now(),
                    valueDate = LocalDate.now(),
                    description = null,
                    status = JournalStatus.PENDING,
                    lines = listOf(line(accountAsset, JournalSide.DEBIT, "100.00")),
                    createdAt = Instant.now(),
                    createdBy = userId,
                    version = 0L,
                )
            }.isInstanceOf(LedgerValidationException::class.java)
                .hasMessageContaining("at least 2 lines")
        }

        @Test
        fun `rejects unbalanced entry - debits exceed credits`() {
            assertThatThrownBy {
                balancedEntry(
                    lines = listOf(
                        line(accountAsset, JournalSide.DEBIT, "1000.00", sequence = 1),
                        line(accountLiability, JournalSide.CREDIT, "999.99", sequence = 2),
                    ),
                )
            }.isInstanceOf(LedgerValidationException::class.java)
                .hasMessageContaining("not balanced")
        }

        @Test
        fun `rejects unbalanced entry - credits exceed debits`() {
            assertThatThrownBy {
                balancedEntry(
                    lines = listOf(
                        line(accountAsset, JournalSide.DEBIT, "500.00", sequence = 1),
                        line(accountLiability, JournalSide.CREDIT, "600.00", sequence = 2),
                    ),
                )
            }.isInstanceOf(LedgerValidationException::class.java)
                .hasMessageContaining("not balanced")
        }

        @Test
        fun `accepts entry with zero amounts on both sides`() {
            val entry = balancedEntry(
                lines = listOf(
                    line(accountAsset, JournalSide.DEBIT, "0.00", sequence = 1),
                    line(accountLiability, JournalSide.CREDIT, "0.00", sequence = 2),
                ),
            )

            assertThat(entry.lines).hasSize(2)
        }

        @Test
        fun `validates balance across multiple lines per side`() {
            val entry = balancedEntry(
                lines = listOf(
                    line(accountAsset, JournalSide.DEBIT, "100.00", sequence = 1),
                    line(accountIncome, JournalSide.DEBIT, "250.00", sequence = 2),
                    line(accountLiability, JournalSide.CREDIT, "150.00", sequence = 3),
                    line(accountLiability, JournalSide.CREDIT, "200.00", sequence = 4),
                ),
            )

            assertThat(entry.lines).hasSize(4)
        }

        @Test
        fun `preserves a non-zero optimistic-lock version`() {
            // Every other test in this file only ever checks version == 0L (the constructor
            // default and reverse()'s hardcoded value for a NEW reversal), which cannot
            // distinguish the real getter from one that unconditionally returns 0 — exactly the
            // mutation pitest generates for JournalEntry.getVersion. A non-zero round-trip pins it.
            val entry = balancedEntry().copy(version = 7L)

            assertThat(entry.version).isEqualTo(7L)
        }

        @Test
        fun `handles large amounts without precision loss`() {
            val entry = balancedEntry(
                lines = listOf(
                    line(accountAsset, JournalSide.DEBIT, "99999999999999.99", sequence = 1),
                    line(accountLiability, JournalSide.CREDIT, "99999999999999.99", sequence = 2),
                ),
            )

            assertThat(entry.lines[0].amount.amount).isEqualByComparingTo(BigDecimal("99999999999999.99"))
        }
    }

    @Nested
    inner class MultiCurrencyBalancing {

        private val fxPositionEur = UUID.randomUUID()
        private val fxPositionCzk = UUID.randomUUID()
        private val fxMarginIncome = UUID.randomUUID()

        @Test
        fun `accepts entry that balances within each currency independently`() {
            val entry = balancedEntry(
                lines = listOf(
                    line(accountAsset, JournalSide.DEBIT, "100.00", currency = "EUR", sequence = 1),
                    line(accountLiability, JournalSide.CREDIT, "100.00", currency = "EUR", sequence = 2),
                    line(accountAsset, JournalSide.DEBIT, "2500.00", currency = "CZK", sequence = 3),
                    line(accountLiability, JournalSide.CREDIT, "2500.00", currency = "CZK", sequence = 4),
                ),
            )

            assertThat(entry.lines).hasSize(4)
        }

        @Test
        fun `rejects entry that balances on base sum but not within each currency`() {
            // Regression for the base-currency-sum bug: 1000 EUR debit vs 1000 CZK credit
            // nets to zero on a naive cross-currency sum, but is unbalanced in both currencies.
            assertThatThrownBy {
                balancedEntry(
                    lines = listOf(
                        line(accountAsset, JournalSide.DEBIT, "1000.00", currency = "EUR", sequence = 1),
                        line(accountLiability, JournalSide.CREDIT, "1000.00", currency = "CZK", sequence = 2),
                    ),
                )
            }.isInstanceOf(LedgerValidationException::class.java)
                .hasMessageContaining("not balanced in EUR")
        }

        @Test
        fun `accepts a four-leg FX conversion routed through FX position accounts`() {
            // EUR 1000 -> CZK at 25.00, customer receives 24900 CZK, bank keeps 100 CZK margin.
            // Self-balances per currency: EUR 1000=1000, CZK 25000=24900+100.
            val entry = balancedEntry(
                lines = listOf(
                    line(accountLiability, JournalSide.DEBIT, "1000.00", currency = "EUR", sequence = 1),
                    line(fxPositionEur, JournalSide.CREDIT, "1000.00", currency = "EUR", sequence = 2),
                    line(fxPositionCzk, JournalSide.DEBIT, "25000.00", currency = "CZK", sequence = 3),
                    line(accountLiability, JournalSide.CREDIT, "24900.00", currency = "CZK", sequence = 4),
                    line(fxMarginIncome, JournalSide.CREDIT, "100.00", currency = "CZK", sequence = 5),
                ),
            )

            assertThat(entry.lines).hasSize(5)
        }

        @Test
        fun `rejects four-leg FX entry when the CZK leg is short`() {
            assertThatThrownBy {
                balancedEntry(
                    lines = listOf(
                        line(accountLiability, JournalSide.DEBIT, "1000.00", currency = "EUR", sequence = 1),
                        line(fxPositionEur, JournalSide.CREDIT, "1000.00", currency = "EUR", sequence = 2),
                        line(fxPositionCzk, JournalSide.DEBIT, "25000.00", currency = "CZK", sequence = 3),
                        line(accountLiability, JournalSide.CREDIT, "24900.00", currency = "CZK", sequence = 4),
                    ),
                )
            }.isInstanceOf(LedgerValidationException::class.java)
                .hasMessageContaining("not balanced in CZK")
        }
    }

    @Nested
    inner class Post {

        @Test
        fun `transitions PENDING to POSTED`() {
            val pending = balancedEntry(status = JournalStatus.PENDING)
            val posted = pending.post()

            assertThat(posted.status).isEqualTo(JournalStatus.POSTED)
            assertThat(posted.id).isEqualTo(pending.id)
            assertThat(posted.lines).isEqualTo(pending.lines)
        }

        @Test
        fun `rejects posting already POSTED entry`() {
            val posted = balancedEntry(status = JournalStatus.POSTED)

            assertThatThrownBy { posted.post() }
                .isInstanceOf(LedgerConflictException::class.java)
                .hasMessageContaining("PENDING")
        }

        @Test
        fun `rejects posting REVERSED entry`() {
            val reversed = balancedEntry(status = JournalStatus.REVERSED)

            assertThatThrownBy { reversed.post() }
                .isInstanceOf(LedgerConflictException::class.java)
                .hasMessageContaining("PENDING")
        }
    }

    @Nested
    inner class Reverse {

        @Test
        fun `a reversal inherits the synthetic taint of the entry it reverses`() {
            // ADR-0252 (#8615). Dropping the taint here puts the compensating half inside the real
            // population while the original sits outside it, so a real-only trial balance would
            // carry the reversal's legs alone and be skewed by exactly the original's net. The
            // reversal is internally balanced, so `balanced` stays true and says nothing — the same
            // shape as the #939 status-filter defect, which is why this needs its own assertion.
            val posted = balancedEntry(status = JournalStatus.POSTED, synthetic = true)

            val reversal = posted.reverse(UUID.randomUUID(), UUID.randomUUID())

            assertThat(reversal.synthetic).isTrue()
        }

        @Test
        fun `a reversal of a real entry stays real`() {
            val posted = balancedEntry(status = JournalStatus.POSTED)

            val reversal = posted.reverse(UUID.randomUUID(), UUID.randomUUID())

            assertThat(reversal.synthetic).isFalse()
        }

        @Test
        fun `creates reversal entry with flipped sides`() {
            val posted = balancedEntry(status = JournalStatus.POSTED)
            val reversalId = UUID.randomUUID()
            val reversedBy = UUID.randomUUID()

            val reversal = posted.reverse(reversalId, reversedBy)

            assertThat(reversal.id).isEqualTo(reversalId)
            assertThat(reversal.status).isEqualTo(JournalStatus.POSTED)
            assertThat(reversal.createdBy).isEqualTo(reversedBy)
            assertThat(reversal.transactionId).isEqualTo(posted.transactionId)
            assertThat(reversal.entryNumber).isNull()
            assertThat(reversal.version).isEqualTo(0L)
            // Every reversal line is re-parented onto the reversal entry. Leaving the original's
            // journalId is the V10-era bug: persistLines then attaches the lines to the ORIGINAL
            // and the persisted reversal has zero lines — unreadable on hydration (#465).
            assertThat(reversal.lines).allSatisfy { assertThat(it.journalId).isEqualTo(reversalId) }
            assertThat(reversal.lines.map { it.id }).doesNotContainAnyElementsOf(posted.lines.map { it.id })
        }

        @Test
        fun `reversal inherits entryDate, valueDate and links reversalOf to the original`() {
            // entryDate specifically is load-bearing for the period lock (#869):
            // LedgerService.requireOpenPeriod derives the fiscal year to check from the
            // REVERSAL's entryDate, which only works if reverse() actually inherits it from the
            // original rather than defaulting to, say, the current date. valueDate and
            // reversalOf are asserted here too since the fixture's balancedEntry() gives
            // entryDate == valueDate by default elsewhere in this file — a mutant swapping one
            // for the other would be silently invisible in every other test in this class.
            val journalId = UUID.randomUUID()
            val posted = JournalEntry(
                id = journalId,
                entryNumber = 1L,
                transactionId = UUID.randomUUID(),
                entryDate = LocalDate.of(2026, 3, 10),
                valueDate = LocalDate.of(2026, 3, 12),
                description = "Test entry",
                status = JournalStatus.POSTED,
                lines = listOf(
                    line(accountAsset, JournalSide.DEBIT, "500.00", sequence = 1),
                    line(accountLiability, JournalSide.CREDIT, "500.00", sequence = 2),
                ),
                createdAt = Instant.now(),
                createdBy = userId,
                version = 0L,
            )

            val reversal = posted.reverse(UUID.randomUUID(), UUID.randomUUID())

            assertThat(reversal.entryDate).isEqualTo(LocalDate.of(2026, 3, 10))
            assertThat(reversal.valueDate).isEqualTo(LocalDate.of(2026, 3, 12))
            assertThat(reversal.reversalOf).isEqualTo(journalId)
        }

        @Test
        fun `reversal flips DEBIT to CREDIT and vice versa`() {
            val posted = balancedEntry(status = JournalStatus.POSTED)
            val reversal = posted.reverse(UUID.randomUUID(), UUID.randomUUID())

            val originalDebit = posted.lines.first { it.side == JournalSide.DEBIT }
            val reversalOfDebit = reversal.lines[posted.lines.indexOf(originalDebit)]

            assertThat(reversalOfDebit.side).isEqualTo(JournalSide.CREDIT)
            assertThat(reversalOfDebit.amount).isEqualTo(originalDebit.amount)

            val originalCredit = posted.lines.first { it.side == JournalSide.CREDIT }
            val reversalOfCredit = reversal.lines[posted.lines.indexOf(originalCredit)]

            assertThat(reversalOfCredit.side).isEqualTo(JournalSide.DEBIT)
        }

        @Test
        fun `reversal generates new line IDs`() {
            val posted = balancedEntry(status = JournalStatus.POSTED)
            val reversal = posted.reverse(UUID.randomUUID(), UUID.randomUUID())

            val originalIds = posted.lines.map { it.id }.toSet()
            val reversalIds = reversal.lines.map { it.id }.toSet()

            assertThat(reversalIds).doesNotContainAnyElementsOf(originalIds)
        }

        @Test
        fun `reversal preserves the sub-ledger dimension`() {
            // The sub_account_id must survive into the reversal so the per-account sub-ledger
            // nets back to zero on reversal (ADR-0039 Phase B).
            val subAccount = UUID.randomUUID()
            val posted = balancedEntry(
                status = JournalStatus.POSTED,
                lines = listOf(
                    line(accountAsset, JournalSide.DEBIT, "1000.00", sequence = 1),
                    line(accountLiability, JournalSide.CREDIT, "1000.00", sequence = 2)
                        .copy(subAccountId = subAccount),
                ),
            )

            val reversal = posted.reverse(UUID.randomUUID(), UUID.randomUUID())

            val controlLeg = reversal.lines.single { it.glAccountId == accountLiability }
            assertThat(controlLeg.subAccountId).isEqualTo(subAccount)
            assertThat(reversal.lines.single { it.glAccountId == accountAsset }.subAccountId).isNull()
        }

        @Test
        fun `reversal preserves amounts`() {
            val posted = balancedEntry("12345.67", status = JournalStatus.POSTED)
            val reversal = posted.reverse(UUID.randomUUID(), UUID.randomUUID())

            reversal.lines.forEachIndexed { i, line ->
                assertThat(line.amount).isEqualTo(posted.lines[i].amount)
                assertThat(line.baseAmount).isEqualTo(posted.lines[i].baseAmount)
            }
        }

        @Test
        fun `reversal description references original entry number`() {
            val posted = balancedEntry(status = JournalStatus.POSTED)
            val reversal = posted.reverse(UUID.randomUUID(), UUID.randomUUID())

            assertThat(reversal.description).contains(posted.entryNumber.toString())
        }

        @Test
        fun `reversal is itself balanced`() {
            val posted = balancedEntry(
                lines = listOf(
                    line(accountAsset, JournalSide.DEBIT, "300.00", sequence = 1),
                    line(accountIncome, JournalSide.DEBIT, "200.00", sequence = 2),
                    line(accountLiability, JournalSide.CREDIT, "500.00", sequence = 3),
                ),
                status = JournalStatus.POSTED,
            )

            val reversal = posted.reverse(UUID.randomUUID(), UUID.randomUUID())

            // If reversal is not balanced, the JournalEntry constructor would throw
            assertThat(reversal.lines).hasSize(3)
            assertThat(reversal.status).isEqualTo(JournalStatus.POSTED)
        }

        @Test
        fun `rejects reversing PENDING entry`() {
            val pending = balancedEntry(status = JournalStatus.PENDING)

            assertThatThrownBy { pending.reverse(UUID.randomUUID(), UUID.randomUUID()) }
                .isInstanceOf(LedgerConflictException::class.java)
                .hasMessageContaining("POSTED")
        }

        @Test
        fun `rejects reversing already REVERSED entry`() {
            val reversed = balancedEntry(status = JournalStatus.REVERSED)

            assertThatThrownBy { reversed.reverse(UUID.randomUUID(), UUID.randomUUID()) }
                .isInstanceOf(LedgerConflictException::class.java)
                .hasMessageContaining("POSTED")
        }
    }
}
