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
import com.openbank.kyb.domain.model.CaseMonitoring
import com.openbank.kyb.domain.model.CaseStatus
import com.openbank.kyb.domain.model.ExitCompletion
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.KybEvent
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.MonitoringFinding
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.infrastructure.persistence.entity.BusinessOnboardingCaseEntity
import com.openbank.kyb.infrastructure.persistence.entity.RegistryExtractEntity
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
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
        }.fill(case, syncedVersion = 0)
        Panache.withTransaction { persist(e).flatMap { withEvent(event) } }.awaitSuspending()
        return case
    }

    /**
     * Two guards, and both are needed. The explicit comparison catches the common case: the copy
     * being written was read before somebody else's committed write (the row is loaded fresh here,
     * so `@Version` alone would see the NEW version and happily overwrite). `@Version` then covers
     * the window between this load and the flush, where two transactions hold the same version.
     */
    override suspend fun update(case: BusinessOnboardingCase, event: KybEvent?): BusinessOnboardingCase {
        val written = Panache.withTransaction {
            // The case row is locked BEFORE its version is compared, and everything below — the
            // registration rows, the findings — is written under that lock. So a version that
            // matches here cannot move before this write commits: a finding row that then does not
            // take this write's decision is a genuine conflict, never a race lost to another
            // writer. Lock order is case row first, then its monitoring rows, on every path; the
            // relay never holds a monitoring row while it waits for a case row.
            find("caseId", case.id).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult().flatMap { e ->
                val row = requireNotNull(e) { "case ${case.id} vanished" }
                if (row.version != case.version) {
                    Uni.createFrom().failure(ConcurrentCaseUpdateException(case.id))
                } else {
                    // The version this write produces: the store is in step with the case at it.
                    row.fill(case, syncedVersion = row.version + 1)
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
     * Brings the monitoring store to the case, inside this transaction and for EVERY write, not
     * from the use cases: there are several paths into and out of an approved case, and a
     * registration one of them forgot is a customer nobody monitors, with nothing reporting it.
     *
     * At most two statements, and none for a case that was never approved: such a case can have
     * neither a registration nor a finding, so the whole onboarding journey pays nothing.
     */
    private fun withMonitoring(case: BusinessOnboardingCase): Uni<Void> {
        if (!case.everApproved) return Uni.createFrom().voidItem()
        val keys = case.monitoredSubjects.map { it.key }.toSet()
        val synced = monitoring.syncInTransaction(case.id, keys, Instant.now(clock))
        val findings = case.monitoring.findings
        return if (findings.isEmpty()) {
            synced
        } else {
            synced.flatMap {
                monitoring.saveFindingsInTransaction(case.id, findings)
            }
        }
    }

    private fun isStaleWrite(failure: Throwable): Boolean = generateSequence(failure) { it.cause }
        .take(MAX_CAUSE_DEPTH)
        .any { it is OptimisticLockException || it is StaleStateException }

    private fun withEvent(event: KybEvent?): Uni<Void> =
        if (event == null) Uni.createFrom().voidItem() else outbox.persistInTransaction(event.toOutboxMessage())

    override suspend fun findById(id: UUID): BusinessOnboardingCase? =
        Panache.withSession { find("caseId", id).firstResult() }.awaitSuspending()?.hydrated()

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
        }.awaitSuspending()?.hydrated()

    override suspend fun findByInvitationToken(token: String): BusinessOnboardingCase? = Panache.withSession {
        find("invitationTokens like ?1", "%|$token|%").firstResult()
    }.awaitSuspending()?.hydrated()

    override suspend fun findByEntityPartyId(entityPartyId: UUID): BusinessOnboardingCase? = Panache.withSession {
        find("entityPartyId = ?1 order by createdAt desc", entityPartyId).firstResult()
    }.awaitSuspending()?.hydrated()

    override suspend fun findInvolving(partyId: UUID): List<BusinessOnboardingCase> = Panache.withSession {
        find("initiatorPartyId = ?1 or signerPartyIds like ?2 order by createdAt desc", partyId, "%|$partyId|%").list()
    }.awaitSuspending().map { it.hydrated() }

    override suspend fun listMonitoringHolds(page: Int, size: Int): List<BusinessOnboardingCase> = Panache.withSession {
        find(
            "monitoringReviewSince is not null or monitoringExitOwedSince is not null " +
                "order by coalesce(monitoringExitOwedSince, monitoringReviewSince), id",
        ).page(page, size).list()
    }.awaitSuspending().map { it.hydrated() }

    /**
     * The findings live in their own table, which a release that does not know monitoring never
     * writes. They are read only for a case that has any.
     */
    private suspend fun BusinessOnboardingCaseEntity.hydrated(): BusinessOnboardingCase {
        val findings = if (monitoringFindingCount > 0) monitoring.findingsOf(caseId) else emptyList()
        return toDomain(findings)
    }

    override suspend fun listByStatus(status: CaseStatus, page: Int, size: Int): List<BusinessOnboardingCase> =
        Panache.withSession {
            find("status = ?1 order by updatedAt desc", status.name).page(page, size).list()
        }.awaitSuspending().map { it.hydrated() }

    private fun BusinessOnboardingCaseEntity.fill(
        case: BusinessOnboardingCase,
        syncedVersion: Long,
    ): BusinessOnboardingCaseEntity = apply {
        identifierScheme = case.identifier.scheme.name
        identifierValue = case.identifier.value
        initiatorPartyId = case.initiatorPartyId
        status = case.status.name
        extractJson = case.extract?.let { KybJson.writeExtract(it) }
        entityPartyId = case.entityPartyId
        entityPartyActive = case.entityPartyActive
        requiredSignatures = case.requiredSignatures
        requiredSignerRoles = KybJson.writeStrings(case.requiredSignerRoles)
        representationAttestationId = case.representationAttestationId
        signersJson = KybJson.writeSigners(case.signers)
        invitationTokens =
            case.signers.mapNotNull { it.invitationToken }.takeIf { it.isNotEmpty() }?.joinToString("|", "|", "|")
        signerPartyIds = case.signers.mapNotNull { it.partyId }.takeIf { it.isNotEmpty() }?.joinToString("|", "|", "|")
        reviewReason = case.reviewReason
        questionnaireJson = case.questionnaire?.let { KybJson.write(it) }
        declarationsJson = case.declarations?.let { KybJson.write(it) }
        agreementJson = case.agreement?.let { KybJson.write(it) }
        screeningJson = case.screening?.let { KybScreeningJson.write(it) }
        monitoringFindingCount = case.monitoring.findings.size
        monitoringReviewSince = case.monitoring.reviewSince
        monitoringExitOwedSince = case.monitoring.exitOwedSince
        monitoringExitCompletedAt = case.monitoring.exitCompleted?.completedAt
        monitoringExitCompletedBy = case.monitoring.exitCompleted?.completedBy
        monitoringExitEvidence = case.monitoring.exitCompleted?.evidence
        monitoringSyncedVersion = syncedVersion
        updatedAt = case.updatedAt
    }

    private fun BusinessOnboardingCaseEntity.toDomain(findings: List<MonitoringFinding>) = BusinessOnboardingCase(
        id = caseId,
        identifier = LegalEntityIdentifier.of(IdentifierScheme.valueOf(identifierScheme), identifierValue),
        initiatorPartyId = initiatorPartyId,
        status = CaseStatus.valueOf(status),
        extract = extractJson?.let { KybJson.readExtract(it) },
        entityPartyId = entityPartyId,
        requiredSignatures = requiredSignatures,
        requiredSignerRoles = KybJson.readStrings(requiredSignerRoles),
        signers = KybJson.readSigners(signersJson),
        representationAttestationId = representationAttestationId,
        reviewReason = reviewReason,
        entityPartyActive = entityPartyActive,
        questionnaire = questionnaireJson?.let { KybJson.readQuestionnaire(it) },
        declarations = declarationsJson?.let { KybJson.readDeclarations(it) },
        agreement = agreementJson?.let { KybJson.readAgreement(it) },
        screening = screeningJson?.let { KybScreeningJson.read(it) },
        monitoring = CaseMonitoring(
            findings,
            monitoringExitCompletedAt?.let { at ->
                ExitCompletion(at, monitoringExitCompletedBy.orEmpty(), monitoringExitEvidence.orEmpty())
            },
        ),
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
