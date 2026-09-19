// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.application.port.out

import com.openbank.fraud.domain.model.FraudInvestigationCase
import java.util.UUID

interface FraudInvestigationCaseStore {
    suspend fun find(caseId: UUID): FraudInvestigationCase?

    /** Source-side equality search restricted to case IDs already assigned by Context. */
    suspend fun matchingAssigned(caseId: UUID, candidateIds: List<UUID>): List<UUID>

    suspend fun open(scoreId: UUID, actorId: String): FraudInvestigationCase?

    suspend fun closeWithoutFinding(caseId: UUID, actorId: String): FraudInvestigationCase?
}
