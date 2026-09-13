// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.`in`.CallerPartyId
import com.openbank.delegation.application.port.out.DelegationPortfolioRepository
import com.openbank.delegation.application.port.out.OwnershipVerdict
import com.openbank.delegation.application.port.out.ResourceOwnershipClient
import com.openbank.delegation.domain.model.DelegationPortfolio
import com.openbank.delegation.domain.model.DelegationResourceType
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

class DelegationPortfolioNotFound(id: UUID) : RuntimeException("delegation portfolio not found: $id")
class DelegationPortfolioAccessDenied : RuntimeException("the authenticated active profile does not own this portfolio")
class DelegationPortfolioOwnershipUnavailable(accountId: UUID) :
    RuntimeException("ownership of account $accountId could not be established — refusing the portfolio")

@ApplicationScoped
class DelegationPortfolioService(
    private val repository: DelegationPortfolioRepository,
    private val resourceOwnershipClient: ResourceOwnershipClient,
    private val clock: Clock,
) {
    @Inject
    constructor(
        repository: DelegationPortfolioRepository,
        resourceOwnershipClient: ResourceOwnershipClient,
    ) : this(repository, resourceOwnershipClient, Clock.systemUTC())

    suspend fun create(
        callerPartyId: CallerPartyId,
        ownerPartyId: UUID,
        name: String,
        accountIds: Set<UUID>,
    ): DelegationPortfolio {
        requireOwner(callerPartyId, ownerPartyId)
        val now = OffsetDateTime.now(clock)
        val candidate = DelegationPortfolio(
            ownerPartyId = ownerPartyId,
            name = name.trim(),
            accountIds = accountIds,
            createdAt = now,
            updatedAt = now,
        )
        verifyAccountOwnership(ownerPartyId, candidate.accountIds)
        return repository.save(candidate)
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

    private suspend fun verifyAccountOwnership(ownerPartyId: UUID, accountIds: Set<UUID>) {
        accountIds.forEach { accountId ->
            when (resourceOwnershipClient.verifyOwnership(ownerPartyId, DelegationResourceType.ACCOUNT, accountId)) {
                OwnershipVerdict.OWNED -> Unit
                OwnershipVerdict.NOT_OWNED -> throw DelegationResourceOwnershipException(
                    "active profile $ownerPartyId does not own account $accountId",
                )
                OwnershipVerdict.UNVERIFIABLE -> throw DelegationPortfolioOwnershipUnavailable(accountId)
            }
        }
    }
}
