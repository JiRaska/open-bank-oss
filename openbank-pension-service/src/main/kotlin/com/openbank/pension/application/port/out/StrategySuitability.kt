// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.port.out

import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.questionnaire.RefreshReason
import com.openbank.pension.domain.questionnaire.WarningAcknowledgement
import com.openbank.pension.domain.questionnaire.WarningCode
import java.util.UUID

/** A strategy someone wants a contract to hold: at draft creation or as a change. */
data class StrategySuitabilityRequest(
    /** null at draft creation (no contract yet). */
    val contractId: UUID?,
    val jurisdiction: String,
    val productLine: ProductLine,
    val packVersion: Int,
    val strategyCode: String,
    /** Warnings the participant acknowledged on the screen that requested this change. */
    val acknowledged: Set<WarningCode>,
    val language: String?,
)

/** What may be recorded once the change is stored: the acknowledgements bound to the wording shown. */
data class StrategyApproval(val applicationId: UUID?, val acknowledgements: List<WarningAcknowledgement>)

/**
 * The ONE suitability gate every strategy-setting path goes through (S1 draft creation and every
 * later change; onboarding's own choice uses the same WarningPolicy). It lives in the use case, not
 * in REST, so no route, workflow or operator path can set a strategy around it.
 */
interface StrategySuitabilityPort {
    /** Throws [ReassessmentRequiredException], [StrategyWarningsRequiredException] or [StrategyNotPermittedException]. */
    suspend fun authorize(request: StrategySuitabilityRequest): StrategyApproval

    /** Persists the acknowledgements of an authorized change (audit: hash of the exact wording shown). */
    suspend fun record(approval: StrategyApproval)
}

/** 409: answer the questionnaire again on [applicationId] before changing strategy. */
class ReassessmentRequiredException(val applicationId: UUID, val reason: RefreshReason) :
    IllegalStateException("the suitability assessment must be renewed before this strategy change ($reason)")

/** 409: show these warnings and repeat the change with them acknowledged. */
class StrategyWarningsRequiredException(val warnings: Set<WarningCode>) :
    IllegalStateException("the strategy change needs these warnings acknowledged: ${warnings.joinToString()}")

/** 403: the regime does not allow this strategy for this participant (MiFID unsuitable, or no assessment). */
class StrategyNotPermittedException(message: String) : RuntimeException(message)
