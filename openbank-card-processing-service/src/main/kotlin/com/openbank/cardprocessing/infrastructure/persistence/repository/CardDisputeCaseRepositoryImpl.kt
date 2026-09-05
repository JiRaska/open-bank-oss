// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.persistence.repository

import com.openbank.cardprocessing.application.port.out.CardDisputeCaseRepository
import com.openbank.cardprocessing.domain.model.CardDisputeCase
import com.openbank.cardprocessing.domain.model.DisputeStatus
import com.openbank.cardprocessing.infrastructure.persistence.entity.CardDisputeCaseEntity
import com.openbank.libs.domain.cards.scheme.CardScheme
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/** Dispute cases and their events, committed together (ADR-0050). Same managed-entity rule as the sibling repositories. */
@ApplicationScoped
class CardDisputeCaseRepositoryImpl(private val outbox: CardProcessingOutboxRepositoryImpl) :
    CardDisputeCaseRepository,
    PanacheRepository<CardDisputeCaseEntity> {

    override suspend fun save(
        case: CardDisputeCase,
        event: OutboxMessage,
        idempotencyKey: String,
    ): CardDisputeCase = Panache.withTransaction {
        find("id", case.id).firstResult().flatMap { existing ->
            val persisted: Uni<CardDisputeCaseEntity> = if (existing != null) {
                existing.applyFrom(case)
                Uni.createFrom().item(existing)
            } else {
                persist(case.toEntity(idempotencyKey))
            }
            persisted.chain { _ -> outbox.persistInTransaction(event) }.replaceWith(case)
        }
    }.awaitSuspending()

    override suspend fun findById(id: UUID): CardDisputeCase? =
        Panache.withSession { find("id", id).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findByIdempotencyKey(key: String): CardDisputeCase? =
        Panache.withSession { find("idempotencyKey", key).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findByCardId(cardId: UUID, limit: Int): List<CardDisputeCase> = Panache.withSession {
        find("cardId = ?1 order by openedAt desc", cardId).range(0, limit.coerceAtLeast(1) - 1).list()
    }.awaitSuspending().map { it.toDomain() }

    /**
     * Live means not terminal, spelled out as the two live values rather than as a NOT IN over the
     * terminal ones: the partial UNIQUE index in `V3__token_and_dispute_lifecycle.sql` is written
     * over exactly this predicate, and the two must not be able to drift apart.
     */
    override suspend fun findLiveByAuthorization(authorizationId: UUID): CardDisputeCase? = Panache.withSession {
        find(
            "authorizationId = ?1 and status in (?2, ?3)",
            authorizationId,
            DisputeStatus.OPEN.name,
            DisputeStatus.EVIDENCE_SUBMITTED.name,
        ).firstResult()
    }.awaitSuspending()?.toDomain()

    private fun CardDisputeCaseEntity.toDomain() = CardDisputeCase(
        id = id,
        authorizationId = authorizationId,
        cardId = cardId,
        networkCaseId = networkCaseId,
        reasonCode = reasonCode,
        amountMinorUnits = amountMinorUnits,
        currencyCode = currencyCode,
        status = DisputeStatus.valueOf(status),
        scheme = CardScheme.valueOf(scheme),
        schemeStatus = schemeStatus,
        respondByDate = respondByDate,
        evidenceReference = evidenceReference,
        openedAt = openedAt,
        updatedAt = updatedAt,
    )

    private fun CardDisputeCase.toEntity(idempotencyKey: String) = CardDisputeCaseEntity().also {
        it.applyFrom(this)
        it.idempotencyKey = idempotencyKey
    }

    private fun CardDisputeCaseEntity.applyFrom(c: CardDisputeCase) {
        id = c.id
        authorizationId = c.authorizationId
        cardId = c.cardId
        networkCaseId = c.networkCaseId
        reasonCode = c.reasonCode
        amountMinorUnits = c.amountMinorUnits
        currencyCode = c.currencyCode
        status = c.status.name
        scheme = c.scheme.name
        schemeStatus = c.schemeStatus
        respondByDate = c.respondByDate
        evidenceReference = c.evidenceReference
        openedAt = c.openedAt
        updatedAt = c.updatedAt
    }
}
