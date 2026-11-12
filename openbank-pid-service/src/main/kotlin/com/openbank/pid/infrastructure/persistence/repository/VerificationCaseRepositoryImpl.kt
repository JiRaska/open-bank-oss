// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.persistence.repository

import com.openbank.pid.application.port.out.VerificationCaseRepository
import com.openbank.pid.application.usecase.PartyErasedException
import com.openbank.pid.domain.model.CaseVerdict
import com.openbank.pid.domain.model.ErasedIdentifierKey
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
@Suppress("TooManyFunctions") // the port plus the small private steps of the GDPR erasure queries
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
            // Lock order: case row first, then its link-target party rows in sorted id order. The erase
            // path never holds a party lock while waiting on a case row (party and case steps are
            // separate transactions), so there is no opposite ordering to deadlock against.
            find("id", case.id).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult().flatMap { existing ->
                val current = requireNotNull(existing) { "verification case ${case.id} not found" }
                // GDPR Art. 17 (#15755): an erased case is closed history; no step may write to it.
                if (current.erasedAt != null) throw PartyErasedException("verification case ${case.id} was erased")
                // EVERY link target of the incoming case is locked (FOR UPDATE) and checked in THIS
                // transaction, not only newly chosen ones: a target erased after it was linked must
                // also stop the write (PartyRepositoryImpl.anonymize takes the same lock).
                val newTargets = listOfNotNull(
                    current.firstLinkPartyId,
                    current.finalLinkPartyId,
                    case.firstLinkPartyId,
                    case.finalLinkPartyId,
                ).distinct().sorted()
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
        anonymiseWhere("(firstLinkPartyId = ?4 or finalLinkPartyId = ?4)", erasedAt, partyId)
    }.awaitSuspending()

    override suspend fun removeErasedIdentifier(blindIndex: String): Int = Panache.withTransaction {
        // REJECT first: keep the decision effective under the opaque key (port KDoc).
        update(
            "blindIndex = null, dedupKey = ?2 where blindIndex = ?1 and erasedAt is null and status = ?3 " +
                "and finalVerdict = ?4",
            blindIndex,
            ErasedIdentifierKey.rejectBlock(blindIndex),
            VerificationCaseStatus.DECIDED.name,
            CaseVerdict.REJECT.name,
        ).flatMap { rejected ->
            update(
                "blindIndex = null, dedupKey = concat('ERASED:', cast(id as string)) " +
                    "where blindIndex = ?1 and erasedAt is null",
                blindIndex,
            ).map { stripped -> rejected + stripped }
        }
    }.awaitSuspending()

    /** The full anonymisation of a case row; [condition] uses ?4 onwards for [params]. */
    private fun anonymiseWhere(condition: String, erasedAt: Instant, vararg params: Any): Uni<Int> = update(
        "applicantGivenName = ?1, applicantFamilyName = ?1, applicantBirthdate = ?2, " +
            "applicantBirthplace = null, applicantNationalities = '', blindIndex = null, " +
            "dedupKey = concat('ERASED:', cast(id as string)), firstNotes = null, erasedAt = ?3 " +
            "where $condition and erasedAt is null",
        ERASED,
        ERASED_BIRTHDATE,
        erasedAt,
        *params,
    )

    private companion object {
        const val ERASED = "GDPR_ERASED"
        val ERASED_BIRTHDATE: LocalDate = LocalDate.of(1900, 1, 1)
    }
}
