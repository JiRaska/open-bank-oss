// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.consent.application.port.out.ConsentOutboxRepository
import com.openbank.consent.application.port.out.ConsentRepository
import com.openbank.consent.domain.model.Consent
import com.openbank.consent.infrastructure.persistence.entity.ConsentEntity
import com.openbank.libs.domain.event.DomainEvent
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.OffsetDateTime
import java.util.UUID

@ApplicationScoped
class ConsentRepositoryImpl(
    private val outboxRepository: ConsentOutboxRepository,
    private val objectMapper: ObjectMapper,
) : ConsentRepository,
    PanacheRepository<ConsentEntity> {

    // merge, not persist: the consent aggregate carries an application-assigned @Id, so persist()
    // schedules an INSERT even for an existing row and fails on the PK for every lifecycle
    // transition (activate/reject/revoke). merge is the upsert the transitions need.
    override suspend fun save(consent: Consent): Consent =
        Panache.withTransaction { mergeConsent(consent) }.awaitSuspending().toDomain()

    // Aggregate state change + outbox row in ONE transaction: the bare persist() inside
    // persistInTransaction joins this @WithTransaction session (transactional outbox, ADR-0126 §D3).
    // The single-aggregate save(consent, event) is the empty-supersede case of this, defaulted on
    // the port rather than written twice.
    override suspend fun saveSuperseding(
        consent: Consent,
        event: DomainEvent,
        superseded: List<Pair<Consent, DomainEvent>>,
    ): Consent = Panache.withTransaction {
        // Every merge and every outbox row joins THIS transaction, so the activation and the
        // retirement of the rows it replaces commit together or not at all (#6487).
        superseded.fold(Uni.createFrom().voidItem()) { chain, (old, oldEvent) ->
            chain.flatMap {
                mergeConsent(old).flatMap {
                    outboxRepository.persistInTransaction(outboxMessage(oldEvent))
                }
            }.replaceWithVoid()
        }.flatMap {
            mergeConsent(consent).flatMap { merged ->
                outboxRepository.persistInTransaction(outboxMessage(event)).replaceWith(merged)
            }
        }
    }.awaitSuspending().toDomain()

    private fun mergeConsent(consent: Consent): Uni<ConsentEntity> =
        Panache.getSession().flatMap { session -> session.merge(ConsentEntity.fromDomain(consent)) }

    private fun outboxMessage(event: DomainEvent): OutboxMessage = OutboxMessage(
        aggregateId = event.aggregateId,
        eventType = event.eventType,
        payload = objectMapper.writeValueAsString(event),
        createdAt = event.occurredAt,
    )

    override suspend fun findById(id: UUID): Consent? =
        Panache.withSession { find("id", id).firstResult<ConsentEntity>() }.awaitSuspending()?.toDomain()

    override suspend fun findByPartyId(partyId: UUID): List<Consent> =
        Panache.withSession { find("partyId", partyId).list<ConsentEntity>() }.awaitSuspending().map { it.toDomain() }

    override suspend fun findByGranteeId(granteeId: String): List<Consent> = Panache.withSession {
        find("granteeId", granteeId).list<ConsentEntity>()
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun findActiveByGranteeAndParty(granteeId: String, partyId: UUID): List<Consent> =
        Panache.withSession {
            find("granteeId = ?1 and partyId = ?2 and status = 'ACTIVE'", granteeId, partyId)
                .list<ConsentEntity>()
        }.awaitSuspending().map { it.toDomain() }

    override fun findExpiredActive(threshold: OffsetDateTime): Uni<List<Consent>> = Panache.withSession {
        find("status = 'ACTIVE' and validTo < ?1", threshold).list<ConsentEntity>()
    }.map { list -> list.map { it.toDomain() } }

    override fun markExpired(id: UUID, expiredAt: OffsetDateTime, event: DomainEvent): Uni<Boolean> =
        Panache.withTransaction {
            update(
                "status = 'EXPIRED', updatedAt = ?1 where id = ?2 and status = 'ACTIVE'",
                expiredAt,
                id,
            ).flatMap { count ->
                if (count > 0L) {
                    outboxRepository.persistInTransaction(outboxMessage(event)).replaceWith(true)
                } else {
                    Uni.createFrom().item(false)
                }
            }
        }
}
