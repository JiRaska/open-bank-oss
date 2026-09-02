// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.application.port.out

import com.openbank.cardissuance.domain.model.DelegatedCardGrant
import java.util.UUID

/** Outbound port for the local delegation-grant enforcement projection (ADR-0232 D3). */
interface CardDelegationProjectionRepository {

    /** Idempotent upsert keyed on the grant id (re-delivered DelegationActivated/Reinstated). */
    suspend fun upsertActive(grant: DelegatedCardGrant)

    /** Close the row (revoke/suspend/renounce/expire). Returns false when unknown — still a no-op. */
    suspend fun closeById(grantId: UUID): Boolean

    suspend fun applyActive(grant: DelegatedCardGrant, lifecycleRevision: Long) = upsertActive(grant)

    suspend fun applyClosed(grantId: UUID, lifecycleRevision: Long?): Boolean = closeById(grantId)

    suspend fun findActiveByCardAndParty(cardId: UUID, partyId: UUID): List<DelegatedCardGrant>
}
