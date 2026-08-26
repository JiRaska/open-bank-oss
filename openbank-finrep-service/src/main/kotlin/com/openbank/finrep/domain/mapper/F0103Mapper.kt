// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.finrep.domain.mapper

import com.openbank.finrep.application.port.out.TrialBalanceSnapshot
import com.openbank.finrep.domain.model.BalanceVerdict
import com.openbank.finrep.domain.model.FinrepCell
import com.openbank.finrep.domain.model.FinrepTemplate
import com.openbank.finrep.domain.model.TrialBalanceAssurance
import java.math.BigDecimal
import java.time.LocalDate

/** Official FINREP F 01.03 equity totals from EBA Reporting Framework 4.2 (#6980). */
object F0103Mapper {
    fun map(snapshot: TrialBalanceSnapshot, asOf: LocalDate): FinrepTemplate {
        fun sum(vararg accountTypes: String): BigDecimal = snapshot.lines
            .filter { it.accountType in accountTypes }
            .fold(BigDecimal.ZERO) { total, line -> total.add(line.net) }

        val equity = sum("EQUITY", "INCOME", "EXPENSE").negate()
        val liabilities = sum("LIABILITY").negate()
        val assessment = TrialBalanceAssurance.assess(snapshot)
        return FinrepTemplate(
            templateId = "F01.03",
            period = asOf,
            cells = listOf(
                FinrepCell("r0300", "c0010", equity),
                FinrepCell("r0310", "c0010", equity.add(liabilities)),
            ),
            dataGaps = FinrepCoverage.gapsFor("F01.03"),
            isBalanced = assessment.verdict == BalanceVerdict.AGREED_BALANCED,
            balanceVerdict = assessment.verdict,
        )
    }
}
