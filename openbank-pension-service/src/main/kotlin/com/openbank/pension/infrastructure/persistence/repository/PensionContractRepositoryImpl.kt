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
        Panache.withTransaction {
            find("contractId", contract.id).firstResult().flatMap { existing ->
                val entity = (existing ?: PensionContractEntity().apply { contractId = contract.id })
                    .apply { fill(contract) }
                val stored = if (existing == null) persist(entity) else Uni.createFrom().item(entity)
                stored
                    .flatMap { elections.count("contractId", contract.id) }
                    .flatMap { count ->
                        val fresh = contract.strategyHistory.drop(count.toInt()).map { it.toEntity(contract.id) }
                        if (fresh.isEmpty()) Uni.createFrom().voidItem() else elections.persist(fresh)
                    }
            }
        }.awaitSuspending()
        return contract
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
