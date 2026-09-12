// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.wealth.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.wealth.application.port.out.DeclaredHoldingRepository
import com.openbank.wealth.application.port.out.RecordedValuation
import com.openbank.wealth.application.port.out.WealthOutboxRepository
import com.openbank.wealth.domain.model.DeclaredHolding
import com.openbank.wealth.domain.model.HoldingStatus
import com.openbank.wealth.domain.model.HoldingType
import com.openbank.wealth.domain.model.Valuation
import com.openbank.wealth.domain.model.ValuationSource
import com.openbank.wealth.infrastructure.persistence.entity.DeclaredHoldingEntity
import com.openbank.wealth.infrastructure.persistence.entity.DeclaredHoldingValuationEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.util.UUID

@ApplicationScoped
class DeclaredHoldingRepositoryImpl(
    private val outbox: WealthOutboxRepository,
    private val valuations: DeclaredHoldingValuationRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : DeclaredHoldingRepository,
    PanacheRepository<DeclaredHoldingEntity> {

    /**
     * Row + outbox entry in ONE transaction — the event is evidence of the state, so they commit
     * together or not at all (ADR-0003). An upsert rather than two methods: the use case works in
     * whole aggregates, and splitting it would put the insert-versus-update decision in the caller
     * where it does not belong.
     */
    override suspend fun save(holding: DeclaredHolding, eventType: String, payload: String): DeclaredHolding {
        val message = OutboxMessage(
            aggregateId = holding.id,
            eventType = eventType,
            payload = payload,
            createdAt = clock.instant(),
        )
        Panache.withTransaction {
            find("holdingId", holding.id).firstResult().flatMap { existing ->
                val entity = existing ?: DeclaredHoldingEntity().apply {
                    holdingId = holding.id
                    createdAt = holding.createdAt
                }
                val valuationChanged = existing == null || existing.valuationDiffersFrom(holding)
                entity.fill(holding)
                // persist() on a NEW entity inserts; an entity already loaded in this session is
                // managed, so mutating it is enough and persist would be a no-op on it.
                val persisted = if (existing == null) persist(entity) else Uni.createFrom().item(entity)
                persisted
                    .flatMap { if (valuationChanged) appendValuation(holding) else Uni.createFrom().voidItem() }
                    .flatMap { outbox.persistInTransaction(message) }
            }
        }.awaitSuspending()
        return holding
    }

    /**
     * Read BEFORE `fill` overwrites the managed entity — afterwards the old value is already gone
     * from the session, which is the same overwrite this table exists to survive.
     */
    private fun DeclaredHoldingEntity.valuationDiffersFrom(holding: DeclaredHolding): Boolean =
        valuationAmount.compareTo(holding.valuation.amount) != 0 ||
            valuationCurrency != holding.valuation.currency ||
            valuedAt != holding.valuation.valuedAt ||
            valuationSource != holding.valuation.source.name

    private fun appendValuation(holding: DeclaredHolding): Uni<Void> = DeclaredHoldingValuationEntity().apply {
        holdingId = holding.id
        valuationAmount = holding.valuation.amount
        valuationCurrency = holding.valuation.currency
        valuedAt = holding.valuation.valuedAt
        valuationSource = holding.valuation.source.name
        appraiserReference = holding.valuation.appraiserReference
        recordedAt = clock.instant()
    }.let { valuations.persist(it).replaceWithVoid() }

    override suspend fun valuationHistory(holdingId: UUID): List<RecordedValuation> = Panache.withSession {
        valuations.find("holdingId = ?1 order by recordedAt desc, id desc", holdingId).list()
    }.awaitSuspending().map {
        RecordedValuation(
            valuation = Valuation(
                amount = it.valuationAmount,
                currency = it.valuationCurrency,
                valuedAt = it.valuedAt,
                source = ValuationSource.valueOf(it.valuationSource),
                appraiserReference = it.appraiserReference,
            ),
            recordedAt = it.recordedAt,
        )
    }

    override suspend fun findById(holdingId: UUID): DeclaredHolding? = Panache.withSession {
        find("holdingId", holdingId).firstResult()
    }.awaitSuspending()?.toDomain()

    override suspend fun findByNaturalKey(
        ownerPartyId: UUID,
        holdingType: HoldingType,
        externalReference: String,
    ): DeclaredHolding? = Panache.withSession {
        find(
            "ownerPartyId = ?1 and holdingType = ?2 and externalReference = ?3",
            ownerPartyId,
            holdingType.name,
            externalReference,
        ).firstResult()
    }.awaitSuspending()?.toDomain()

    /** Withdrawn rows are excluded: the customer's picture is what they hold, not what they held. */
    override suspend fun listForParty(ownerPartyId: UUID): List<DeclaredHolding> = Panache.withSession {
        find(
            "ownerPartyId = ?1 and status <> ?2 order by createdAt desc",
            ownerPartyId,
            HoldingStatus.WITHDRAWN.name,
        ).list()
    }.awaitSuspending().map { it.toDomain() }

    private fun DeclaredHoldingEntity.fill(holding: DeclaredHolding) = apply {
        ownerPartyId = holding.ownerPartyId
        holdingType = holding.holdingType.name
        label = holding.label
        valuationAmount = holding.valuation.amount
        valuationCurrency = holding.valuation.currency
        valuedAt = holding.valuation.valuedAt
        valuationSource = holding.valuation.source.name
        appraiserReference = holding.valuation.appraiserReference
        ownershipShare = holding.ownershipShare
        externalReference = holding.externalReference
        documentIds = objectMapper.writeValueAsString(holding.documentIds.map(UUID::toString))
        status = holding.status.name
        pledgedToLoanId = holding.pledgedToLoanId
        updatedAt = holding.updatedAt
    }

    private fun DeclaredHoldingEntity.toDomain() = DeclaredHolding(
        id = holdingId,
        ownerPartyId = ownerPartyId,
        holdingType = HoldingType.valueOf(holdingType),
        label = label,
        valuation = Valuation(
            amount = valuationAmount,
            currency = valuationCurrency,
            valuedAt = valuedAt,
            source = ValuationSource.valueOf(valuationSource),
            appraiserReference = appraiserReference,
        ),
        ownershipShare = ownershipShare,
        externalReference = externalReference,
        documentIds = objectMapper.readValue(documentIds, Array<String>::class.java).map(UUID::fromString),
        status = HoldingStatus.valueOf(status),
        pledgedToLoanId = pledgedToLoanId,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
