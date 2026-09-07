// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.persistence.repository

import com.openbank.kyb.application.port.out.BusinessOnboardingCaseRepository
import com.openbank.kyb.application.port.out.KybOutboxRepository
import com.openbank.kyb.application.port.out.RegistryExtractCache
import com.openbank.kyb.domain.model.BusinessOnboardingCase
import com.openbank.kyb.domain.model.CaseStatus
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.KybEvent
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.infrastructure.persistence.entity.BusinessOnboardingCaseEntity
import com.openbank.kyb.infrastructure.persistence.entity.RegistryExtractEntity
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

@ApplicationScoped
@Suppress("TooManyFunctions") // one query per lookup the use cases need, plus the two mappers
class BusinessOnboardingCaseRepositoryImpl(private val outbox: KybOutboxRepository) :
    BusinessOnboardingCaseRepository,
    PanacheRepository<BusinessOnboardingCaseEntity> {

    /** Row + outbox entry in ONE transaction — the event is evidence of the state, so they commit together or not at all. */
    override suspend fun save(case: BusinessOnboardingCase, event: KybEvent?): BusinessOnboardingCase {
        val e = BusinessOnboardingCaseEntity().apply {
            caseId = case.id
            createdAt = case.createdAt
        }.fill(case)
        Panache.withTransaction { persist(e).flatMap { withEvent(event) } }.awaitSuspending()
        return case
    }

    override suspend fun update(case: BusinessOnboardingCase, event: KybEvent?): BusinessOnboardingCase {
        Panache.withTransaction {
            find("caseId", case.id).firstResult().flatMap { e ->
                requireNotNull(e) { "case ${case.id} vanished" }.fill(case)
                withEvent(event)
            }
        }.awaitSuspending()
        return case
    }

    private fun withEvent(event: KybEvent?): Uni<Void> =
        if (event == null) Uni.createFrom().voidItem() else outbox.persistInTransaction(event.toOutboxMessage())

    override suspend fun findById(id: UUID): BusinessOnboardingCase? =
        Panache.withSession { find("caseId", id).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findOpenByIdentifier(identifier: LegalEntityIdentifier): BusinessOnboardingCase? =
        Panache.withSession {
            find(
                "identifierScheme = ?1 and identifierValue = ?2 and status not in (?3, ?4, ?5) order by createdAt desc",
                identifier.scheme.name,
                identifier.value,
                CaseStatus.ACTIVE.name,
                CaseStatus.REJECTED.name,
                CaseStatus.ABANDONED.name,
            ).firstResult()
        }.awaitSuspending()?.toDomain()

    override suspend fun findByInvitationToken(token: String): BusinessOnboardingCase? = Panache.withSession {
        find("invitationTokens like ?1", "%|$token|%").firstResult()
    }.awaitSuspending()?.toDomain()

    override suspend fun findByEntityPartyId(entityPartyId: UUID): BusinessOnboardingCase? = Panache.withSession {
        find("entityPartyId = ?1 order by createdAt desc", entityPartyId).firstResult()
    }.awaitSuspending()?.toDomain()

    override suspend fun findInvolving(partyId: UUID): List<BusinessOnboardingCase> = Panache.withSession {
        find("initiatorPartyId = ?1 or signerPartyIds like ?2 order by createdAt desc", partyId, "%|$partyId|%").list()
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun listByStatus(status: CaseStatus, page: Int, size: Int): List<BusinessOnboardingCase> =
        Panache.withSession {
            find("status = ?1 order by updatedAt desc", status.name).page(page, size).list()
        }.awaitSuspending().map { it.toDomain() }

    private fun BusinessOnboardingCaseEntity.fill(case: BusinessOnboardingCase): BusinessOnboardingCaseEntity = apply {
        identifierScheme = case.identifier.scheme.name
        identifierValue = case.identifier.value
        initiatorPartyId = case.initiatorPartyId
        status = case.status.name
        extractJson = case.extract?.let { KybJson.write(it) }
        entityPartyId = case.entityPartyId
        entityPartyActive = case.entityPartyActive
        requiredSignatures = case.requiredSignatures
        signersJson = KybJson.writeSigners(case.signers)
        invitationTokens =
            case.signers.mapNotNull { it.invitationToken }.takeIf { it.isNotEmpty() }?.joinToString("|", "|", "|")
        signerPartyIds = case.signers.mapNotNull { it.partyId }.takeIf { it.isNotEmpty() }?.joinToString("|", "|", "|")
        reviewReason = case.reviewReason
        updatedAt = case.updatedAt
    }

    private fun BusinessOnboardingCaseEntity.toDomain() = BusinessOnboardingCase(
        id = caseId,
        identifier = LegalEntityIdentifier.of(IdentifierScheme.valueOf(identifierScheme), identifierValue),
        initiatorPartyId = initiatorPartyId,
        status = CaseStatus.valueOf(status),
        extract = extractJson?.let { KybJson.readExtract(it) },
        entityPartyId = entityPartyId,
        requiredSignatures = requiredSignatures,
        signers = KybJson.readSigners(signersJson),
        reviewReason = reviewReason,
        entityPartyActive = entityPartyActive,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    /** The typed payload is what goes on the wire; Jackson keeps declaration order, which the contract documents. */
    private fun KybEvent.toOutboxMessage() = OutboxMessage(
        aggregateId = aggregateId,
        eventType = eventType,
        payload = KybJson.mapper.writeValueAsString(payload),
        createdAt = occurredAt,
    )
}

@ApplicationScoped
class RegistryExtractCacheImpl :
    RegistryExtractCache,
    PanacheRepository<RegistryExtractEntity> {

    override suspend fun find(identifier: LegalEntityIdentifier, notOlderThan: Instant): RegistryExtract? =
        Panache.withSession {
            find(
                "identifierScheme = ?1 and identifierValue = ?2 and fetchedAt >= ?3 order by fetchedAt desc",
                identifier.scheme.name,
                identifier.value,
                notOlderThan,
            ).firstResult()
        }.awaitSuspending()?.let { KybJson.readExtract(it.extractJson) }

    override suspend fun put(extract: RegistryExtract) {
        Panache.withTransaction {
            find(
                "identifierScheme = ?1 and identifierValue = ?2",
                extract.identifier.scheme.name,
                extract.identifier.value,
            )
                .firstResult()
                .flatMap { existing ->
                    val e = existing ?: RegistryExtractEntity().apply {
                        identifierScheme = extract.identifier.scheme.name
                        identifierValue = extract.identifier.value
                    }
                    e.extractJson = KybJson.write(extract)
                    e.source = extract.source
                    e.fetchedAt = extract.fetchedAt
                    if (existing == null) persist(e) else Uni.createFrom().item(e)
                }
        }.awaitSuspending()
    }
}
