// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.application.usecase

import com.openbank.tax.application.port.out.CeuRegisterRepository
import com.openbank.tax.domain.ceu.RegisterFact
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** What became of one observed register event. */
enum class CeuObservation { RECORDED, DUPLICATE, UNPLACEABLE }

/** Feeds the CEÚ register projection (ADR-0367 D7, #15292). */
@ApplicationScoped
class CeuRegisterService(private val repository: CeuRegisterRepository) {

    suspend fun record(fact: RegisterFact): CeuObservation =
        if (repository.append(fact)) CeuObservation.RECORDED else CeuObservation.DUPLICATE

    /**
     * A relation end names only the relation (delegation lifecycle events carry the grant id, not
     * the account). Its account comes from the start. An end whose start was never observed is
     * UNPLACEABLE — a grant on something other than an account, or one that began before this
     * projection existed — and is counted, not invented.
     */
    suspend fun endRelation(
        eventId: UUID,
        relationId: UUID,
        effectiveOn: LocalDate,
        recordedAt: Instant,
        synthetic: Boolean,
    ): CeuObservation {
        val start = repository.startOf(relationId) ?: return CeuObservation.UNPLACEABLE
        return record(
            RegisterFact.RelationEnded(eventId, relationId, start.accountId, effectiveOn, recordedAt, synthetic),
        )
    }

    /** A reinstated grant names neither account nor grantee; both come from its first start. */
    suspend fun reinstateRelation(
        eventId: UUID,
        relationId: UUID,
        effectiveOn: LocalDate,
        recordedAt: Instant,
        synthetic: Boolean,
    ): CeuObservation {
        val start = repository.startOf(relationId) ?: return CeuObservation.UNPLACEABLE
        return record(
            start.copy(eventId = eventId, effectiveOn = effectiveOn, recordedAt = recordedAt, synthetic = synthetic),
        )
    }
}
