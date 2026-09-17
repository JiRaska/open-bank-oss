// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.persistence.repository

import com.openbank.kyb.application.port.out.BusinessOnboardingCaseRepository
import com.openbank.kyb.application.port.out.KybOutboxRepository
import com.openbank.kyb.application.port.out.RegistryExtractCache
import com.openbank.kyb.application.port.out.UboObservationRepository
import com.openbank.kyb.domain.model.BusinessOnboardingCase
import com.openbank.kyb.domain.model.CaseStatus
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.KybEvent
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.domain.model.UboFinding
import com.openbank.kyb.domain.model.UboObservation
import com.openbank.kyb.infrastructure.messaging.UboObservationReference
import com.openbank.kyb.infrastructure.persistence.entity.BusinessOnboardingCaseEntity
import com.openbank.kyb.infrastructure.persistence.entity.RegistryExtractEntity
import com.openbank.kyb.infrastructure.persistence.entity.UboObservationEntity
import com.openbank.kyb.infrastructure.persistence.entity.UboObservationReadEntity
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

@ApplicationScoped
@Suppress("TooManyFunctions") // one query per lookup the use cases need, plus the two mappers
class BusinessOnboardingCaseRepositoryImpl(private val outbox: KybOutboxRepository) :
    BusinessOnboardingCaseRepository,
    UboObservationRepository,
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

    override suspend fun updateWithUboObservation(
        case: BusinessOnboardingCase,
        finding: UboFinding,
        recordedAt: Instant,
    ): UboObservation {
        require(finding.identifier == case.identifier) { "UBO observation identifier differs from case" }
        val json = KybUboJson.write(finding)
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_OBSERVATION_BYTES) { "UBO observation exceeds size limit" }
        val hash = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8)),
        )
        return Panache.withTransaction {
            find("caseId", case.id).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult().flatMap { row ->
                requireNotNull(row) { "case ${case.id} vanished" }.fill(case)
                Panache.getSession().flatMap { session ->
                    session.createQuery(
                        "select coalesce(max(o.revision), 0) from UboObservationEntity o where o.caseId = :caseId",
                        java.lang.Long::class.java,
                    ).setParameter("caseId", case.id).singleResult.flatMap { latest ->
                        val observation = UboObservation(
                            id = Ids.newId(),
                            caseId = case.id,
                            revision = latest.toLong() + 1,
                            finding = finding,
                            sourceSha256 = hash,
                            recordedAt = recordedAt,
                        )
                        session.persist(
                            UboObservationEntity().apply {
                                observationId = observation.id
                                caseId = observation.caseId
                                revision = observation.revision
                                source = finding.source.name
                                sourceSha256 = hash
                                findingJson = json
                                fetchedAt = finding.fetchedAt
                                this.recordedAt = recordedAt
                            },
                        ).flatMap {
                            outbox.persistInTransaction(
                                UboObservationReference.from(observation).toOutboxMessage(recordedAt),
                            ).replaceWith(observation)
                        }
                    }
                }
            }
        }.awaitSuspending()
    }

    override suspend fun findUboObservation(caseId: UUID, observationId: UUID): UboObservation? = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createQuery(
                "from UboObservationEntity o where o.caseId = :caseId and o.observationId = :observationId",
                UboObservationEntity::class.java,
            ).setParameter("caseId", caseId).setParameter("observationId", observationId)
                .resultList.map { rows -> rows.firstOrNull() }
        }
    }.awaitSuspending()?.let { row ->
        UboObservation(
            id = row.observationId,
            caseId = row.caseId,
            revision = row.revision,
            finding = KybUboJson.read(row.findingJson),
            sourceSha256 = row.sourceSha256,
            recordedAt = row.recordedAt,
        )
    }

    override suspend fun recordUboObservationRead(
        caseId: UUID,
        observationId: UUID,
        principalId: String,
        purpose: String,
        readAt: Instant,
    ) {
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.persist(
                    UboObservationReadEntity().apply {
                        readId = Ids.newId()
                        this.caseId = caseId
                        this.observationId = observationId
                        this.principalId = principalId
                        this.purpose = purpose
                        this.readAt = readAt
                    },
                )
            }
        }.awaitSuspending()
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
        requiredSignerRoles = KybJson.writeStrings(case.requiredSignerRoles)
        signersJson = KybJson.writeSigners(case.signers)
        invitationTokens =
            case.signers.mapNotNull { it.invitationToken }.takeIf { it.isNotEmpty() }?.joinToString("|", "|", "|")
        signerPartyIds = case.signers.mapNotNull { it.partyId }.takeIf { it.isNotEmpty() }?.joinToString("|", "|", "|")
        reviewReason = case.reviewReason
        questionnaireJson = case.questionnaire?.let { KybJson.write(it) }
        declarationsJson = case.declarations?.let { KybJson.write(it) }
        agreementJson = case.agreement?.let { KybJson.write(it) }
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
        requiredSignerRoles = KybJson.readStrings(requiredSignerRoles),
        signers = KybJson.readSigners(signersJson),
        reviewReason = reviewReason,
        entityPartyActive = entityPartyActive,
        questionnaire = questionnaireJson?.let { KybJson.readQuestionnaire(it) },
        declarations = declarationsJson?.let { KybJson.readDeclarations(it) },
        agreement = agreementJson?.let { KybJson.readAgreement(it) },
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

    private companion object {
        const val MAX_OBSERVATION_BYTES = 262144
    }
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
