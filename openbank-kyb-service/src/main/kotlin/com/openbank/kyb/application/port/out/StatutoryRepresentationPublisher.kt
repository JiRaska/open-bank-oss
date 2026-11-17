// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application.port.out

import com.openbank.kyb.domain.model.RepresentationMode
import java.time.Instant
import java.util.UUID

data class StatutoryRepresentative(val partyId: UUID, val officeSlots: Set<Int>, val mandateEvidenceHash: String)

data class StatutoryRepresentationEvidence(
    val caseId: UUID,
    val attestationId: UUID,
    val ruleTextHash: String,
    val attestedAt: Instant,
    val mode: RepresentationMode,
    val requiredSignatures: Int,
    val requiredOffices: List<String>,
    val representatives: List<StatutoryRepresentative>,
)

interface StatutoryRepresentationPublisher {
    suspend fun publishStatutoryRule(principalPartyId: UUID, evidence: StatutoryRepresentationEvidence)
}
