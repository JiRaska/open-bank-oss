// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.persistence.repository

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.party.application.port.out.PartyAmlProfileRepository
import com.openbank.party.application.port.out.PartyOutboxRepository
import com.openbank.party.domain.model.AccountPurpose
import com.openbank.party.domain.model.AmlDerivedFacts
import com.openbank.party.domain.model.AmlProfileDeclaration
import com.openbank.party.domain.model.AmlRiskFactor
import com.openbank.party.domain.model.ExpectedMonthlyTurnover
import com.openbank.party.domain.model.IncomeSource
import com.openbank.party.domain.model.Occupation
import com.openbank.party.domain.model.PartyAmlProfile
import com.openbank.party.domain.model.PartyEvent
import com.openbank.party.domain.model.PepCategory
import com.openbank.party.domain.model.PepDeclaration
import com.openbank.party.domain.model.TaxResidency
import com.openbank.party.infrastructure.persistence.entity.PartyAmlProfileEntity
import com.openbank.party.infrastructure.persistence.entity.PartyEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * Versioned AML profile rows. A new declaration demotes the current row (never updates its
 * content), inserts the next version, projects the derived facts onto `parties`, and appends the
 * `PARTY_UPDATED` event — all in one transaction, same discipline as the party aggregate (#4007).
 */
@ApplicationScoped
class PartyAmlProfileRepositoryImpl(
    private val outboxRepository: PartyOutboxRepository,
    private val objectMapper: ObjectMapper,
) : PartyAmlProfileRepository,
    PanacheRepository<PartyAmlProfileEntity> {

    override suspend fun findCurrent(partyId: UUID): PartyAmlProfile? = Panache.withSession {
        find("partyId = ?1 and isCurrent = true", partyId).firstResult()
    }.awaitSuspending()?.toDomain()

    override suspend fun findHistory(partyId: UUID): List<PartyAmlProfile> = Panache.withSession {
        find("partyId = ?1 order by version", partyId).list()
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun saveNewVersion(
        profile: PartyAmlProfile,
        facts: AmlDerivedFacts,
        event: PartyEvent,
    ): PartyAmlProfile {
        val row = profile.toEntity()
        Panache.withTransaction {
            // Demote first and flush: the partial unique index allows one current row per party,
            // and a concurrent declaration that loses the race fails here rather than stacking.
            update("isCurrent = false where partyId = ?1 and isCurrent = true", profile.partyId)
                .flatMap { persist(row) }
                .flatMap { Panache.getSession().flatMap { it.flush() } }
                .flatMap {
                    Panache.getSession().flatMap { session ->
                        session.createMutationQuery(
                            "update ${PartyEntity::class.simpleName} set pepFlag = :pep, pepCategory = :cat, " +
                                "fatcaStatus = :fatca, crsStatus = :crs, updatedAt = :at where partyId = :id",
                        )
                            .setParameter("pep", facts.pepFlag)
                            .setParameter("cat", facts.pepCategory?.name)
                            .setParameter("fatca", facts.fatcaStatus.name)
                            .setParameter("crs", facts.crsStatus.name)
                            .setParameter("at", profile.declaredAt)
                            .setParameter("id", profile.partyId)
                            .executeUpdate()
                    }
                }
                .flatMap { outboxRepository.persistInTransaction(event.toOutboxMessage()) }
        }.awaitSuspending()
        return profile
    }

    private fun PartyAmlProfile.toEntity() = PartyAmlProfileEntity().also {
        val d = declaration
        it.partyId = partyId
        it.version = version
        it.isCurrent = true
        it.purposes = d.purposes.sorted().joinToString(",") { p -> p.name }
        it.purposeNote = d.purposeNote
        it.incomeSources = d.incomeSources.sorted().joinToString(",") { s -> s.name }
        it.incomeNote = d.incomeNote
        it.occupation = d.occupation.name
        it.occupationNote = d.occupationNote
        it.expectedMonthlyTurnover = d.expectedMonthlyTurnover.name
        it.cashIntensive = d.cashIntensive
        it.isPep = d.pep.isPep
        it.pepCategory = d.pep.category?.name
        it.pepDetail = d.pep.detail
        it.taxResidencies = objectMapper.writeValueAsString(d.taxResidencies)
        it.usPerson = d.usPerson
        it.truthful = d.truthful
        it.riskFactors = riskFactors.joinToString(",") { f -> f.name }
        it.declaredAt = declaredAt
        it.declaredBy = declaredBy
    }

    private fun PartyAmlProfileEntity.toDomain() = PartyAmlProfile(
        partyId = partyId,
        version = version,
        declaration = AmlProfileDeclaration(
            purposes = purposes.csv().map { AccountPurpose.valueOf(it) }.toSet(),
            purposeNote = purposeNote,
            incomeSources = incomeSources.csv().map { IncomeSource.valueOf(it) }.toSet(),
            incomeNote = incomeNote,
            occupation = Occupation.valueOf(occupation),
            occupationNote = occupationNote,
            expectedMonthlyTurnover = ExpectedMonthlyTurnover.valueOf(expectedMonthlyTurnover),
            cashIntensive = cashIntensive,
            pep = PepDeclaration(isPep, pepCategory?.let { PepCategory.valueOf(it) }, pepDetail),
            taxResidencies = objectMapper.readValue(taxResidencies, RESIDENCIES),
            usPerson = usPerson,
            truthful = truthful,
        ),
        riskFactors = riskFactors.csv().map { AmlRiskFactor.valueOf(it) },
        declaredAt = declaredAt,
        declaredBy = declaredBy,
    )

    private fun String.csv(): List<String> = split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun PartyEvent.toOutboxMessage() = OutboxMessage(
        aggregateId = aggregateId,
        eventType = eventType,
        payload = objectMapper.writeValueAsString(envelope),
        createdAt = occurredAt,
    )

    private companion object {
        val RESIDENCIES = object : TypeReference<List<TaxResidency>>() {}
    }
}
