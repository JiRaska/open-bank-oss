// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.`in`.CallerPartyId
import com.openbank.delegation.application.port.out.DelegationPortfolioRepository
import com.openbank.delegation.domain.model.DelegationPortfolio
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

class DelegationPortfolioNotFound(id: UUID) : RuntimeException("delegation portfolio not found: $id")
class DelegationPortfolioAccessDenied : RuntimeException("the authenticated active profile does not own this portfolio")

@ApplicationScoped
class DelegationPortfolioService(
    private val repository: DelegationPortfolioRepository,
    private val clock: Clock,
) {
    @Inject
    constructor(repository: DelegationPortfolioRepository) : this(repository, Clock.systemUTC())

    suspend fun create(callerPartyId: CallerPartyId, ownerPartyId: UUID, name: String, accountIds: Set<UUID>): DelegationPortfolio {
        requireOwner(callerPartyId, ownerPartyId)
        val now = OffsetDateTime.now(clock)
        return repository.save(DelegationPortfolio(ownerPartyId = ownerPartyId, name = name.trim(), accountIds = accountIds, createdAt = now, updatedAt = now))
    }

    suspend fun list(callerPartyId: CallerPartyId, ownerPartyId: UUID): List<DelegationPortfolio> {
        requireOwner(callerPartyId, ownerPartyId)
        return repository.findByOwner(ownerPartyId)
    }

    suspend fun get(callerPartyId: CallerPartyId, id: UUID): DelegationPortfolio {
        val portfolio = repository.findById(id) ?: throw DelegationPortfolioNotFound(id)
        requireOwner(callerPartyId, portfolio.ownerPartyId)
        return portfolio
    }

    private fun requireOwner(callerPartyId: CallerPartyId, ownerPartyId: UUID) {
        if (callerPartyId != ownerPartyId) throw DelegationPortfolioAccessDenied()
    }
}
