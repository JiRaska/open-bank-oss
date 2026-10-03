// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.persistence.repository

import com.openbank.cardprocessing.application.port.out.CardTokenRegistrationRepository
import com.openbank.cardprocessing.application.port.out.IdempotencyClaim
import com.openbank.cardprocessing.domain.model.CardTokenRegistration
import com.openbank.cardprocessing.infrastructure.persistence.entity.CardTokenRegistrationEntity
import com.openbank.libs.domain.cards.scheme.CardScheme
import com.openbank.libs.domain.cards.scheme.NetworkTokenStatus
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The token mirror, written with its event in one transaction (ADR-0050).
 *
 * The update path mutates the MANAGED entity. Panache reactive `persist()` on an
 * application-assigned id is INSERT-only — Hibernate cannot tell transient from detached — so
 * persisting a rebuilt row would fail every status change with a duplicate-key violation at flush
 * (ADR-0126 D3, #1521).
 */
@ApplicationScoped
class CardTokenRegistrationRepositoryImpl(
    private val outbox: CardProcessingOutboxRepositoryImpl,
    private val idempotency: LifecycleIdempotencyRepositoryImpl,
) : CardTokenRegistrationRepository,
    PanacheRepository<CardTokenRegistrationEntity> {

    override suspend fun save(
        registration: CardTokenRegistration,
        event: OutboxMessage,
        idempotencyKey: String,
        claim: IdempotencyClaim?,
    ): CardTokenRegistration = Panache.withTransaction {
        find("id", registration.id).firstResult().flatMap { existing ->
            val persisted: Uni<CardTokenRegistrationEntity> = if (existing != null) {
                existing.applyFrom(registration)
                Uni.createFrom().item(existing)
            } else {
                persist(registration.toEntity(idempotencyKey))
            }
            persisted
                .chain { _ -> outbox.persistInTransaction(event) }
                .chain { _ ->
                    if (claim == null) {
                        Uni.createFrom().voidItem()
                    } else {
                        idempotency.completeInTransaction(claim, registration.id)
                    }
                }
                .replaceWith(registration)
        }
    }.awaitSuspending()

    /**
     * `INSERT ... ON CONFLICT (token_reference) DO NOTHING`, then a read of what is stored: whichever
     * concurrent read inserted first, every reader gets the same row and the same id. No outbox event
     * — the bank did not provision these tokens and nothing about them changed; the row is a record
     * that the network reported them.
     */
    override suspend fun adoptNetworkSeen(registrations: List<CardTokenRegistration>): List<CardTokenRegistration> {
        if (registrations.isEmpty()) return emptyList()
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                registrations.fold(Uni.createFrom().item(0)) { acc, r ->
                    acc.chain { _ ->
                        session.createNativeQuery<Int>(
                            "INSERT INTO card_network_tokens (id, card_id, token_reference, requestor_id, " +
                                "requestor_label, last4, status, scheme, expiry, idempotency_key, provisioned_at, " +
                                "updated_at) VALUES (:id, :card, :ref, :requestor, :label, :last4, :status, " +
                                ":scheme, :expiry, :key, :at, :at) ON CONFLICT (token_reference) DO NOTHING",
                        )
                            .setParameter("id", r.id)
                            .setParameter("card", r.cardId)
                            .setParameter("ref", r.tokenReference)
                            .setParameter("requestor", r.requestorId)
                            .setParameter("label", r.requestorLabel)
                            .setParameter("last4", r.last4)
                            .setParameter("status", r.status.name)
                            .setParameter("scheme", r.scheme.name)
                            .setParameter("expiry", r.expiry)
                            .setParameter("key", "$ADOPTED_KEY_PREFIX${r.tokenReference}")
                            .setParameter("at", OffsetDateTime.ofInstant(r.provisionedAt, ZoneOffset.UTC))
                            .executeUpdate()
                    }
                }
            }
        }.awaitSuspending()
        val references = registrations.map { it.tokenReference }
        return Panache.withSession { find("tokenReference in ?1", references).list() }
            .awaitSuspending()
            .map { it.toDomain() }
    }

    override suspend fun findById(id: UUID): CardTokenRegistration? =
        Panache.withSession { find("id", id).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findByTokenReference(tokenReference: String): CardTokenRegistration? =
        Panache.withSession { find("tokenReference", tokenReference).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findByCardId(cardId: UUID): List<CardTokenRegistration> = Panache.withSession {
        find("cardId = ?1 order by provisionedAt desc", cardId).list()
    }.awaitSuspending().map { it.toDomain() }

    private fun CardTokenRegistrationEntity.toDomain() = CardTokenRegistration(
        id = id,
        cardId = cardId,
        tokenReference = tokenReference,
        requestorId = requestorId,
        requestorLabel = requestorLabel,
        last4 = last4,
        status = NetworkTokenStatus.valueOf(status),
        scheme = CardScheme.valueOf(scheme),
        expiry = expiry,
        provisionedAt = provisionedAt,
        updatedAt = updatedAt,
    )

    private fun CardTokenRegistration.toEntity(idempotencyKey: String) = CardTokenRegistrationEntity().also {
        it.applyFrom(this)
        it.idempotencyKey = idempotencyKey
    }

    /**
     * The mutable half: status, expiry and the update instant. The token reference, the requestor
     * and the provisioning instant are written once — a mirror whose token reference can change is
     * not a record of what the network minted.
     */
    private fun CardTokenRegistrationEntity.applyFrom(r: CardTokenRegistration) {
        id = r.id
        cardId = r.cardId
        tokenReference = r.tokenReference
        requestorId = r.requestorId
        requestorLabel = r.requestorLabel
        last4 = r.last4
        status = r.status.name
        scheme = r.scheme.name
        expiry = r.expiry
        provisionedAt = r.provisionedAt
        updatedAt = r.updatedAt
    }

    private companion object {
        /** Idempotency-key column value for a token adopted from a network read, not provisioned here. */
        const val ADOPTED_KEY_PREFIX = "adopted:"
    }
}
