// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0.

package com.openbank.balance.domain.reconciliation

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class BookKey(val branchBookId: UUID?, val currency: String)
data class BranchLedgerSnapshot(
    val bankId: UUID,
    val asOf: LocalDate,
    val lines: List<BranchLedgerLine>,
    val clearingBalanced: Boolean = true,
)
data class BranchLedgerLine(
    val branchBookId: UUID?,
    val currency: String,
    val debit: BigDecimal,
    val credit: BigDecimal,
    val deposits: BigDecimal,
    val baseCurrency: String = currency,
    val clearing: BigDecimal = BigDecimal.ZERO,
)
data class ProjectedBookTotal(
    val bankId: UUID?,
    val branchBookId: UUID?,
    val currency: String,
    val booked: BigDecimal,
    val unknownAccounts: Long,
)
data class BranchBookComparison(
    val branchBookId: UUID?,
    val currency: String,
    val ledger: BigDecimal,
    val projected: BigDecimal,
    val difference: BigDecimal,
)
data class BranchClearingBalance(val branchBookId: UUID?, val currency: String, val balance: BigDecimal)

data class BranchBookReport(
    val bankId: UUID,
    val asOf: LocalDate,
    val generatedAt: Instant,
    val books: List<BranchBookComparison>,
    val bank: List<BranchBookComparison>,
    val journalBooksBalanced: Boolean,
    val unknownAccounts: Long,
    val hasUnattributedJournals: Boolean,
    val clearingBalances: List<BranchClearingBalance> = emptyList(),
    val clearingEliminates: Boolean = true,
) {
    val bankMatches: Boolean get() = bank.all { it.difference.signum() == 0 }
    val booksMatch: Boolean get() = books.all { it.difference.signum() == 0 }
    val complete: Boolean get() = clearingEliminates &&
        bankMatches &&
        booksMatch &&
        journalBooksBalanced &&
        unknownAccounts == 0L &&
        !hasUnattributedJournals
}

object BranchBookReconciliation {
    fun reconcile(ledger: BranchLedgerSnapshot, projected: List<ProjectedBookTotal>, at: Instant): BranchBookReport {
        require(
            projected.all {
                it.bankId == null || it.bankId == ledger.bankId
            },
        ) { "projection contains a different bank" }
        val ledgerTotals = ledger.lines.groupBy { BookKey(it.branchBookId, it.currency) }
            .mapValues { (_, rows) -> rows.fold(BigDecimal.ZERO) { sum, row -> sum + row.deposits } }
        val projectedTotals = projected.groupBy { BookKey(it.branchBookId, it.currency) }
            .mapValues { (_, rows) -> rows.fold(BigDecimal.ZERO) { sum, row -> sum + row.booked } }
        val keys = ledgerTotals.keys + projectedTotals.keys
        val books = keys.sortedWith(compareBy({ it.branchBookId?.toString() ?: "" }, { it.currency })).map { key ->
            comparison(key, ledgerTotals[key] ?: BigDecimal.ZERO, projectedTotals[key] ?: BigDecimal.ZERO)
        }
        val bank = books.groupBy { it.currency }.toSortedMap().map { (currency, rows) ->
            comparison(
                BookKey(null, currency),
                rows.fold(BigDecimal.ZERO) { sum, row -> sum + row.ledger },
                rows.fold(BigDecimal.ZERO) { sum, row -> sum + row.projected },
            )
        }
        val clearing = ledger.lines.groupBy { BookKey(it.branchBookId, it.currency) }.map { (key, lines) ->
            BranchClearingBalance(key.branchBookId, key.currency, lines.sumOf { it.clearing })
        }
        return BranchBookReport(
            ledger.bankId,
            ledger.asOf,
            at,
            books,
            bank,
            ledger.lines.groupBy { BookKey(it.branchBookId, it.baseCurrency) }.values.all { lines ->
                lines.fold(BigDecimal.ZERO) { sum, row -> sum + row.debit - row.credit }.signum() == 0
            },
            projected.sumOf { it.unknownAccounts },
            ledger.lines.any { it.branchBookId == null && (it.debit.signum() != 0 || it.credit.signum() != 0) },
            clearing,
            ledger.clearingBalanced &&
                clearing.groupBy { it.currency }.values.all { rows -> rows.sumOf { it.balance }.signum() == 0 },
        )
    }

    private fun comparison(key: BookKey, ledger: BigDecimal, projected: BigDecimal) =
        BranchBookComparison(key.branchBookId, key.currency, ledger, projected, ledger - projected)
}
