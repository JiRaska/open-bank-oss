// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.persistence.repository

import com.openbank.kyb.application.port.out.BusinessOnboardingCaseRepository
import com.openbank.kyb.application.port.out.ConcurrentCaseUpdateException
import com.openbank.kyb.application.port.out.KybOutboxRepository
import com.openbank.kyb.application.port.out.MonitoringRegistrationStore
import com.openbank.kyb.application.port.out.RegistryExtractCache
import com.openbank.kyb.domain.model.BusinessOnboardingCase
import com.openbank.kyb.domain.model.CaseStatus
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.KybEvent
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.MonitoringReview
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.infrastructure.persistence.entity.BusinessOnboardingCaseEntity
import com.openbank.kyb.infrastructure.persistence.entity.RegistryExtractEntity
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.OptimisticLockException
import org.hibernate.StaleStateException
import java.time.Clock
import java.time.Instant
import java.util.UUID

@ApplicationScoped
@Suppress("TooManyFunctions") // one query per lookup the use cases need, plus the two mappers
class BusinessOnboardingCaseRepositoryImpl(
    private val outbox: KybOutboxRepository,
    private val monitoring: MonitoringRegistrationStore,
    private val clock: Clock,
) : BusinessOnboardingCaseRepository,
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

    /**
     * Two guards, and both are needed. The explicit comparison catches the common case: the copy
     * being written was read before somebody else's committed write (the row is loaded fresh here,
     * so `@Version` alone would see the NEW version and happily overwrite). `@Version` then covers
     * the window between this load and the flush, where two transactions hold the same version.
     *
     * The ongoing-monitoring registrations are brought to the case's desired set HERE, inside the
     * transaction and for every write, not by the use cases: there are several paths into and out
     * of an approved case, and a registration that one of them forgot is a customer nobody
     * monitors, with nothing anywhere reporting it.
     */
    override suspend fun update(case: BusinessOnboardingCase, event: KybEvent?): BusinessOnboardingCase {
        val written = Panache.withTransaction {
            find("caseId", case.id).firstResult().flatMap { e ->
                val row = requireNotNull(e) { "case ${case.id} vanished" }
                if (row.version != case.version) {
                    Uni.createFrom().failure(ConcurrentCaseUpdateException(case.id))
                } else {
                    row.fill(case)
                    withEvent(event)
                        .flatMap { withMonitoring(case) }
                        .flatMap { Panache.getSession() }
                        .flatMap { session -> session.flush() }
                        .map { row.version }
                }
            }
        }.onFailure(::isStaleWrite).transform { ConcurrentCaseUpdateException(case.id) }.awaitSuspending()
        return case.copy(version = written)
    }

    /**
     * One statement, and only for a case that is or was approved: no other case can have a row
     * to add or to turn, so the writes of the whole onboarding journey pay nothing for this.
     */
    private fun withMonitoring(case: BusinessOnboardingCase): Uni<Void> = if (!case.everApproved) {
        Uni.createFrom().voidItem()
    } else {
        monitoring.syncInTransaction(
            case.id,
            case.monitoredSubjects.mapNotNull { it.ref }.toSet(),
            Instant.now(clock),
        )
    }

    private fun isStaleWrite(failure: Throwable): Boolean = generateSequence(failure) { it.cause }
        .take(MAX_CAUSE_DEPTH)
        .any { it is OptimisticLockException || it is StaleStateException }

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
            // The review queue also holds approved cases with an OPEN monitoring review: their
            // status does not say so, on purpose, and an operator must still find them here.
            if (status == CaseStatus.MANUAL_REVIEW) {
                find(
                    "status = ?1 or (monitoringReviewOpenedAt is not null and monitoringExitRequiredAt is null) " +
                        "order by updatedAt desc",
                    status.name,
                )
            } else {
                find("status = ?1 order by updatedAt desc", status.name)
            }.page(page, size).list()
        }.awaitSuspending().map { it.toDomain() }

    private fun BusinessOnboardingCaseEntity.fill(case: BusinessOnboardingCase): BusinessOnboardingCaseEntity = apply {
        identifierScheme = case.identifier.scheme.name
        identifierValue = case.identifier.value
        initiatorPartyId = case.initiatorPartyId
        status = case.status.name
        extractJson = case.extract?.let { KybJson.writeExtract(it) }
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
        screeningJson = case.screening?.let { KybScreeningJson.write(it) }
        monitoringReviewOpenedAt = case.monitoringReview?.openedAt
        monitoringExitRequiredAt = case.monitoringReview?.exitRequiredAt
        monitoringExitReason = case.monitoringReview?.exitReason
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
        screening = screeningJson?.let { KybScreeningJson.read(it) },
        monitoringReview = monitoringReviewOpenedAt?.let { opened ->
            MonitoringReview(opened, monitoringExitRequiredAt, monitoringExitReason)
        },
        version = version,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private companion object {
        const val MAX_CAUSE_DEPTH = 8
    }

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
                    e.extractJson = KybJson.writeExtract(extract)
                    e.source = extract.source
                    e.fetchedAt = extract.fetchedAt
                    if (existing == null) persist(e) else Uni.createFrom().item(e)
                }
        }.awaitSuspending()
    }
}
