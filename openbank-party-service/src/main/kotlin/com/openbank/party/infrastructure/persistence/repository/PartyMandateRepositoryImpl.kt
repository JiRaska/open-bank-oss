// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.party.application.port.out.PartyMandateRepository
import com.openbank.party.application.port.out.PartyOutboxRepository
import com.openbank.party.domain.model.MandateAuthority
import com.openbank.party.domain.model.MandateRole
import com.openbank.party.domain.model.MandateSource
import com.openbank.party.domain.model.MandateStatus
import com.openbank.party.domain.model.PartyEvent
import com.openbank.party.domain.model.PartyMandate
import com.openbank.party.infrastructure.persistence.entity.PartyMandateEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/** Mandate row + `party_outbox` event in one transaction, same discipline as the party aggregate (#4007). */
@ApplicationScoped
class PartyMandateRepositoryImpl(
    private val outboxRepository: PartyOutboxRepository,
    private val objectMapper: ObjectMapper,
) : PartyMandateRepository,
    PanacheRepository<PartyMandateEntity> {

    override suspend fun save(mandate: PartyMandate, event: PartyEvent): PartyMandate {
        val e = PartyMandateEntity().apply {
            mandateId = mandate.id
            createdAt = mandate.createdAt
        }.fill(mandate)
        Panache.withTransaction {
            persist(e).flatMap { outboxRepository.persistInTransaction(event.toOutboxMessage()) }
        }.awaitSuspending()
        return mandate
    }

    override suspend fun update(mandate: PartyMandate, event: PartyEvent): PartyMandate {
        Panache.withTransaction {
            find("mandateId", mandate.id).firstResult().flatMap { e ->
                requireNotNull(e) { "mandate ${mandate.id} vanished" }.fill(mandate)
                outboxRepository.persistInTransaction(event.toOutboxMessage())
            }
        }.awaitSuspending()
        return mandate
    }

    override suspend fun findById(id: UUID): PartyMandate? =
        Panache.withSession { find("mandateId", id).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findByPrincipal(principalPartyId: UUID): List<PartyMandate> = Panache.withSession {
        find("principalPartyId = ?1 order by createdAt", principalPartyId).list()
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun findByAgent(agentPartyId: UUID): List<PartyMandate> = Panache.withSession {
        find("agentPartyId = ?1 order by createdAt", agentPartyId).list()
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun findActive(principalPartyId: UUID, agentPartyId: UUID, role: String): PartyMandate? =
        Panache.withSession {
            find(
                "principalPartyId = ?1 and agentPartyId = ?2 and role = ?3 and status = ?4",
                principalPartyId,
                agentPartyId,
                role,
                MandateStatus.ACTIVE.name,
            ).firstResult()
        }.awaitSuspending()?.toDomain()

    private fun PartyMandateEntity.fill(m: PartyMandate) = apply {
        principalPartyId = m.principalPartyId
        agentPartyId = m.agentPartyId
        role = m.role.name
        authority = m.authority.name
        source = m.source.name
        status = m.status.name
        evidenceRef = m.evidenceRef
        validFrom = m.validFrom
        validTo = m.validTo
        revokedAt = m.revokedAt
        revokeReason = m.revokeReason
        updatedAt = m.updatedAt
    }

    private fun PartyMandateEntity.toDomain() = PartyMandate(
        id = mandateId,
        principalPartyId = principalPartyId,
        agentPartyId = agentPartyId,
        role = MandateRole.valueOf(role),
        authority = MandateAuthority.valueOf(authority),
        source = MandateSource.valueOf(source),
        status = MandateStatus.valueOf(status),
        evidenceRef = evidenceRef,
        validFrom = validFrom,
        validTo = validTo,
        revokedAt = revokedAt,
        revokeReason = revokeReason,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun PartyEvent.toOutboxMessage() = OutboxMessage(
        aggregateId = aggregateId,
        eventType = eventType,
        payload = objectMapper.writeValueAsString(envelope),
        createdAt = occurredAt,
    )
}
