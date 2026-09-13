// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.finrep.application.usecase

import com.openbank.finrep.application.port.inbound.FinrepUseCase
import com.openbank.finrep.application.port.inbound.GetFinrepTemplateQuery
import com.openbank.finrep.application.port.inbound.GetXbrlCsvPreflightQuery
import com.openbank.finrep.application.port.inbound.XbrlCsvPreflightUseCase
import com.openbank.finrep.domain.model.EbaReportingFramework42
import com.openbank.finrep.domain.model.XbrlCsvBlocker
import com.openbank.finrep.domain.model.XbrlCsvPreflight
import com.openbank.finrep.domain.model.XbrlCsvPreflightState
import jakarta.enterprise.context.ApplicationScoped

/**
 * First safe stage of #5914: decide whether a FINREP preview is even eligible to reach an
 * XBRL-CSV renderer. No archive, instance document or regulator request is constructed here.
 */
@ApplicationScoped
class XbrlCsvPreflightService(private val finrepUseCase: FinrepUseCase) : XbrlCsvPreflightUseCase {

    override suspend fun getPreflight(query: GetXbrlCsvPreflightQuery): XbrlCsvPreflight {
        val template = finrepUseCase.getTemplate(GetFinrepTemplateQuery(query.templateId, query.asOf))
        val blockers = buildList {
            if (template.hasDataGaps) {
                add(
                    XbrlCsvBlocker(
                        code = "INCOMPLETE_OFFICIAL_MAPPING",
                        reason = "This preview has unmapped official EBA cells and cannot be rendered as a return.",
                    ),
                )
            }
            if (!template.isBalanced) {
                add(
                    XbrlCsvBlocker(
                        code = "TRIAL_BALANCE_NOT_AGREED",
                        reason = "The independent trial-balance checks did not agree that this " +
                            "reporting period balances.",
                    ),
                )
            }
        }
        return XbrlCsvPreflight(
            templateId = template.templateId,
            period = template.period,
            reportingFrameworkVersion = EbaReportingFramework42.REPORTING_FRAMEWORK_VERSION,
            dpmVersion = EbaReportingFramework42.DPM_VERSION,
            taxonomyVersion = EbaReportingFramework42.TAXONOMY_VERSION,
            state = if (blockers.isEmpty()) {
                XbrlCsvPreflightState.READY_FOR_RENDERING
            } else {
                XbrlCsvPreflightState.BLOCKED
            },
            blockers = blockers,
        )
    }
}
