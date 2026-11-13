// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.persistence.repository

import com.openbank.aml.application.port.out.AmlCaseRepository
import com.openbank.aml.application.port.out.CaseChangedConcurrentlyException
import com.openbank.aml.domain.model.AmlCase
import com.openbank.aml.domain.model.AmlCaseStatus
import com.openbank.aml.domain.model.ScreeningType
import com.openbank.aml.infrastructure.persistence.entity.AmlCaseEntity
import com.openbank.aml.infrastructure.persistence.mapper.toDomain
import com.openbank.aml.infrastructure.persistence.mapper.toEntity
import com.openbank.aml.infrastructure.persistence.repository.NativeSql.mutate
import com.openbank.aml.infrastructure.persistence.repository.NativeSql.select
import com.openbank.aml.infrastructure.persistence.repository.NativeSql.utc
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class AmlCaseRepositoryImpl(private val outboxRepository: AmlOutboxRepositoryImpl) :
    AmlCaseRepository,
    PanacheRepository<AmlCaseEntity> {

    // ADR-0050: case row + outbox row persist in ONE transaction (atomic write-then-publish).
    override suspend fun save(amlCase: AmlCase, event: OutboxMessage): AmlCase = Panache.withTransaction {
        persist(amlCase.toEntity())
            .chain { _ -> outboxRepository.persistInTransaction(event) }
            .replaceWith(amlCase)
    }.awaitSuspending()

    override suspend fun findById(caseId: UUID): AmlCase? =
        Panache.withSession { find("caseId", caseId).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findByIdempotencyKey(idempotencyKey: String): AmlCase? =
        Panache.withSession { find("idempotencyKey", idempotencyKey).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun list(
        status: AmlCaseStatus?,
        partyId: UUID?,
        partyResolved: Boolean?,
        screeningType: ScreeningType?,
        limit: Int,
        offset: Int,
    ): List<AmlCase> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        if (status != null) {
            conditions += "status = ?${params.size + 1}"
            params += status.name
        }
        if (partyId != null) {
            conditions += "partyId = ?${params.size + 1}"
            params += partyId
        }
        if (partyResolved != null) {
            conditions += if (partyResolved) "($RESOLVED_PARTY)" else "($UNRESOLVED_PARTY)"
        }
        if (screeningType != null) {
            conditions += "screeningType = ?${params.size + 1}"
            params += screeningType.name
        }

        val clause = conditions.joinToString(" and ").ifBlank { "1 = 1" }
        return Panache.withSession {
            val query = find("$clause order by createdAt desc", *params.toTypedArray())
            query.range(offset, offset + limit - 1).list()
        }.awaitSuspending().map { it.toDomain() }
    }

    // ADR-0050: transition + outbox row persist in ONE transaction (atomic write-then-publish).
    // The row is locked for the write, and the same lock is what `resolveParty` takes: a decision and
    // a resolution of one case are serialised, so the decision's event either precedes the resolution
    // (which then states a BLOCK again for the party) or is refused here and rebuilt with the party in it.
    override suspend fun update(amlCase: AmlCase, event: OutboxMessage): AmlCase = Panache.withTransaction {
        find("caseId", amlCase.id).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()
            .invoke { entity ->
                if (entity != null) {
                    if (entity.partyId != amlCase.partyId) throw CaseChangedConcurrentlyException(amlCase.id)
                    entity.status = amlCase.status.name
                    entity.decisionReason = amlCase.decisionReason
                    entity.assignedAnalyst = amlCase.assignedAnalyst
                    entity.decidedBy = amlCase.decidedBy
                    entity.decidedAt = amlCase.decidedAt
                    entity.updatedAt = amlCase.updatedAt
                }
            }
            .chain { _ -> outboxRepository.persistInTransaction(event) }
            .replaceWith(amlCase)
    }.awaitSuspending()

    // Two shapes of "party not established", both resolvable from the account. A missing party is
    // the explicit one (V14). The other is #3413: while `party_id` was NOT NULL the rails satisfied it
    // with the debtor ACCOUNT id, so on those rows the two columns are byte-for-byte equal — that
    // equality cannot false-positive, because an account id and a party id are drawn from different
    // tables and a genuine party is never its own account.
    override suspend fun findUnresolvedParty(limit: Int): List<Pair<UUID, UUID>> = Panache.withSession {
        find("$RESOLVABLE order by partyResolutionAttemptedAt asc nulls first, id asc").range(0, limit - 1).list()
    }.awaitSuspending().map { it.caseId to it.accountId!! }

    override suspend fun resolveParty(caseId: UUID, partyId: UUID, owed: (AmlCase) -> OutboxMessage?): Boolean =
        Panache.withTransaction {
            find("caseId", caseId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult().chain { entity ->
                if (entity == null || !entity.toDomain().resolvableTo(partyId)) {
                    Uni.createFrom().item(false)
                } else {
                    val event = owed(entity.toDomain())
                    // Taken before the erasure record is read, and taken by the erasure before it
                    // writes one: whichever of the two commits first, the other sees it.
                    lockParty(partyId)
                        .chain { _ -> select(ERASED, mapOf("partyId" to partyId)) }
                        .chain { erased ->
                            entity.partyId = partyId
                            if (erased.isNotEmpty()) {
                                entity.customerReference = erasedReference(partyId)
                                entity.matchedEntity = null
                                entity.alertDetail = null
                            }
                            if (event ==
                                null
                            ) {
                                Uni.createFrom().voidItem()
                            } else {
                                outboxRepository.persistInTransaction(event)
                            }
                        }
                        .replaceWith(true)
                }
            }
        }.awaitSuspending()

    override suspend fun markPartyResolutionAttempted(caseId: UUID, at: Instant) {
        Panache.withTransaction {
            update("partyResolutionAttemptedAt = ?1 where caseId = ?2", at, caseId)
        }.awaitSuspending()
    }

    override suspend fun countUnresolvedParty(): Long = Panache.withSession { count(RESOLVABLE) }.awaitSuspending()

    // GDPR Art. 17: right of erasure — null out PII fields and replace customerReference with a
    // non-identifying sentinel so the case row itself (required for audit/SAR trails) survives.
    // A case that does not name the party yet cannot be found here, so the erasure is RECORDED and
    // `resolveParty` applies the same anonymisation when it attaches such a case to the party.
    override suspend fun anonymizeByPartyId(partyId: UUID, erasedAt: Instant): Int = Panache.withTransaction {
        lockParty(partyId)
            .chain { _ -> mutate(RECORD_ERASURE, mapOf("partyId" to partyId, "erasedAt" to utc(erasedAt))) }
            .chain { _ ->
                mutate(ANONYMIZE, mapOf("partyId" to partyId, "reference" to erasedReference(partyId)))
            }
    }.awaitSuspending()

    private companion object {
        fun erasedReference(partyId: UUID): String = "ERASED-$partyId"

        const val ERASED = "SELECT 1 FROM aml_erased_party WHERE party_id = :partyId"

        const val RECORD_ERASURE = """
            INSERT INTO aml_erased_party (party_id, erased_at) VALUES (:partyId, :erasedAt)
            ON CONFLICT (party_id) DO NOTHING
        """

        const val ANONYMIZE = """
            UPDATE aml_cases
               SET customer_reference = :reference, matched_entity = NULL, alert_detail = NULL
             WHERE party_id = :partyId
        """

        /** A case whose owning party is not established; mirrors `AmlCase.partyEstablished`. */
        const val UNRESOLVED_PARTY = "partyId is null or partyId = accountId"

        /** Its exact complement, spelled out: `not (...)` over a null `accountId` is null, not true. */
        const val RESOLVED_PARTY = "partyId is not null and (accountId is null or partyId <> accountId)"

        /** The unresolved cases the sweep can do something about: they know their account. */
        const val RESOLVABLE = "($UNRESOLVED_PARTY) and accountId is not null"
    }
}

/** Held to the end of the transaction; serialises an erasure of a party with a resolution to it. */
private fun lockParty(partyId: UUID): Uni<Unit> =
    // Cast to text: the function returns `void`, which the driver cannot hand back as a column.
    select(
        "SELECT cast(pg_advisory_xact_lock(hashtextextended(:party, 0)) AS text)",
        mapOf("party" to partyId.toString()),
    ).replaceWith(Unit)
