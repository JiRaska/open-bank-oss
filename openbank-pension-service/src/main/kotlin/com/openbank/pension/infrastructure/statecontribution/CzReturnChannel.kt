// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.statecontribution

import com.openbank.pension.application.port.out.StateContributionReturnChannel
import com.openbank.pension.domain.statecontribution.ReturnReportLine
import com.openbank.pension.domain.statecontribution.ReturnResultLine
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.YearMonth
import java.util.Optional

/** The CZ monthly return report (ZDPS §18(4)–(6)) in the placeholder `cz-mf-state-contribution-v1` format. */
@ApplicationScoped
class CzReturnChannel(
    @param:ConfigProperty(name = "openbank.pension.state-contribution.cz.company-ico")
    private val configuredIco: Optional<String>,
) : StateContributionReturnChannel {

    override val format: String = CzMfStateContributionFormat.FORMAT

    override fun render(month: YearMonth, lines: List<ReturnReportLine>): String {
        val ico = configuredIco.filter { ICO.matches(it) }.orElseThrow {
            IllegalStateException(
                "openbank.pension.state-contribution.cz.company-ico (8 digits) is not configured; nothing filed",
            )
        }
        return CzMfStateContributionFormat.returnReport(ico, month, lines)
    }

    override fun parseResult(payload: String): List<ReturnResultLine> =
        CzMfStateContributionFormat.parseReturnResult(payload)

    private companion object {
        val ICO = Regex("^[0-9]{8}$")
    }
}
