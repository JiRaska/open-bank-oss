// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.port.out

import com.openbank.pension.domain.model.PensionContract
import com.openbank.pension.domain.questionnaire.RefreshReason
import java.util.UUID

/** Judges a strategy change of an existing contract against the participant's assessment in force. */
fun interface StrategySuitabilityPort {
    /** Throws [ReassessmentRequiredException] when the participant must answer the questionnaire again. */
    suspend fun requireSuitable(contract: PensionContract, strategyCode: String)
}

/** 409: answer the questionnaire again on [applicationId] before changing strategy. */
class ReassessmentRequiredException(val applicationId: UUID, val reason: RefreshReason) :
    IllegalStateException("the suitability assessment must be renewed before this strategy change ($reason)")
