// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.port.out

import com.openbank.account.domain.model.DelegatedAccessGrant
import java.util.UUID

/** Outbound port for the local delegation-grant enforcement projection (ADR-0232 D3). */
interface DelegationProjectionRepository {

    /** Idempotent upsert keyed on the grant id (re-delivered DelegationActivated/Reinstated). */
    suspend fun upsertActive(grant: DelegatedAccessGrant)

    /** Close the row (revoke/suspend/renounce/expire). Returns false when unknown — still a no-op. */
    suspend fun closeById(grantId: UUID): Boolean

    suspend fun applyActive(grant: DelegatedAccessGrant, lifecycleRevision: Long) = upsertActive(grant)

    suspend fun applyClosed(grantId: UUID, lifecycleRevision: Long?): Boolean = closeById(grantId)

    suspend fun findActiveByAccountAndParty(accountId: UUID, partyId: UUID): List<DelegatedAccessGrant>

    /**
     * Every active grant on an account, whoever holds it.
     *
     * The guard only ever asks about one candidate party at a time, so nothing needed this until
     * the account owner had to be shown who can act on their account. Answering that from
     * per-party lookups would require already knowing the answer.
     */
    suspend fun findActiveByAccount(accountId: UUID): List<DelegatedAccessGrant>

    /** Same lookup restricted to one resource type (ACCOUNT guard vs SAVINGS_GOAL guard). */
    suspend fun findActiveByAccountPartyAndType(
        accountId: UUID,
        partyId: UUID,
        resourceType: String,
    ): List<DelegatedAccessGrant>
}
