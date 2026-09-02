// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep

import com.openbank.finrep.application.port.out.TrialBalanceLineDto
import com.openbank.finrep.application.port.out.TrialBalanceSnapshot
import com.openbank.finrep.domain.mapper.F0101Mapper
import com.openbank.finrep.domain.model.BalanceVerdict
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Fixtures use ledger's real sign convention — `net = totalDebit − totalCredit`, so credit-normal
 * accounts (LIABILITY, EQUITY, INCOME) are NEGATIVE. The previous fixtures passed a positive
 * liability, which no real trial balance line can carry for a credit-balance account, and that is
 * why the mapper's `assets − liabilities` derivation read as sensible while being wrong.
 *
 * The `isBalanced` assertions here come in falsification PAIRS on purpose (issue #5987). A check
 * that cannot be shown to fail is not a check, and the specific way the old one could not fail —
 * equity derived as the residual of the very identity being tested — is invisible to any test that
 * only ever asserts `true`.
 */
class F0101MapperTest {

    private val asOf: LocalDate = LocalDate.of(2026, 6, 30)

    /** Assets 500 000 = liabilities 300 000 + equity 200 000 (retained result). Σ net == 0. */
    private fun balancedTrialBalance() = listOf(
        line("1000", "ASSET", "500000"),
        line("2000", "LIABILITY", "-300000"),
        line("4100", "INCOME", "-260000"),
        line("5100", "EXPENSE", "60000"),
    )

    @Test
    fun `F01_01 reports only the official total-assets datapoint`() {
        val template = F0101Mapper.map(tb(balancedTrialBalance(), ledgerSays = true), asOf)

        assertThat(template.templateId).isEqualTo("F01.01")
        assertThat(template.cells).hasSize(6)
        assertThat(cell(template.cells, "r0380")).isEqualByComparingTo("500000")
        assertThat(template.cells).allMatch { it.colRef == "c0010" }
        assertThat(template.hasDataGaps).isFalse()
    }

    @Test
    fun `a trial balance that ties out is reported as balanced`() {
        assertThat(F0101Mapper.map(tb(balancedTrialBalance(), ledgerSays = true), asOf).isBalanced).isTrue()
    }

    @Test
    fun `an asset booked with no counterpart is reported as UNBALANCED`() {
        // The falsifying input. One extra debit-side line and nothing to credit against it — the
        // shape of a lost or filtered response line, or a ledger-side posting defect that survived
        // into the frozen evidence.
        val lines = balancedTrialBalance() + line("1001", "ASSET", "70000")

        assertThat(F0101Mapper.map(tb(lines, ledgerSays = false), asOf).isBalanced).isFalse()
    }

    @Test
    fun `a residual in the P&L half falsifies the BALANCE SHEET template`() {
        // Income and expense feed no F01.01 asset row, yet a residual there is still caught.
        val lines = balancedTrialBalance() + line("5001", "EXPENSE", "12000")

        assertThat(F0101Mapper.map(tb(lines, ledgerSays = false), asOf).isBalanced).isFalse()
    }

    @Test
    fun `a currency that does not tie out falsifies the template even when the totals cancel`() {
        // Summing residuals across currencies would report this as balanced: EUR is +40 000 short
        // and CZK is 40 000 long, so a single global sum is exactly zero. Grouping per currency is
        // what stops the check being satisfiable by coincidence.
        val lines = listOf(
            line("1000", "ASSET", "500000"),
            line("2000", "LIABILITY", "-460000"),
            line("1000", "ASSET", "100000", currency = "EUR"),
            line("2000", "LIABILITY", "-140000", currency = "EUR"),
        )

        // NOTE the ledger flag passed here is `true`, and that is what a real ledger would send:
        // its own verdict is a single UNGROUPED scalar (`Σ totalDebit == Σ totalCredit`), which
        // this fixture satisfies exactly. So this is also a live SOURCES_DISAGREE case — the
        // per-currency grouping is finrep seeing something the producer's own check cannot.
        val template = F0101Mapper.map(tb(lines, ledgerSays = true), asOf)

        assertThat(template.isBalanced).isFalse()
        assertThat(template.balanceVerdict).isEqualTo(BalanceVerdict.SOURCES_DISAGREE)
    }

    @Test
    fun `decimal scale differences do not fake an imbalance`() {
        // `BigDecimal("0.00") != BigDecimal.ZERO` by equals. An equals-based residual check would
        // call every real money trial balance unbalanced.
        val lines = listOf(
            line("1000", "ASSET", "150000.00"),
            line("2000", "LIABILITY", "-150000.0000"),
        )

        assertThat(F0101Mapper.map(tb(lines, ledgerSays = true), asOf).isBalanced).isTrue()
    }

    @Test
    fun `an empty trial balance is balanced, not unbalanced`() {
        // An absent trial balance is an under-reporting defect with its own detector
        // (`openbank.finrep.trial_balance.lines`); collapsing it onto this flag would make two
        // different failures indistinguishable to a consumer acting on either.
        val template = F0101Mapper.map(tb(emptyList(), ledgerSays = true), asOf)

        assertThat(template.isBalanced).isTrue()
        assertThat(template.cells).allMatch { it.value.compareTo(BigDecimal.ZERO) == 0 }
    }

    /**
     * The ledger verdict is passed EXPLICITLY at every call site, never derived from `lines`
     * (issue #6011). Deriving it here would make both sides of the cross-check move together and
     * every `SOURCES_DISAGREE` case in this file structurally unreachable — the same vacuity
     * #6010 removed one level down.
     */
    private fun tb(lines: List<TrialBalanceLineDto>, ledgerSays: Boolean?) =
        TrialBalanceSnapshot(lines = lines, ledgerReportsBalanced = ledgerSays)

    private fun line(code: String, accountType: String, net: String, currency: String = "CZK") =
        TrialBalanceLineDto(code = code, accountType = accountType, net = BigDecimal(net), currency = currency)

    private fun cell(cells: List<com.openbank.finrep.domain.model.FinrepCell>, rowRef: String) =
        cells.single { it.rowRef == rowRef }.value
}
