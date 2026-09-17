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
import com.openbank.party.domain.model.PartyActor
import com.openbank.party.domain.model.PartyEvent
import com.openbank.party.domain.model.PartyEvents
import com.openbank.party.domain.model.PartyMandate
import com.openbank.party.infrastructure.persistence.entity.PartyMandateEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.reactive.mutiny.Mutiny
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

    /** Joins the caller's transaction: every signer, outbox event and rule commit or roll back together. */
    internal fun upsertSignedInTransaction(session: Mutiny.Session, caseId: UUID, mandate: PartyMandate): Uni<Void> =
        session.createQuery(
            "FROM PartyMandateEntity WHERE principalPartyId = :principal AND agentPartyId = :agent " +
                "AND role = :role ORDER BY updatedAt DESC",
            PartyMandateEntity::class.java,
        ).setParameter("principal", mandate.principalPartyId)
            .setParameter("agent", mandate.agentPartyId)
            .setParameter("role", mandate.role.name)
            .setMaxResults(1)
            .singleResultOrNull
            .flatMap { existing ->
                val sameCase = existing?.evidenceRef?.startsWith("kyb-case:$caseId:") == true
                val sameCaseActive = existing?.status == MandateStatus.ACTIVE.name &&
                    sameCase &&
                    existing.authority == mandate.authority.name &&
                    existing.requiredSignatures == mandate.requiredSignatures &&
                    existing.source == mandate.source.name
                if (sameCaseActive) {
                    return@flatMap Uni.createFrom().voidItem()
                }
                // A later revocation wins; a later active grant with different facts is ambiguous,
                // so never silently mark this signed case as projected with the wrong authority.
                if (existing != null && existing.updatedAt.isAfter(mandate.validFrom)) {
                    if (existing.status != MandateStatus.ACTIVE.name) return@flatMap Uni.createFrom().voidItem()
                    require(false) { "later active mandate conflicts with signed KYB case $caseId" }
                }
                val granted = if (existing?.status == MandateStatus.ACTIVE.name) {
                    mandate.copy(id = existing.mandateId, createdAt = existing.createdAt)
                } else {
                    mandate
                }
                val event = PartyEvents.mandateGranted(granted, mandate.validFrom, PartyActor.system("kyb-signed-case"))
                val write = if (existing?.status == MandateStatus.ACTIVE.name) {
                    existing.fill(granted)
                    Uni.createFrom().voidItem()
                } else {
                    val entity = PartyMandateEntity().apply {
                        mandateId = granted.id
                        createdAt = granted.createdAt
                    }.fill(granted)
                    session.persist(entity)
                }
                write.flatMap { outboxRepository.persistInTransaction(event.toOutboxMessage()) }
            }

    private fun PartyMandateEntity.fill(m: PartyMandate) = apply {
        principalPartyId = m.principalPartyId
        agentPartyId = m.agentPartyId
        role = m.role.name
        authority = m.authority.name
        requiredSignatures = m.requiredSignatures
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
        requiredSignatures = requiredSignatures,
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
