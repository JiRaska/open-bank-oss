// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.persistence.repository

import com.openbank.cardprocessing.application.port.out.CardAuthorizationRepository
import com.openbank.cardprocessing.domain.model.AuthorizationStatus
import com.openbank.cardprocessing.domain.model.CardAuthorization
import com.openbank.cardprocessing.domain.model.CountedSpend
import com.openbank.cardprocessing.domain.model.PresentmentChannel
import com.openbank.cardprocessing.domain.model.SpendWindow
import com.openbank.cardprocessing.infrastructure.persistence.entity.CardAuthorizationEntity
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class CardAuthorizationRepositoryImpl(private val outbox: CardProcessingOutboxRepositoryImpl) :
    CardAuthorizationRepository,
    PanacheRepository<CardAuthorizationEntity> {

    /**
     * The authorisation row and its outbox row commit together or not at all (ADR-0050).
     *
     * The update path mutates the **managed** entity rather than persisting a rebuilt one: the id is
     * application-assigned, and Panache reactive `persist()` on a non-null assigned id is
     * INSERT-only — Hibernate cannot tell transient from detached, so every state transition would
     * fail on `duplicate key value violates ..._pkey` at flush. That is exactly how consent-service's
     * revoke/reject/activate all 500'd (ADR-0126 D3, #1521).
     */
    override suspend fun save(
        authorization: CardAuthorization,
        event: OutboxMessage,
        idempotencyKey: String,
    ): CardAuthorization = Panache.withTransaction {
        find("id", authorization.id).firstResult().flatMap { existing ->
            val persisted: Uni<CardAuthorizationEntity> = if (existing != null) {
                existing.applyFrom(authorization)
                Uni.createFrom().item(existing)
            } else {
                // The command's idempotency key is what the UNIQUE index protects. It is not a
                // field of the aggregate — the domain has no opinion about acquirer retries — so it
                // is carried on the row, written once here and never rewritten.
                persist(authorization.toEntity(idempotencyKey))
            }
            persisted.chain { _ -> outbox.persistInTransaction(event) }.replaceWith(authorization)
        }
    }.awaitSuspending()

    override suspend fun findById(id: UUID): CardAuthorization? =
        Panache.withSession { find("id", id).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findByNetworkReference(networkReference: String): CardAuthorization? =
        Panache.withSession { find("networkReference", networkReference).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findByIdempotencyKey(key: String): CardAuthorization? =
        Panache.withSession { find("idempotencyKey", key).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findByCardId(cardId: UUID, limit: Int): List<CardAuthorization> = Panache.withSession {
        find("cardId = ?1 order by authorizedAt desc", cardId).range(0, limit.coerceAtLeast(1) - 1).list()
    }.awaitSuspending().map { it.toDomain() }

    /**
     * The counters, computed over the rows themselves.
     *
     * The `CASE` mirrors [CardAuthorization.effectiveSpendMinorUnits] exactly: a hold counts in full
     * until it clears, a cleared authorisation counts what cleared, and a released one counts
     * nothing. Two expressions of one rule is a drift risk, and the domain one is the readable copy.
     */
    override suspend fun countSpend(cardId: UUID, window: SpendWindow, category: String): CountedSpend =
        Panache.withSession {
            Panache.getSession().chain { session ->
                session.createNativeQuery(SPEND_SQL, Array<Any>::class.java)
                    .setParameter("cardId", cardId)
                    .setParameter("dayStart", window.dayStart)
                    .setParameter("monthStart", window.monthStart)
                    .setParameter("category", category)
                    .singleResult
            }
        }.awaitSuspending().let { row ->
            CountedSpend(
                todayMinorUnits = (row[0] as? Number)?.toLong() ?: 0L,
                thisMonthMinorUnits = (row[1] as? Number)?.toLong() ?: 0L,
                thisMonthInCategoryMinorUnits = (row[2] as? Number)?.toLong() ?: 0L,
            )
        }

    override suspend fun findExpiredHolds(now: Instant, limit: Int): List<CardAuthorization> = Panache.withSession {
        find(
            "status in (?1, ?2) and expiresAt <= ?3 order by expiresAt asc",
            AuthorizationStatus.APPROVED.name,
            AuthorizationStatus.PARTIALLY_CLEARED.name,
            now,
        ).range(0, limit.coerceAtLeast(1) - 1).list()
    }.awaitSuspending().map { it.toDomain() }

    private fun CardAuthorizationEntity.toDomain() = CardAuthorization(
        id = id,
        cardId = cardId,
        accountId = accountId,
        partyId = partyId,
        amountMinorUnits = amountMinorUnits,
        currencyCode = currencyCode,
        channel = PresentmentChannel.valueOf(channel),
        mcc = mcc,
        merchantName = merchantName,
        merchantCountry = merchantCountry,
        status = AuthorizationStatus.valueOf(status),
        category = category,
        declineReason = declineReason,
        clearedAmountMinorUnits = clearedAmountMinorUnits,
        networkReference = networkReference,
        initiatedByAgentId = initiatedByAgentId,
        authorizedAt = authorizedAt,
        expiresAt = expiresAt,
        updatedAt = updatedAt,
    )

    private fun CardAuthorization.toEntity(idempotencyKey: String) = CardAuthorizationEntity().also {
        it.applyFrom(this)
        it.idempotencyKey = idempotencyKey
    }

    /**
     * The mutable half of the aggregate. Identity, amount, merchant and the authorisation instant
     * are written once at insert and never rewritten — an authorisation whose amount or merchant can
     * change is not a record of what the acquirer asked.
     */
    private fun CardAuthorizationEntity.applyFrom(a: CardAuthorization) {
        id = a.id
        cardId = a.cardId
        accountId = a.accountId
        partyId = a.partyId
        amountMinorUnits = a.amountMinorUnits
        currencyCode = a.currencyCode
        channel = a.channel.name
        mcc = a.mcc
        merchantName = a.merchantName
        merchantCountry = a.merchantCountry
        status = a.status.name
        category = a.category
        declineReason = a.declineReason
        clearedAmountMinorUnits = a.clearedAmountMinorUnits
        networkReference = a.networkReference
        // Written once at insert with identity, amount and merchant: which agent acted is a fact
        // about the moment of authorisation, and a row whose agent can change is not a record of it.
        initiatedByAgentId = a.initiatedByAgentId
        authorizedAt = a.authorizedAt
        expiresAt = a.expiresAt
        updatedAt = a.updatedAt
    }

    companion object {
        /**
         * `COALESCE` on every aggregate: `SUM` over no rows is NULL, and a NULL read as a Kotlin
         * `Long` is either an exception or, worse, a silent zero that looks measured. A card with no
         * spend today must answer 0 by construction.
         */
        private const val SPEND_SQL = """
            SELECT
              COALESCE(SUM(CASE WHEN authorized_at >= :dayStart THEN effective END), 0) AS today,
              COALESCE(SUM(effective), 0)                                               AS this_month,
              COALESCE(SUM(CASE WHEN category = :category THEN effective END), 0)       AS this_month_category
            FROM (
              SELECT authorized_at, category,
                     CASE status
                       WHEN 'APPROVED'          THEN amount_minor_units
                       WHEN 'PARTIALLY_CLEARED' THEN amount_minor_units
                       WHEN 'CLEARED'           THEN cleared_amount_minor_units
                       ELSE 0
                     END AS effective
              FROM card_authorizations
              WHERE card_id = :cardId AND authorized_at >= :monthStart
            ) counted
        """
    }
}
