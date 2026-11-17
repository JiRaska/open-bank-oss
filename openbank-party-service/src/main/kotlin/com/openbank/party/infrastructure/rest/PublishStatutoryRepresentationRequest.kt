// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.rest

import com.openbank.party.domain.model.JointRepresentationMode
import com.openbank.party.domain.model.StatutoryRepresentationEvidence
import com.openbank.party.domain.model.StatutoryRepresentative
import java.time.Instant
import java.util.UUID

data class PublishStatutoryRepresentationRequest(
    val caseId: UUID,
    val attestationId: UUID,
    val ruleTextHash: String,
    val attestedAt: Instant,
    val mode: JointRepresentationMode,
    val requiredSignatures: Int,
    val requiredOffices: List<String?>,
    val representatives: List<StatutoryRepresentativeRequest?>,
) {
    fun toDomain() = StatutoryRepresentationEvidence(
        caseId,
        attestationId,
        ruleTextHash,
        attestedAt,
        mode,
        requiredSignatures,
        requiredOffices.mapIndexed { index, office ->
            requireNotNull(office) { "requiredOffices[$index] is required" }
        },
        representatives.mapIndexed { index, member ->
            requireNotNull(member) { "representatives[$index] is required" }.toDomain(index)
        },
    )
}

data class StatutoryRepresentativeRequest(
    val partyId: UUID,
    val officeSlots: List<Int?>,
    val mandateEvidenceHash: String,
) {
    fun toDomain(memberIndex: Int): StatutoryRepresentative {
        val slots = officeSlots.mapIndexed { index, slot ->
            requireNotNull(slot) { "representatives[$memberIndex].officeSlots[$index] is required" }
        }.toSet()
        require(slots.size == officeSlots.size) { "representatives[$memberIndex].officeSlots must be distinct" }
        return StatutoryRepresentative(partyId, slots, mandateEvidenceHash)
    }
}
