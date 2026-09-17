// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.application.usecase

import com.openbank.party.application.port.out.RepresentationPolicyRepository
import com.openbank.party.domain.model.RepresentationPolicySnapshot
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/** Read-only evidence query. A consumer must still verify the current attestation and mandates. */
@ApplicationScoped
class RepresentationPolicyQuery(private val policies: RepresentationPolicyRepository) {
    suspend fun latestEvidence(principalPartyId: UUID): RepresentationPolicySnapshot? =
        policies.findLatestEffective(principalPartyId)
}
