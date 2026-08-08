// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.persistence.repository

import com.openbank.cardissuance.application.port.out.CardRepository
import com.openbank.cardissuance.domain.model.Card
import com.openbank.cardissuance.infrastructure.persistence.entity.CardEntity
import com.openbank.cardissuance.infrastructure.persistence.mapper.toDomain
import com.openbank.cardissuance.infrastructure.persistence.mapper.toEntity
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.LocalDate
import java.util.UUID

@ApplicationScoped
@Suppress("TooManyFunctions") // one adapter method per CardRepository port method (hexagonal)
class CardRepositoryImpl(private val outboxRepository: CardOutboxRepositoryImpl) :
    CardRepository,
    PanacheRepository<CardEntity> {

    /**
     * Persists the card **and** its outbox row in one transaction (ADR-0050). Issue is an insert;
     * a status transition is an update applied in place to the managed entity (so we never `persist`
     * a detached entity with an application-assigned id, which would attempt a conflicting INSERT).
     */
    override suspend fun save(card: Card, event: OutboxMessage): Card = Panache.withTransaction {
        find("id", card.id).firstResult().flatMap { existing ->
            val persisted = if (existing != null) {
                existing.applyFrom(card)
                io.smallrye.mutiny.Uni.createFrom().item(existing)
            } else {
                persist(card.toEntity())
            }
            persisted.chain { _ -> outboxRepository.persistInTransaction(event) }.replaceWith(card)
        }
    }.awaitSuspending()

    override suspend fun findById(id: UUID): Card? =
        Panache.withSession { find("id", id).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findByIdempotencyKey(key: String): Card? =
        Panache.withSession { find("idempotencyKey", key).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun listAllCards(): List<Card> =
        Panache.withSession { listAll() }.awaitSuspending().map { it.toDomain() }

    override suspend fun findByAccountId(accountId: UUID): List<Card> =
        Panache.withSession { find("accountId", accountId).list() }.awaitSuspending().map { it.toDomain() }

    override suspend fun findByPartyId(partyId: UUID): List<Card> =
        Panache.withSession { find("partyId", partyId).list() }.awaitSuspending().map { it.toDomain() }

    override suspend fun findByDelegationGrantId(grantId: UUID): List<Card> =
        Panache.withSession { find("delegationGrantId", grantId).list() }.awaitSuspending().map { it.toDomain() }

    override suspend fun anonymizeByPartyId(partyId: UUID) {
        Panache.withTransaction {
            update(
                "cardholderName = '[ERASED]', embossedName = '[ERASED]', deliveryAddress = null, blockedReason = null WHERE partyId = ?1",
                partyId,
            )
        }.awaitSuspending()
    }

    override suspend fun anonymizeExpiredCardPii(cutoff: LocalDate): Int = Panache.withTransaction {
        // Guard covers both fields: a partial failure from a prior run may leave embossedName
        // with PII even if cardholderName was already erased, so we re-process either way.
        update(
            "cardholderName = '[ERASED]', embossedName = '[ERASED]'" +
                " WHERE expiryDate <= ?1" +
                " AND (cardholderName != '[ERASED]' OR embossedName != '[ERASED]')",
            cutoff,
        )
    }.awaitSuspending()

    override suspend fun findWithoutPanCredential(): List<Card> = Panache.withSession {
        find("panEncrypted IS NULL AND status NOT IN ?1", TERMINAL_STATUS_NAMES).list()
    }.awaitSuspending().map { it.toDomain() }

    /**
     * Bulk UPDATE rather than a load-mutate-flush: the `panEncrypted IS NULL` predicate has to be
     * part of the *write*, not a check the caller made earlier, or two replicas booting together
     * could both decide a card needs a credential and the second would overwrite the first's.
     */
    override suspend fun storePanCredentialIfAbsent(
        cardId: UUID,
        panEncrypted: String,
        cvvEncrypted: String,
    ): Boolean = Panache.withTransaction {
        update(
            "panEncrypted = ?1, cvvEncrypted = ?2 WHERE id = ?3 AND panEncrypted IS NULL",
            panEncrypted,
            cvvEncrypted,
            cardId,
        )
    }.awaitSuspending() == 1

    /**
     * Copy the mutable (lifecycle) fields of [card] onto a managed entity for an in-place update.
     *
     * `delegationGrantId` is deliberately absent, along with the other issue-time identity fields:
     * the grant a card rests on is fixed at issue (ADR-0249 D1). Re-pointing a live card at a
     * different grant would let a revoked authority be laundered into a fresh one.
     */
    private fun CardEntity.applyFrom(card: Card) {
        status = card.status.name
        maskedPan = card.maskedPan
        cardholderName = card.cardholderName
        embossedName = card.embossedName
        expiryDate = card.expiryDate
        dailyLimitMinorUnits = card.dailyLimitMinorUnits
        monthlyLimitMinorUnits = card.monthlyLimitMinorUnits
        contactlessEnabled = card.contactlessEnabled
        onlineEnabled = card.onlineEnabled
        atmEnabled = card.atmEnabled
        abroadEnabled = card.abroadEnabled
        currency = card.currency
        deliveryAddress = card.deliveryAddress
        activatedAt = card.activatedAt
        blockedAt = card.blockedAt
        blockedReason = card.blockedReason
        updatedAt = card.updatedAt
    }

    private companion object {
        /** `status` is persisted as its enum NAME (see CardMapper), so the query compares strings. */
        val TERMINAL_STATUS_NAMES = Card.TERMINAL_STATUSES.map { it.name }
    }
}
