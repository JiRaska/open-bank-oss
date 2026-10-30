// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.testsupport

import com.openbank.pension.application.exit.ScaOperation
import com.openbank.pension.application.exit.ScaVerificationPort
import com.openbank.pension.application.port.out.StrategyApproval
import com.openbank.pension.application.port.out.StrategySuitabilityPort
import com.openbank.pension.application.port.out.StrategySuitabilityRequest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Records every suitability decision; [refusal] (when set) is thrown instead of approving. */
class RecordingSuitability(var refusal: (() -> Throwable)? = null) : StrategySuitabilityPort {
    val asked = CopyOnWriteArrayList<StrategySuitabilityRequest>()
    val recorded = CopyOnWriteArrayList<StrategyApproval>()

    override suspend fun authorize(request: StrategySuitabilityRequest): StrategyApproval {
        asked += request
        refusal?.let { throw it() }
        return StrategyApproval(null, emptyList())
    }

    override suspend fun record(approval: StrategyApproval) {
        recorded += approval
    }
}

/** Single-use SCA fake: accepts any id starting with "sca-" once; records what was signed. */
class RecordingSca : ScaVerificationPort {
    val spent = CopyOnWriteArrayList<Triple<String, String, ScaOperation>>()

    override suspend fun verify(
        partyId: UUID,
        challengeId: String,
        documentSha256: String,
        operation: ScaOperation,
    ): Boolean {
        if (!challengeId.startsWith("sca-") || spent.any { it.first == challengeId }) return false
        spent += Triple(challengeId, documentSha256, operation)
        return true
    }
}
