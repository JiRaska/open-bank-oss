// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0.

package com.openbank.balance.domain.reconciliation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class BranchBookReconciliationTest {
    private val bank = UUID.randomUUID()
    private val first = UUID.randomUUID()
    private val second = UUID.randomUUID()
    private val date = LocalDate.of(2026, 11, 17)

    @Test
    fun `matching deposit books cannot hide an unmatched clearing position`() {
        val rows = listOf(
            line(first, "20").copy(clearing = BigDecimal("-10")),
            line(second, "30").copy(clearing = BigDecimal("9")),
        )
        val report = BranchBookReconciliation.reconcile(
            BranchLedgerSnapshot(bank, date, rows),
            listOf(total(first, "20"), total(second, "30")),
            Instant.now(),
        )
        assertThat(report.bankMatches).isTrue()
        assertThat(report.booksMatch).isTrue()
        assertThat(report.clearingEliminates).isFalse()
        assertThat(report.complete).isFalse()
    }

    @Test
    fun `opposite corrupt payment pairs cannot cancel the per-payment control`() {
        val rows = listOf(
            line(first, "20").copy(clearing = BigDecimal("-10")),
            line(second, "30").copy(clearing = BigDecimal("10")),
        )
        val report = BranchBookReconciliation.reconcile(
            BranchLedgerSnapshot(bank, date, rows, clearingBalanced = false),
            listOf(total(first, "20"), total(second, "30")),
            Instant.now(),
        )
        assertThat(report.booksMatch).isTrue()
        assertThat(report.clearingEliminates).isFalse()
        assertThat(report.complete).isFalse()
    }

    @Test
    fun `two swapped branch allocations fail even though the bank still reconciles`() {
        val snapshot = BranchLedgerSnapshot(bank, date, listOf(line(first, "20"), line(second, "30")))
        val report = BranchBookReconciliation.reconcile(
            snapshot,
            listOf(total(first, "30"), total(second, "20")),
            Instant.now(),
        )
        assertThat(report.bankMatches).isTrue()
        assertThat(report.journalBooksBalanced).isTrue()
        assertThat(report.booksMatch).isFalse()
        assertThat(report.complete).isFalse()
        assertThat(report.books.map { it.difference.abs() }).allSatisfy { assertThat(it).isEqualByComparingTo("10") }
    }

    @Test
    fun `matching per-currency books reconcile to the bank and corrections preserve provenance`() {
        val snapshot = BranchLedgerSnapshot(bank, date, listOf(line(first, "20"), line(second, "30")))
        val report = BranchBookReconciliation.reconcile(
            snapshot,
            listOf(total(first, "20"), total(second, "30")),
            Instant.now(),
        )
        assertThat(report.complete).isTrue()
        assertThat(report.bank.single().ledger).isEqualByComparingTo("50")
        assertThat(report.books.map { it.branchBookId }).containsExactlyInAnyOrder(first, second)
    }

    @Test
    fun `missing historical context is explicit even when the bank and books match`() {
        val snapshot = BranchLedgerSnapshot(bank, date, listOf(line(null, "20")))
        val report = BranchBookReconciliation.reconcile(
            snapshot,
            listOf(ProjectedBookTotal(null, null, "EUR", BigDecimal("20"), 1)),
            Instant.now(),
        )
        assertThat(report.bankMatches).isTrue()
        assertThat(report.booksMatch).isTrue()
        assertThat(report.complete).isFalse()
        assertThat(report.unknownAccounts).isEqualTo(1)
        assertThat(report.hasUnattributedJournals).isTrue()
    }

    @Test
    fun `an FX journal balances in base currency while deposits reconcile in native currency`() {
        val snapshot = BranchLedgerSnapshot(
            bank,
            date,
            listOf(
                BranchLedgerLine(first, "EUR", BigDecimal.ZERO, BigDecimal("500"), BigDecimal("20"), "CZK"),
                BranchLedgerLine(first, "CZK", BigDecimal("500"), BigDecimal.ZERO, BigDecimal.ZERO, "CZK"),
            ),
        )
        val report = BranchBookReconciliation.reconcile(snapshot, listOf(total(first, "20")), Instant.now())
        assertThat(report.complete).isTrue()
    }

    private fun line(book: UUID?, amount: String) =
        BranchLedgerLine(book, "EUR", BigDecimal(amount), BigDecimal(amount), BigDecimal(amount))
    private fun total(book: UUID, amount: String) = ProjectedBookTotal(bank, book, "EUR", BigDecimal(amount), 0)
}
