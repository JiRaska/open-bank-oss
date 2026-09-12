// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.`in`.CallerPartyId
import com.openbank.delegation.application.port.`in`.DelegationRecertificationUseCase
import com.openbank.delegation.application.port.out.DelegationRecertificationRepository
import com.openbank.delegation.domain.model.DelegationRecertificationCycle
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/** Customer review actions: explicit, party-scoped and deliberately authority-neutral. */
class DelegationRecertificationConflict(message: String) : RuntimeException(message)

@ApplicationScoped
class DelegationRecertificationService(
    private val repository: DelegationRecertificationRepository,
    private val clock: Clock,
) : DelegationRecertificationUseCase {

    @Inject
    constructor(repository: DelegationRecertificationRepository) : this(repository, Clock.systemUTC())

    override suspend fun listPending(
        grantorPartyId: UUID,
        callerPartyId: CallerPartyId,
    ): List<DelegationRecertificationCycle> {
        requireCallerIs(callerPartyId, grantorPartyId)
        return repository.listPendingByGrantor(grantorPartyId)
    }

    override suspend fun confirm(
        recertificationId: UUID,
        grantorPartyId: UUID,
        callerPartyId: CallerPartyId,
    ): DelegationRecertificationCycle {
        requireCallerIs(callerPartyId, grantorPartyId)
        return repository.confirm(recertificationId, grantorPartyId, OffsetDateTime.now(clock))
    }

    private fun requireCallerIs(callerPartyId: CallerPartyId, partyId: UUID) {
        if (callerPartyId != null && callerPartyId != partyId) {
            throw DelegationCallerMismatchException(callerPartyId, partyId)
        }
    }
}
