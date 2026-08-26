// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.finrep.domain.mapper

import com.openbank.finrep.domain.model.FinrepDataGap

/** Explicit coverage boundary for the EBA Reporting Framework 4.2 bounded previews (#6980). */
object FinrepCoverage {
    private val supportedCells = mapOf(
        "F01.01" to "r0380/c0010",
        "F01.02" to "r0300/c0010",
        "F01.03" to "r0300/c0010, r0310/c0010",
        "F02.00" to "r0670/c0010",
    )

    fun gapsFor(templateId: String): List<FinrepDataGap> {
        val supported = requireNotNull(supportedCells[templateId]) { "Unknown FINREP coverage: $templateId" }
        return listOf(
            FinrepDataGap(
                code = "UNMAPPED_OFFICIAL_CELLS",
                affectedScope = "$templateId except $supported",
                reason = "The ledger lacks the regulatory classifications required to derive the remaining " +
                    "EBA Reporting Framework 4.2 cells; this preview must not be submitted as a return.",
            ),
        )
    }
}
