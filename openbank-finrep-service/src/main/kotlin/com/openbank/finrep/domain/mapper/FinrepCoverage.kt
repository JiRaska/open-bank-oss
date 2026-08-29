// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.finrep.domain.mapper

import com.openbank.finrep.application.port.out.TrialBalanceLineDto
import com.openbank.finrep.domain.model.FinrepDataGap

/** Fail-closed coverage boundary for the EBA Reporting Framework 4.2 mappings (#6980). */
object FinrepCoverage {
    private val classifiedAccounts = setOf(
        "1000", "1001",
        "1100", "1101", "1102", "1103",
        "1200", "1201", "1202", "1203",
        "1300", "1301", "1302", "1303",
        "1400", "1401", "1402", "1403",
        "1990", "1991", "1992", "1993", "1995", "1996", "1997",
        "2000", "2001", "2002", "2100", "2101", "2102", "2103", "2200",
        "3000", "4000", "4001", "4002", "4003",
        "4010", "4011", "4012", "4013",
        "4100", "4101", "4102", "4103",
        "5100", "5101", "5102", "5103", "5900",
        "6000", "6010", "6020", "6030", "6040", "6050", "6060",
    )

    fun gapsFor(templateId: String, lines: List<TrialBalanceLineDto>): List<FinrepDataGap> {
        val unknown = lines.map { it.code }.filterNot { it in classifiedAccounts }.distinct().sorted()
        return if (unknown.isEmpty()) {
            emptyList()
        } else {
            listOf(
                FinrepDataGap(
                    code = "UNCLASSIFIED_GL_ACCOUNT",
                    affectedScope = "$templateId: ${unknown.joinToString()}",
                    reason = "The trial balance contains an active GL account with no governed EBA 4.2 mapping. " +
                        "The return remains blocked until the chart classification is extended.",
                ),
            )
        }
    }
}
