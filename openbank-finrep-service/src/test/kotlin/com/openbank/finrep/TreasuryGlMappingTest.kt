// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep

import com.openbank.finrep.application.port.out.TrialBalanceLineDto
import com.openbank.finrep.application.port.out.TrialBalanceSnapshot
import com.openbank.finrep.domain.mapper.F0101Mapper
import com.openbank.finrep.domain.mapper.F0102Mapper
import com.openbank.finrep.domain.mapper.F0103Mapper
import com.openbank.finrep.domain.mapper.F0200Mapper
import com.openbank.finrep.domain.model.FinrepCell
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Treasury (ADR-0315) books to GL codes that the FINREP allowlist did not know, so any booked
 * treasury deal produced `UNCLASSIFIED_GL_ACCOUNT` and blocked F01.01/F01.02/F02.00 outright.
 *
 * The code list below is the literal `TreasuryChart.postedCodes` of openbank-treasury-service
 * (`Postings.kt`) plus the two FX position accounts. It is a literal on purpose: finrep must not
 * depend on the treasury module, and a literal is what makes this test fail if either side drifts.
 */
class TreasuryGlMappingTest {
    private val asOf: LocalDate = LocalDate.of(2026, 6, 30)

    private val treasuryAssets = listOf("1001", "1002", "1500", "1501", "1510", "1520", "1521", "1990", "1991")
    private val treasuryLiabilities = listOf("2300", "2301", "2310", "2311", "2320")
    private val treasuryIncome = listOf("4200", "4201")
    private val treasuryExpense = listOf("5200", "5201")

    private fun gaps(template: com.openbank.finrep.domain.model.FinrepTemplate) = template.dataGaps.map { it.code }

    private fun line(code: String, type: String, net: String) =
        TrialBalanceLineDto(code = code, accountType = type, net = BigDecimal(net), currency = "CZK")

    private fun snapshot(vararg lines: TrialBalanceLineDto) =
        TrialBalanceSnapshot(lines = lines.toList(), ledgerReportsBalanced = true)

    private fun cell(cells: List<FinrepCell>, row: String) = cells.single { it.rowRef == row }.value

    @Test
    fun `every treasury GL code is classified in all three templates`() {
        val all = treasuryAssets.map { line(it, "ASSET", "1") } +
            treasuryLiabilities.map { line(it, "LIABILITY", "-1") } +
            treasuryIncome.map { line(it, "INCOME", "-1") } +
            treasuryExpense.map { line(it, "EXPENSE", "1") }
        val s = TrialBalanceSnapshot(lines = all, ledgerReportsBalanced = true)

        assertThat(gaps(F0101Mapper.map(s, asOf))).isEmpty()
        assertThat(gaps(F0102Mapper.map(s, asOf))).isEmpty()
        assertThat(gaps(F0200Mapper.map(s, asOf))).isEmpty()
    }

    @Test
    fun `a code the allowlist still does not know is still blocked`() {
        // Negative control: the fail-closed boundary must survive the extension.
        val s = snapshot(line("1500", "ASSET", "1"), line("1599", "ASSET", "1"))
        val gap = F0101Mapper.map(s, asOf).dataGaps.single()
        assertThat(gap.code).isEqualTo("UNCLASSIFIED_GL_ACCOUNT")
        assertThat(gap.affectedScope).contains("1599").doesNotContain("1500")
    }

    @Test
    fun `F01_01 puts nostro in demand deposits and placements in loans and advances`() {
        val s = snapshot(
            line("1001", "ASSET", "100"),
            line("1002", "ASSET", "10"),
            line("1510", "ASSET", "1000"),
            line("1500", "ASSET", "2000"),
            line("1501", "ASSET", "200"),
            line("1520", "ASSET", "5"),
            line("1521", "ASSET", "1"),
        )
        val cells = F0101Mapper.map(s, asOf).cells
        // r0010 = cash + central bank balances + other demand deposits (nostro + ČNB facility)
        assertThat(cell(cells, "r0010")).isEqualByComparingTo("1110")
        assertThat(cell(cells, "r0030")).isEqualByComparingTo("1000")
        // r0040 = other demand deposits only: the ČNB facility is a central-bank balance, not a demand deposit
        assertThat(cell(cells, "r0040")).isEqualByComparingTo("110")
        // loans and advances to credit institutions incl. accrued interest (IFRS 9 carrying amount)
        assertThat(cell(cells, "r0181")).isEqualByComparingTo("2206")
        assertThat(cell(cells, "r0183")).isEqualByComparingTo("2206")
        assertThat(cell(cells, "r0380")).isEqualByComparingTo("3316")
    }

    @Test
    fun `F01_02 reports borrowings and accrued interest payable as deposits`() {
        val s = snapshot(
            line("2300", "LIABILITY", "-3000"),
            line("2301", "LIABILITY", "-300"),
            line("2320", "LIABILITY", "-500"),
            line("2310", "LIABILITY", "-7"),
            line("2311", "LIABILITY", "-3"),
        )
        val cells = F0102Mapper.map(s, asOf).cells
        assertThat(cell(cells, "r0130")).isEqualByComparingTo("500")
        assertThat(cell(cells, "r0150")).isEqualByComparingTo("3310")
        assertThat(cell(cells, "r0110")).isEqualByComparingTo("3810")
        assertThat(cell(cells, "r0120")).isEqualByComparingTo("3810")
        assertThat(cell(cells, "r0300")).isEqualByComparingTo("3810")
    }

