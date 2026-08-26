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

/** Official FINREP F 01.02 liabilities total: r0300/c0010, datapoint 32592 (Framework 4.2). */
object F0102Mapper {
    fun map(snapshot: TrialBalanceSnapshot, asOf: LocalDate): FinrepTemplate {
        val liabilities = snapshot.lines
            .filter { it.accountType == "LIABILITY" }
            .fold(BigDecimal.ZERO) { total, line -> total.add(line.net) }
            .negate()
        val assessment = TrialBalanceAssurance.assess(snapshot)
        return FinrepTemplate(
            templateId = "F01.02",
            period = asOf,
            cells = listOf(FinrepCell("r0300", "c0010", liabilities)),
            dataGaps = FinrepCoverage.gapsFor("F01.02"),
            isBalanced = assessment.verdict == BalanceVerdict.AGREED_BALANCED,
            balanceVerdict = assessment.verdict,
        )
    }
}
