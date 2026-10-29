// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.finrep.domain.mapper

import com.openbank.finrep.application.port.out.TrialBalanceLineDto
import com.openbank.finrep.application.port.out.TrialBalanceSnapshot
import com.openbank.finrep.domain.model.BalanceVerdict
import com.openbank.finrep.domain.model.FinrepCell
import com.openbank.finrep.domain.model.FinrepTemplate
import com.openbank.finrep.domain.model.TrialBalanceAssurance
import java.math.BigDecimal
import java.time.LocalDate

/** Official FINREP F 01.02 liabilities total: r0300/c0010, datapoint 32592 (Framework 4.2). */
object F0102Mapper {
    fun map(snapshot: TrialBalanceSnapshot, asOf: LocalDate): FinrepTemplate {
        val lines = snapshot.lines
        val overdraft = NostroOverdraft.of(lines)
        val liabilities = lines
            .filter { it.accountType == "LIABILITY" }
            .fold(BigDecimal.ZERO) { total, line -> total.add(line.net) }
            .negate()
            .add(overdraft)
        // F01.02 has no counterparty split: interbank and central-bank borrowings belong to the
        // aggregate deposits row r0120. Rows r0130/r0150 are debt securities and hedge-accounting
        // derivatives respectively, neither of which is present in this ledger chart (#13603).
        val creditInstitutions = creditBalance(lines, "2300", "2301", "2310", "2311").add(overdraft)
        val deposits = creditBalance(lines, "2001", "2002", "2100", "2101", "2102", "2103", "2320")
            .add(creditInstitutions)
        val taxPayable = creditBalance(lines, "2200")
        val assessment = TrialBalanceAssurance.assess(snapshot)
        return FinrepTemplate(
            templateId = "F01.02",
            period = asOf,
            cells = listOf(
                FinrepCell("r0110", "c0010", deposits),
                FinrepCell("r0120", "c0010", deposits),
                FinrepCell("r0130", "c0010", BigDecimal.ZERO),
                FinrepCell("r0150", "c0010", BigDecimal.ZERO),
                FinrepCell("r0240", "c0010", taxPayable),
                FinrepCell("r0250", "c0010", taxPayable),
                FinrepCell("r0300", "c0010", liabilities),
            ),
            dataGaps = FinrepCoverage.gapsFor("F01.02", lines),
            isBalanced = assessment.verdict == BalanceVerdict.AGREED_BALANCED,
            balanceVerdict = assessment.verdict,
        )
    }

    private fun creditBalance(lines: List<TrialBalanceLineDto>, vararg codes: String): BigDecimal = lines
        .filter { it.code in codes }
        .fold(BigDecimal.ZERO) { total, line -> total.subtract(line.net) }
}
