// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.persistence.repository

import com.openbank.libs.identity.MatchKey
import com.openbank.pid.application.port.out.ApplicantCaseKeys
import com.openbank.pid.application.port.out.VerificationCaseRepository
import com.openbank.pid.application.usecase.PartyErasedException
import com.openbank.pid.domain.model.CaseVerdict
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

    override suspend fun anonymizeApplicantCases(keys: ApplicantCaseKeys, erasedAt: Instant): Int =
        Panache.withTransaction {
            byBlindIndex(keys.birthNumberBlindIndex, erasedAt).flatMap { blind ->
                byNameKey(keys, erasedAt).map { named -> blind + named }
            }
        }.awaitSuspending()

    /** `RN:` cases carrying the erased person's RČ blind index (rule in the port KDoc). */
    private fun byBlindIndex(blindIndex: String?, erasedAt: Instant): Uni<Int> {
        if (blindIndex == null) return Uni.createFrom().item(0)
        return anonymiseWhere(
            "blindIndex = ?4 and not (status = ?5 and coalesce(finalVerdict, '') = ?6)",
            erasedAt,
            blindIndex,
            VerificationCaseStatus.DECIDED.name,
            CaseVerdict.DISTINCT_NEW.name,
        ).flatMap { full ->
            // What is left with this index is DISTINCT_NEW: a decided different person. Remove only
            // the erased person's identifier; erased_at stays null because the applicant is kept.
            update(
                "blindIndex = null, dedupKey = concat('ERASED:', cast(id as string)) " +
                    "where blindIndex = ?1 and erasedAt is null",
                blindIndex,
            ).map { stripped -> full + stripped }
        }
    }

    /** `NK:`/`PK:` cases whose applicant can only be the erased person (rule in the port KDoc). */
    private fun byNameKey(keys: ApplicantCaseKeys, erasedAt: Instant): Uni<Int> =
        find("dedupKey in ?1 and erasedAt is null", listOf("NK:${keys.matchKey}", "PK:${keys.matchKey}")).list()
            .flatMap { cases ->
                if (cases.isEmpty()) {
                    Uni.createFrom().item(0)
                } else {
                    liveNamesakes(keys).flatMap { namesakes ->
                        val eligible = cases.filter { case ->
                            val candidates = case.candidatePartyIds.split(',').filter { it.isNotBlank() }
                                .map(UUID::fromString).toSet()
                            keys.partyId !in candidates && candidates.containsAll(namesakes)
                        }.map { it.id }
                        if (eligible.isEmpty()) {
                            Uni.createFrom().item(0)
                        } else {
                            anonymiseWhere("id in ?4", erasedAt, eligible)
                        }
                    }
                }
            }

    /**
     * Every live (not erased) party other than the erased one whose stored attributes produce the
     * same match key. Selected by exact birthdate and compared on the normalised key in memory, so a
     * namesake spelled with diacritics is still found (a `LOWER(family_name)` filter would miss it).
     */
    private fun liveNamesakes(keys: ApplicantCaseKeys): Uni<Set<UUID>> = Panache.getSession().flatMap { session ->
        session.createSelectionQuery(
            "from PartyEntity where birthdate = :birthdate and erasedAt is null and id <> :self",
            PartyEntity::class.java,
        ).setParameter("birthdate", keys.birthdate).setParameter("self", keys.partyId).resultList
    }.map { parties ->
        parties.filter { MatchKey.of(it.familyName, it.givenName, it.birthdate, it.birthplace) == keys.matchKey }
            .map { it.id }.toSet()
    }

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
