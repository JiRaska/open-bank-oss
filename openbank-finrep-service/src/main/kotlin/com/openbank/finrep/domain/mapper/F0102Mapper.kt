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
        // Central banks (r0130): ČNB lombard borrowing 2320 only.
        val centralBank = creditBalance(lines, "2320")
        // Credit institutions (r0150): interbank deposits 2300/2301, nostro overdrafts, and accrued
        // interest payable 2310/2311. The accrual is shared with the lombard facility and cannot be
        // split by counterparty (accepted limitation, README), so it is reported here.
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
                FinrepCell("r0130", "c0010", centralBank),
                FinrepCell("r0150", "c0010", creditInstitutions),
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
