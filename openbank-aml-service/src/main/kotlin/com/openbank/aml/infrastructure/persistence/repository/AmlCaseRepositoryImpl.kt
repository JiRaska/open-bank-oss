// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.persistence.repository

import com.openbank.aml.application.port.out.AmlCaseRepository
import com.openbank.aml.domain.model.AmlCase
import com.openbank.aml.domain.model.AmlCaseStatus
import com.openbank.aml.domain.model.ScreeningType
import com.openbank.aml.infrastructure.persistence.entity.AmlCaseEntity
import com.openbank.aml.infrastructure.persistence.mapper.toDomain
import com.openbank.aml.infrastructure.persistence.mapper.toEntity
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
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
    override suspend fun update(amlCase: AmlCase, event: OutboxMessage): AmlCase = Panache.withTransaction {
        find("caseId", amlCase.id).firstResult()
            .invoke { entity ->
                if (entity != null) {
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

    // GDPR Art. 17: right of erasure — null out PII fields and replace customerReference with a
    // non-identifying sentinel so the case row itself (required for audit/SAR trails) survives.
    // #3413: the rails satisfied a NOT NULL `party_id` with the debtor ACCOUNT id, so on every
    // affected row the two columns are byte-for-byte equal — measured 6 of 6 payment cases. That
    // equality is the detector; it cannot false-positive, because an account id and a party id are
    // drawn from different tables and a genuine party is never its own account.
    override suspend fun findUnresolvedParty(limit: Int): List<Pair<UUID, UUID>> = Panache.withSession {
        find("partyId = accountId and accountId is not null").range(0, limit - 1).list()
    }.awaitSuspending().map { it.caseId to it.accountId!! }

    override suspend fun resolveParty(caseId: UUID, partyId: UUID) {
        Panache.withTransaction {
            update("partyId = ?1 where caseId = ?2", partyId, caseId)
        }.awaitSuspending()
    }

    override suspend fun countUnresolvedParty(): Long =
        Panache.withSession { count("partyId = accountId and accountId is not null") }.awaitSuspending()

    override suspend fun anonymizeByPartyId(partyId: UUID): Int = Panache.withTransaction {
        update(
            "customerReference = concat('ERASED-', cast(partyId as string))," +
                " matchedEntity = null, alertDetail = null" +
                " where partyId = ?1",
            partyId,
        )
    }.awaitSuspending().toInt()
}
