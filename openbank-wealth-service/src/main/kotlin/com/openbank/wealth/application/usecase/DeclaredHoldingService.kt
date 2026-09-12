// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.wealth.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.wealth.application.port.`in`.DeclareHoldingCommand
import com.openbank.wealth.application.port.`in`.DeclaredHoldingUseCase
import com.openbank.wealth.application.port.`in`.RevalueHoldingCommand
import com.openbank.wealth.application.port.out.DeclaredHoldingRepository
import com.openbank.wealth.application.port.out.HoldingNotFoundException
import com.openbank.wealth.domain.model.DeclaredHolding
import com.openbank.wealth.domain.model.HoldingDeclared
import com.openbank.wealth.domain.model.HoldingRevalued
import com.openbank.wealth.domain.model.HoldingWithdrawn
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.util.UUID

@ApplicationScoped
class DeclaredHoldingService(
    private val repository: DeclaredHoldingRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : DeclaredHoldingUseCase {

    override suspend fun declare(command: DeclareHoldingCommand): DeclaredHolding {
        val reference = command.externalReference?.takeIf { it.isNotBlank() }
        if (reference != null) {
            val existing = repository.findByNaturalKey(command.ownerPartyId, command.holdingType, reference)
            // A replay returns the ORIGINAL, unmodified. It deliberately does not revalue: a
            // retried create carrying a different amount is a caller bug, and silently taking the
            // newer figure would make the retry indistinguishable from an intentional revalue.
            if (existing != null) return existing
        }

        val now = clock.instant()
        val holding = DeclaredHolding(
            id = Ids.newId(),
            ownerPartyId = command.ownerPartyId,
            holdingType = command.holdingType,
            label = command.label,
            valuation = command.valuation,
            ownershipShare = command.ownershipShare,
            externalReference = reference,
            documentIds = command.documentIds,
            createdAt = now,
            updatedAt = now,
        )

        val payload = objectMapper.writeValueAsString(
            HoldingDeclared(
                holdingId = holding.id,
                ownerPartyId = holding.ownerPartyId,
                holdingType = holding.holdingType,
                amount = holding.valuation.amount,
                currency = holding.valuation.currency,
                valuedAt = holding.valuation.valuedAt,
                valuationSource = holding.valuation.source,
                ownershipShare = holding.ownershipShare,
                occurredAt = now,
            ),
        )
        return repository.save(holding, HoldingDeclared.EVENT_TYPE, payload)
    }

    override suspend fun revalue(command: RevalueHoldingCommand): DeclaredHolding {
        val now = clock.instant()
        val current = repository.findById(command.holdingId) ?: throw HoldingNotFoundException(command.holdingId)
        val revalued = current.revalue(command.valuation, now)

        val payload = objectMapper.writeValueAsString(
            HoldingRevalued(
                holdingId = revalued.id,
                ownerPartyId = revalued.ownerPartyId,
                holdingType = revalued.holdingType,
                amount = revalued.valuation.amount,
                currency = revalued.valuation.currency,
                valuedAt = revalued.valuation.valuedAt,
                valuationSource = revalued.valuation.source,
                occurredAt = now,
            ),
        )
        return repository.save(revalued, HoldingRevalued.EVENT_TYPE, payload)
    }

    override suspend fun withdraw(holdingId: UUID): DeclaredHolding {
        val now = clock.instant()
        val current = repository.findById(holdingId) ?: throw HoldingNotFoundException(holdingId)
        val withdrawn = current.withdraw(now)

        val payload = objectMapper.writeValueAsString(
            HoldingWithdrawn(
                holdingId = withdrawn.id,
                ownerPartyId = withdrawn.ownerPartyId,
                holdingType = withdrawn.holdingType,
                occurredAt = now,
            ),
        )
        return repository.save(withdrawn, HoldingWithdrawn.EVENT_TYPE, payload)
    }

    override suspend fun findById(holdingId: UUID): DeclaredHolding? = repository.findById(holdingId)

    override suspend fun listForParty(ownerPartyId: UUID): List<DeclaredHolding> = repository.listForParty(ownerPartyId)
}
