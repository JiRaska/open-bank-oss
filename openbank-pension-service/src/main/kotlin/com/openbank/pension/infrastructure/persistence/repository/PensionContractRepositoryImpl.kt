// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.domain.model.Beneficiary
import com.openbank.pension.domain.model.ContractStatus
import com.openbank.pension.domain.model.ContributionFrequency
import com.openbank.pension.domain.model.ContributionSchedule
import com.openbank.pension.domain.model.PensionContract
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.model.StrategyElection
import com.openbank.pension.domain.pack.ProviderType
import com.openbank.pension.infrastructure.persistence.entity.PensionContractEntity
import com.openbank.pension.infrastructure.persistence.entity.StrategyElectionEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.util.UUID

@ApplicationScoped
class PensionContractRepositoryImpl(
    private val elections: StrategyElectionRepository,
    private val objectMapper: ObjectMapper,
) : PensionContractRepository,
    PanacheRepository<PensionContractEntity> {

    /**
     * Contract row and any NEW strategy elections in one transaction. Elections are append-only: the
     * ones already stored are counted and only the tail of the aggregate's history is inserted.
     * An existing row is loaded into the session and mutated, never `persist`ed again — persist on
     * an application-assigned key is INSERT-only (consent-service, #1521).
     */
    override suspend fun save(contract: PensionContract): PensionContract {
        val version = Panache.withTransaction {
            find("contractId", contract.id).firstResult().flatMap { existing ->
                // Optimistic lock (S8): the row must still be at the version this aggregate was read at.
                if (existing != null && existing.rowVersion != contract.version) {
                    throw ConcurrentContractUpdateException(contract.id, existing.rowVersion, contract.version)
                }
                val entity = (existing ?: PensionContractEntity().apply { contractId = contract.id })
                    .apply { fill(contract) }
                val stored = if (existing == null) persist(entity) else Uni.createFrom().item(entity)
                stored
                    .flatMap { elections.count("contractId", contract.id) }
                    .flatMap { count ->
                        val fresh = contract.strategyHistory.drop(count.toInt()).map { it.toEntity(contract.id) }
                        if (fresh.isEmpty()) Uni.createFrom().voidItem() else elections.persist(fresh)
                    }
                    .flatMap { Panache.getSession() }
                    .flatMap { it.flush() }
                    .map { entity.rowVersion }
            }
        }.awaitSuspending()
        return contract.copy(version = version)
    }

    override suspend fun findById(id: UUID): PensionContract? = Panache.withSession {
        find("contractId", id).firstResult().flatMap { entity ->
            if (entity == null) {
                Uni.createFrom().nullItem()
            } else {
                elections.find("contractId = ?1 order by id", id).list()
                    .map { history -> entity.toDomain(history) }
            }
        }
    }.awaitSuspending()

    override suspend fun findByParticipant(participantPartyId: UUID, limit: Int): List<PensionContract> =
        loadAll { find("participantPartyId = ?1 order by createdAt desc", participantPartyId).page(0, limit).list() }

    override suspend fun findByStatus(status: ContractStatus?, limit: Int): List<PensionContract> = loadAll {
        if (status == null) {
            find("order by createdAt desc").page(0, limit).list()
        } else {
            find("status = ?1 order by createdAt desc", status.name).page(0, limit).list()
        }
    }

    /** Each row with its own strategy history (bounded by the caller's page size). */
    private suspend fun loadAll(rows: () -> Uni<List<PensionContractEntity>>): List<PensionContract> =
        Panache.withSession {
            rows().flatMap { entities ->
                if (entities.isEmpty()) {
                    Uni.createFrom().item(emptyList())
                } else {
                    elections.find("contractId in ?1 order by id", entities.map { it.contractId }).list()
                        .map { history ->
                            val byContract = history.groupBy { it.contractId }
                            entities.map { it.toDomain(byContract[it.contractId].orEmpty()) }
                        }
                }
            }
        }.awaitSuspending()

    override suspend fun findByIdempotencyKey(participantPartyId: UUID, idempotencyKey: String): PensionContract? =
        Panache.withSession {
            find("participantPartyId = ?1 and idempotencyKey = ?2", participantPartyId, idempotencyKey)
                .firstResult()
                .flatMap { entity ->
                    if (entity == null) {
                        Uni.createFrom().nullItem()
                    } else {
                        elections.find("contractId = ?1 order by id", entity.contractId).list()
                            .map { history -> entity.toDomain(history) }
                    }
                }
        }.awaitSuspending()

    private fun PensionContractEntity.fill(contract: PensionContract) {
        participantPartyId = contract.participantPartyId
        productLine = contract.productLine.name
        jurisdiction = contract.jurisdiction
        packVersion = contract.packVersion
        providerEntityId = contract.providerEntityId
        providerType = contract.providerType.name
        participantBirthDate = contract.participantBirthDate
        status = contract.status.name
        contributionAmount = contract.schedule.amount
        employerContributionAmount = contract.schedule.employerAmount
        contributionCurrency = contract.schedule.currency
        contributionFrequency = contract.schedule.frequency.name
        beneficiaries = objectMapper.writeValueAsString(contract.beneficiaries.map { it.toRow() })
        startDate = contract.startDate
        idempotencyKey = contract.idempotencyKey
        createdAt = contract.createdAt
        updatedAt = contract.updatedAt
    }

    private fun PensionContractEntity.toDomain(history: List<StrategyElectionEntity>) = PensionContract(
        id = contractId,
        participantPartyId = participantPartyId,
        productLine = ProductLine.valueOf(productLine),
        jurisdiction = jurisdiction,
        packVersion = packVersion,
        providerEntityId = providerEntityId,
        providerType = ProviderType.valueOf(providerType),
        participantBirthDate = participantBirthDate,
        status = ContractStatus.valueOf(status),
        schedule = ContributionSchedule(
            amount = contributionAmount,
            currency = contributionCurrency,
            frequency = ContributionFrequency.valueOf(contributionFrequency),
            employerAmount = employerContributionAmount,
        ),
        strategyHistory = history.map { StrategyElection(it.strategyCode, it.effectiveFrom, it.electedAt) },
        beneficiaries = objectMapper.readValue<List<BeneficiaryRow>>(beneficiaries).map { it.toDomain() },
        startDate = startDate,
        idempotencyKey = idempotencyKey,
        createdAt = createdAt,
        updatedAt = updatedAt,
        version = rowVersion,
    )

    private fun StrategyElection.toEntity(id: UUID) = StrategyElectionEntity().also {
        it.contractId = id
        it.strategyCode = strategyCode
        it.effectiveFrom = effectiveFrom
        it.electedAt = electedAt
    }

    /** Storage shape of a designation, kept apart from the domain type so the domain stays annotation-free. */
    data class BeneficiaryRow(val name: String, val partyId: String?, val sharePercent: BigDecimal) {
        fun toDomain() = Beneficiary(name, partyId?.let(UUID::fromString), sharePercent)
    }

    private fun Beneficiary.toRow() = BeneficiaryRow(name, partyId?.toString(), sharePercent)
}

/** A contract write lost a race (ADR-0334 S8): 409 to a caller, a fresh-read retry for an activity. */
class ConcurrentContractUpdateException(id: UUID, stored: Int, expected: Int) :
    IllegalStateException("contract $id changed concurrently (version $stored, expected $expected)")
