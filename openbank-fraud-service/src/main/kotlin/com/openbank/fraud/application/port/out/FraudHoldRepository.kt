// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.application.port.out

import io.smallrye.mutiny.Uni
import java.time.Instant
import java.util.UUID

/** ADR-0220 D3.5 fraud-hold signal (issue #2749). One row per party — see the entity's KDoc. */
interface FraudHoldRepository {
    suspend fun findActive(partyId: UUID): FraudHoldRecord?

    /** Active holds whose [FraudHoldRecord.expiresAt] is before [now] — the expiry-sweep's input. */
    suspend fun findExpiredActive(now: Instant): List<FraudHoldRecord>

    /**
     * Insert-or-refresh the hold for [partyId] (find-then-persist-or-update, never a naked
     * persist). `Uni`, not `suspend` — the caller (`FraudHoldService`) chains this with the
     * ADR-0050 outbox write inside ONE transaction, so a crash between the two can never raise a
     * hold with no event published, or vice versa.
     */
    fun raise(
        partyId: UUID,
        accountId: UUID,
        reason: String,
        ruleVersion: String,
        setAt: Instant,
        expiresAt: Instant,
    ): Uni<Void>

    /** Same composable-into-a-transaction contract as [raise]. */
    fun clear(partyId: UUID): Uni<Void>
}

data class FraudHoldRecord(
    val partyId: UUID,
    val accountId: UUID,
    val reason: String,
    val ruleVersion: String,
    val setAt: Instant,
    val expiresAt: Instant,
)
