// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.sca.application.port.out.EnrolledDeviceRepository
import com.openbank.sca.domain.model.EnrolledDevice
import com.openbank.sca.domain.model.ScaStatus
import com.openbank.sca.infrastructure.persistence.entity.EnrolledDeviceEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.LockModeType
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@ApplicationScoped
class EnrolledDeviceRepositoryImpl :
    EnrolledDeviceRepository,
    PanacheRepository<EnrolledDeviceEntity> {

    @Inject
    lateinit var outboxRepo: ScaOutboxRepositoryImpl

    @Inject lateinit var challenges: ScaChallengeRepositoryImpl

    @Inject lateinit var objectMapper: ObjectMapper

    @Inject lateinit var clock: Clock

    /**
     * ONE `Panache.withTransaction`, both legs inside it: the device row and its outbox row are
     * written by the same database transaction, so `xmin` is identical on both and a crash can
     * commit neither instead of only the first (#8679, pinned by `ScaEnrollOutboxAtomicityIT`).
     *
     * `persist`, not `merge`, is correct here even though the `@Id` is application-assigned:
     * `ScaService.enroll` returns the existing device (same party) or throws
     * `CredentialAlreadyEnrolledException` (another party) BEFORE reaching this method, so every
     * call that arrives is a fresh credential with a freshly generated id — an INSERT. The unique
     * constraint on `credential_id` is what catches the concurrent-enroll TOCTOU race, and
     * `ScaService` translates that 23505 into `CredentialAlreadyEnrolledException`.
     */
    override suspend fun saveWithOutbox(device: EnrolledDevice, outboxMessage: OutboxMessage): EnrolledDevice {
        val entity = EnrolledDeviceEntity.fromDomain(device)
        Panache.withTransaction {
            persistAndFlush(entity).chain { _ -> outboxRepo.persistInTransaction(outboxMessage) }
        }.awaitSuspending()
        return entity.toDomain()
    }

    override suspend fun findByCredentialId(credentialId: String): EnrolledDevice? =
        Panache.withSession { find("credentialId", credentialId).firstResult<EnrolledDeviceEntity>() }
            .awaitSuspending()?.toDomain()

    override suspend fun findByPartyId(partyId: UUID): List<EnrolledDevice> =
        Panache.withSession { find("partyId", partyId).list<EnrolledDeviceEntity>() }
            .awaitSuspending().map { it.toDomain() }

    override suspend fun revokeWithAudit(partyId: UUID, deviceId: UUID, actorId: String): Boolean =
        Panache.withTransaction {
            find("id = ?1 and partyId = ?2", deviceId, partyId)
                .withLock<EnrolledDeviceEntity>(LockModeType.PESSIMISTIC_WRITE)
                .firstResult<EnrolledDeviceEntity>().flatMap { device ->
                    when {
                        device == null -> Uni.createFrom().item(false)
                        device.revokedAt != null -> Uni.createFrom().item(true)
                        else -> revokeLocked(device, actorId)
                    }
                }
        }.awaitSuspending()

    private fun revokeLocked(device: EnrolledDeviceEntity, actorId: String): Uni<Boolean> {
        // All decision writers acquire this credential lock before a challenge lock.
        // Cancellation's version bump fences a verifier holding an earlier snapshot.
        device.revokedAt = OffsetDateTime.now(clock)
        return getSession().flatMap { it.flush() }.flatMap { getSession() }.flatMap { it.refresh(device) }
            .flatMap {
                challenges.update(
                    "status = ?1, version = version + 1 where consumedAt is null and status in (?2, ?3) " +
                        "and id in (select d.challengeId from ScaDeviceDecisionEntity d where d.credentialId = ?4)",
                    ScaStatus.CANCELLED,
                    ScaStatus.PENDING,
                    ScaStatus.COMPLETED,
                    device.credentialId,
                )
            }.flatMap { cancelled ->
                outboxRepo.persistInTransaction(revocationEvent(device, actorId, cancelled))
            }.replaceWith(true)
    }

    private fun revocationEvent(device: EnrolledDeviceEntity, actorId: String, cancelled: Int): OutboxMessage {
        val eventId = Ids.newId()
        return OutboxMessage(
            eventId = eventId,
            aggregateId = device.id,
            eventType = "DEVICE_REVOKED",
            payload = objectMapper.writeValueAsString(
                mapOf(
                    "eventId" to eventId.toString(),
                    "eventType" to "DEVICE_REVOKED",
                    "schemaVersion" to 1,
                    "sourceService" to "sca-service",
                    "aggregateType" to "SCA_DEVICE",
                    "aggregateId" to device.id.toString(),
                    "partyId" to device.partyId.toString(),
                    "credentialId" to device.credentialId,
                    "actorId" to actorId,
                    "actorType" to "AUTHENTICATED_PRINCIPAL",
                    "occurredAt" to device.revokedAt.toString(),
                    "cancelledChallenges" to cancelled,
                ),
            ),
        )
    }
}
