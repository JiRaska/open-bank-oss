// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.account.application.port.out

import com.openbank.account.domain.model.PartyMandateProjection
import java.util.UUID

interface PartyMandateProjectionRepository {
    suspend fun upsert(mandate: PartyMandateProjection)
    suspend fun revoke(mandateId: UUID)
    suspend fun findActive(principalPartyId: UUID, agentPartyId: UUID): List<PartyMandateProjection>
}
