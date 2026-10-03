// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.persistence.repository

import com.openbank.cardprocessing.application.port.out.CardTokenRegistrationRepository
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
class CardTokenRegistrationRepositoryImpl(private val outbox: CardProcessingOutboxRepositoryImpl) :
    CardTokenRegistrationRepository,
    PanacheRepository<CardTokenRegistrationEntity> {

    override suspend fun save(
        registration: CardTokenRegistration,
        event: OutboxMessage,
        idempotencyKey: String,
    ): CardTokenRegistration = Panache.withTransaction {
        find("id", registration.id).firstResult().flatMap { existing ->
            val persisted: Uni<CardTokenRegistrationEntity> = if (existing != null) {
                existing.applyFrom(registration)
                Uni.createFrom().item(existing)
            } else {
                persist(registration.toEntity(idempotencyKey))
            }
            persisted.chain { _ -> outbox.persistInTransaction(event) }.replaceWith(registration)
        }
    }.awaitSuspending()

    override suspend fun findByTokenReference(tokenReference: String): CardTokenRegistration? =
        Panache.withSession { find("tokenReference", tokenReference).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findByIdempotencyKey(key: String): CardTokenRegistration? =
        Panache.withSession { find("idempotencyKey", key).firstResult() }.awaitSuspending()?.toDomain()

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
}