    @Test
    fun `CNB deposit facility fills r0030 and feeds neither r0040 nor loans`() {
        val s = snapshot(line("1510", "ASSET", "1000"), line("1001", "ASSET", "40"))
        val cells = F0101Mapper.map(s, asOf).cells
        assertThat(cell(cells, "r0030")).isEqualByComparingTo("1000")
        assertThat(cell(cells, "r0040")).isEqualByComparingTo("40")
        assertThat(cell(cells, "r0010")).isEqualByComparingTo("1040")
        assertThat(cell(cells, "r0181")).isEqualByComparingTo("0")
    }

    @Test
    fun `CNB accrued interest stays with loans - accepted limitation of the shared 1520 account`() {
        val s = snapshot(line("1520", "ASSET", "5"), line("1521", "ASSET", "1"))
        val cells = F0101Mapper.map(s, asOf).cells
        assertThat(cell(cells, "r0181")).isEqualByComparingTo("6")
        assertThat(cell(cells, "r0183")).isEqualByComparingTo("6")
        assertThat(cell(cells, "r0030")).isEqualByComparingTo("0")
        assertThat(cell(cells, "r0010")).isEqualByComparingTo("0")
    }

    @Test
    fun `lombard borrowing lands in r0130 central banks and not in r0150`() {
        val s = snapshot(line("2320", "LIABILITY", "-500"), line("2300", "LIABILITY", "-200"))
        val cells = F0102Mapper.map(s, asOf).cells
        assertThat(cell(cells, "r0130")).isEqualByComparingTo("500")
        assertThat(cell(cells, "r0150")).isEqualByComparingTo("200")
        assertThat(cell(cells, "r0120")).isEqualByComparingTo("700")
        assertThat(cell(cells, "r0300")).isEqualByComparingTo("700")
    }

    @Test
    fun `nostro overdraft is reclassified from cash to credit-institution liabilities`() {
        // 1001 holds a 30 credit balance (overdraft); 1002 a 100 debit balance.
        val s = snapshot(line("1001", "ASSET", "-30"), line("1002", "ASSET", "100"))
        val assets = F0101Mapper.map(s, asOf).cells
        assertThat(cell(assets, "r0040")).isEqualByComparingTo("100")
        assertThat(cell(assets, "r0010")).isEqualByComparingTo("100")
        // gross assets: the overdraft is no longer netted against the positive nostro
        assertThat(cell(assets, "r0380")).isEqualByComparingTo("100")
        val liabs = F0102Mapper.map(s, asOf).cells
        assertThat(cell(liabs, "r0150")).isEqualByComparingTo("30")
        assertThat(cell(liabs, "r0120")).isEqualByComparingTo("30")
        assertThat(cell(liabs, "r0130")).isEqualByComparingTo("0")
        assertThat(cell(liabs, "r0300")).isEqualByComparingTo("30")
    }

    @Test
    fun `overdraft among other assets and liabilities keeps total assets equal to liabilities plus equity`() {
        val s = snapshot(
            line("1001", "ASSET", "-30"),
            line("1100", "ASSET", "200"),
            line("2300", "LIABILITY", "-50"),
            line("4100", "INCOME", "-120"),
        )
        val assets = F0101Mapper.map(s, asOf).cells
        val liabs = F0102Mapper.map(s, asOf).cells
        val equity = F0103Mapper.map(s, asOf).cells
        assertThat(cell(assets, "r0380")).isEqualByComparingTo("200")
        assertThat(cell(liabs, "r0300")).isEqualByComparingTo("80")
        assertThat(cell(liabs, "r0150")).isEqualByComparingTo("80")
        assertThat(cell(equity, "r0300")).isEqualByComparingTo("120")
        assertThat(cell(equity, "r0310")).isEqualByComparingTo("200")
        assertThat(cell(assets, "r0380"))
            .isEqualByComparingTo(cell(liabs, "r0300").add(cell(equity, "r0300")))
    }

    @Test
    fun `F02_00 reports money-market interest income and expense`() {
        val s = snapshot(
            line("4200", "INCOME", "-40"),
            line("4201", "INCOME", "-2"),
            line("5200", "EXPENSE", "30"),
            line("5201", "EXPENSE", "1"),
        )
        val cells = F0200Mapper.map(s, asOf).cells
        assertThat(cell(cells, "r0010")).isEqualByComparingTo("42")
        assertThat(cell(cells, "r0051")).isEqualByComparingTo("42")
        assertThat(cell(cells, "r0090")).isEqualByComparingTo("31")
        assertThat(cell(cells, "r0120")).isEqualByComparingTo("31")
        assertThat(cell(cells, "r0670")).isEqualByComparingTo("11")
    }
}
