// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain.model

import java.time.Instant
import java.util.UUID

/** Current-revision semantics, derived from the human-confirmed count and the mapped roster. */
enum class StatutoryPolicyMode { SOLE, JOINT_N, JOINT_ALL }

/** A bank-identified person tied to exact rows of the verified registry extract, never to a name. */
data class StatutoryRepresentativeEvidence(
    val partyId: UUID,
    val registryRepresentativeIndices: Set<Int>,
    val officeTags: Set<String>,
)

/** Evidence to project into party-service; it conveys no authority until that service validates it. */
data class StatutoryPolicyEvidence(
    val sourceCaseId: UUID,
    val principalPartyId: UUID,
    val attestationId: UUID,
    val ruleTextHash: String,
    val registrySource: String,
    val registrySourceRef: String?,
    val registryRepresentativeCount: Int,
    val mode: StatutoryPolicyMode,
    val requiredSignatures: Int,
    val requiredOffices: List<String>,
    val eligibleRepresentatives: List<StatutoryRepresentativeEvidence>,
    val evidenceRef: String,
    val effectiveFrom: Instant,
)
