// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.persistence.repository

import com.openbank.pid.application.port.out.VerificationCaseRepository
import com.openbank.pid.application.usecase.PartyErasedException
import com.openbank.pid.domain.model.VerificationCase
import com.openbank.pid.domain.model.VerificationCaseStatus
import com.openbank.pid.infrastructure.persistence.entity.PartyEntity
import com.openbank.pid.infrastructure.persistence.entity.VerificationCaseEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Persistence for four-eyes identity-verification cases. Every Panache op is wrapped in
 * [Panache.withSession]/[Panache.withTransaction] because these are `suspend` methods and the
 * request-scoped auto-session is not propagated across the coroutine (mirrors PartyRepositoryImpl).
 */
@ApplicationScoped
class VerificationCaseRepositoryImpl :
    VerificationCaseRepository,
    PanacheRepository<VerificationCaseEntity> {

    override suspend fun findById(id: UUID): VerificationCase? =
        Panache.withSession { find("id", id).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findActiveByDedupKey(dedupKey: String): VerificationCase? = Panache.withSession {
        find("dedupKey = ?1 and status != ?2", dedupKey, VerificationCaseStatus.DECIDED.name).firstResult()
    }.awaitSuspending()?.toDomain()

    override suspend fun findLatestDecidedByDedupKey(dedupKey: String): VerificationCase? = Panache.withSession {
        find(
            "dedupKey = ?1 and status = ?2 order by decidedAt desc",
            dedupKey,
            VerificationCaseStatus.DECIDED.name,
        ).firstResult()
    }.awaitSuspending()?.toDomain()

    override suspend fun listByStatuses(statuses: List<VerificationCaseStatus>): List<VerificationCase> =
        Panache.withSession {
            find("status in ?1 order by createdAt desc", statuses.map { it.name }).list()
        }.awaitSuspending().map { it.toDomain() }

    override suspend fun save(case: VerificationCase): VerificationCase {
        Panache.withTransaction { persist(VerificationCaseEntity.fromDomain(case)) }.awaitSuspending()
        return case
    }

    override suspend fun update(case: VerificationCase): VerificationCase {
        Panache.withTransaction {
            find("id", case.id).firstResult().flatMap { existing ->
                val current = requireNotNull(existing) { "verification case ${case.id} not found" }
                // GDPR Art. 17 (#15755): an erased case is closed history; no step may write to it.
                if (current.erasedAt != null) throw PartyErasedException("verification case ${case.id} was erased")
                // A newly chosen link target is locked (FOR UPDATE) and checked in THIS transaction, so
                // it cannot be erased between the check and the write (PartyRepositoryImpl.anonymize
                // takes the same lock).
                val newTargets = listOfNotNull(case.firstLinkPartyId, case.finalLinkPartyId)
                    .distinct()
                    .filter { it != current.firstLinkPartyId && it != current.finalLinkPartyId }
                lockLiveTargets(newTargets).map { applyDecision(current, case) }
            }
        }.awaitSuspending()
        return case
    }

    private fun applyDecision(current: VerificationCaseEntity, case: VerificationCase) = current.apply {
        status = case.status.name
        firstApprover = case.firstApprover
        firstVerdict = case.firstVerdict?.name
        firstLinkPartyId = case.firstLinkPartyId
        firstNotes = case.firstNotes
        firstAt = case.firstAt
        secondApprover = case.secondApprover
        secondAt = case.secondAt
        finalVerdict = case.finalVerdict?.name
        finalLinkPartyId = case.finalLinkPartyId
        decidedAt = case.decidedAt
        updatedAt = case.updatedAt
    }

    /** Locks each party row (FOR UPDATE) in order and fails if any was erased. */
    private fun lockLiveTargets(ids: List<UUID>): Uni<Void> = Panache.getSession().flatMap { session ->
        ids.fold(Uni.createFrom().voidItem()) { chain, id ->
            chain.flatMap {
                session.find(PartyEntity::class.java, id, LockModeType.PESSIMISTIC_WRITE).map { party ->
                    if (party?.erasedAt != null) throw PartyErasedException("Link target party $id was erased")
                    party
                }.replaceWithVoid()
            }
        }
    }

    override suspend fun anonymizeLinkedTo(partyId: UUID, erasedAt: Instant): Int = Panache.withTransaction {
        update(
            "applicantGivenName = ?2, applicantFamilyName = ?2, applicantBirthdate = ?3, " +
                "applicantBirthplace = null, applicantNationalities = '', blindIndex = null, " +
                "dedupKey = concat('ERASED:', cast(id as string)), firstNotes = null, erasedAt = ?4 " +
                "where (firstLinkPartyId = ?1 or finalLinkPartyId = ?1) and erasedAt is null",
            partyId,
            ERASED,
            ERASED_BIRTHDATE,
            erasedAt,
        )
    }.awaitSuspending()

    private companion object {
        const val ERASED = "GDPR_ERASED"
        val ERASED_BIRTHDATE: LocalDate = LocalDate.of(1900, 1, 1)
    }
}
